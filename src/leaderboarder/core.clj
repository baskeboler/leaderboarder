;; -*- mode: clojure; -*-
;;
;; MVP implementation of a credit‑driven leaderboard API.
;;
;; This namespace wires together a simple HTTP service exposing a
;; handful of endpoints that back a multiplayer social game.  The
;; server maintains an in‑memory SQL database of users and
;; leaderboards.  Each user accrues credits over time which can be
;; spent to increment their own score or decrement another user's
;; score.  Leaderboards are defined by arbitrary filters (e.g.
;; geography, age group, time of day) and the creator must sit atop
;; their board with at least a configurable number of participants.
;;
;; The code is structured as a set of Integrant components: a
;; database connection, a periodic credit incrementer, HTTP route
;; definitions, a Ring handler with middleware, and a Jetty server.
;; Integrant cleanly handles component lifecycle (initialization and
;; teardown) when the application starts or stops.

(ns leaderboarder.core
  (:require
    [integrant.core :as ig]
    [compojure.core :refer [routes GET POST]]
    [compojure.route :as route]
    [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
    [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
    [ring.util.response :refer [response]]
    [clojure.java.jdbc :as jdbc]
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [cheshire.core :as cheshire]
    [ring.adapter.jetty :as jetty])
  (:gen-class))

;; -----------------------------------------------------------------------------
;; Database initialization
;;
;; Create two tables: users and leaderboards.  Users accrue credits and have
;; optional metadata fields.  Leaderboards store the ID of the creating user,
;; a name, JSON‑encoded filter specification, and a minimum participant count.

(defn init-db
  "Initialize the database schema for the game.
  Returns the db-spec so it can be used downstream as a dependency."
  [db-spec]
  ;; Create the users table if it doesn't already exist.  H2 will quietly
  ;; ignore the create if the table exists, which simplifies repeated
    ;; development runs.
  (jdbc/execute! db-spec
            (sql/format
              {:create-table-if-not-exists :users
               :with-columns
               [[:id :serial :primary-key]
                [:username :varchar :not-null :unique]
                [:score :integer [:default 0]]
                [:credits :integer [:default 0]]
                [:geography :varchar]
                [:sex :varchar]
                [:age_group :varchar]
                [:last_active :timestamp]]}))
  ;; Create the leaderboards table.
  (jdbc/execute! db-spec
    (sql/format
      {:create-table-if-not-exists :leaderboards
       :with-columns
       [[:id :serial :primary-key]
        [:creator_id :integer [:references :users :id]]
        [:name :varchar]
        [:filters :text]
        [:min_users :integer [:default 5]]]}))
  db-spec)

;; -----------------------------------------------------------------------------
;; Periodic credit incrementer
;;
;; Each tick, all users receive one additional credit.  In this simple MVP we
;; simulate a periodic task with a java.util.Timer.  The interval is
;; configurable (in milliseconds) via the Integrant config.  Production
;; deployments should use a more robust scheduler such as Quartz or cron.

(defn start-credit-incrementer
  "Return a Timer that increments every user's credits at a fixed interval."
  [db-spec interval-ms]
  (let [scheduler (java.util.Timer.)]
    (.schedule scheduler
      (proxy [java.util.TimerTask] []
        (run []
          ;; Use HoneySQL helpers to build the update: set credits = credits + 1
          (jdbc/execute! db-spec
            (sql/format
              (-> (h/update :users)
                  (h/set {:credits [:+ :credits 1]}))))))
      0 interval-ms)
    scheduler))

(defn stop-credit-incrementer
  "Cancel the periodic credit incrementer."
  [scheduler]
  (.cancel scheduler))

;; -----------------------------------------------------------------------------
;; Database helper functions

(defn get-user
  "Retrieve a user row by username."
  [db-spec username]
  (first
    (jdbc/query db-spec
      (sql/format
        (-> (h/select :*)
            (h/from :users)
            (h/where [:= :username username]))))))

(defn use-credit
  "Spend a credit for a user.  If `action` is :increment-self the user's own
  score is incremented; otherwise `target-id` is decremented.  No-op if the
  acting user has no credits."
  [db-spec user-id action target-id]
  (jdbc/with-db-transaction [tx db-spec]
    (let [user (first
                (jdbc/query tx
                  (sql/format
                    (-> (h/select :credits :score)
                        (h/from :users)
                        (h/where [:= :id user-id])))))]
      (when (> (:credits user 0) 0)
        ;; Deduct one credit from the acting user
        (jdbc/execute! tx
          (sql/format
            (-> (h/update :users)
                (h/set {:credits [:- :credits 1]})
                (h/where [:= :id user-id]))))
        (if (= action :increment-self)
          ;; Increment the user's own score
          (jdbc/execute! tx
            (sql/format
              (-> (h/update :users)
                  (h/set {:score [:+ :score 1]})
                  (h/where [:= :id user-id]))))
          ;; Otherwise decrement the target's score (if provided)
          (when target-id
            (jdbc/execute! tx
              (sql/format
                (-> (h/update :users)
                    (h/set {:score [:- :score 1]})
                    (h/where [:= :id target-id])))))))))

;; -----------------------------------------------------------------------------
;; Leaderboard utilities

  (def ^:private EXTRA-LIMIT-BUFFER 10)

  (defn- time-of-day->predicate
    "Return a HoneySQL predicate for a given time-of-day label, or nil if unsupported.
     Uses EXTRACT(HOUR FROM last_active), handling wrap-around for night."
    [value]
    (let [hour-expr (sql/format [:raw "EXTRACT(HOUR FROM last_active)"])]
      (case value
        "morning" [:between hour-expr 6 11]
        "afternoon" [:between hour-expr 12 17]
        "evening" [:between hour-expr 18 21]
        "night" [:or [:between hour-expr 22 23]
                 [:between hour-expr 0 5]]
        nil)))

  (defn build-leaderboard-query
    "Construct a HoneySQL query for a leaderboard given filter criteria.
    The returned query selects users sorted by descending score. Filters are
    applied dynamically based on the keys of the `filters` map. Supported
    filters include :geography, :sex, :age_group, and :time_of_day. The
    creator's ID is accepted but not currently used here; it's passed for future extension."
    [_db-spec filters min-users _creator-id]
    (let [base-query (-> (h/select :*)
                         (h/from :users)
                         ;; Sort by descending score, tiebreak by ascending id for stability
                         (h/order-by [:score :desc] [:id :asc])
                         ;; Fetch a few extra rows beyond min-users to allow filtering
                         (h/limit (+ min-users EXTRA-LIMIT-BUFFER)))]
      (sql/format
        (reduce-kv
          (fn [q key value]
            (case key
              :geography (h/where q [:= :geography value])
              :sex (h/where q [:= :sex value])
              :age_group (h/where q [:= :age_group value])
              :time_of_day
              (if-let [pred (time-of-day->predicate value)]
                (h/where q pred)
                q)
              ;; Unknown filter keys are ignored
              q))
          base-query
          filters))))
(defn create-leaderboard
  "Create a new leaderboard and return either the full leaderboard or an
  explanation of why the filters did not produce enough users or why the
  creator isn't at the top.

  The `filters` map is JSON-encoded for storage.  After inserting the
  leaderboard row, the query is re-run to validate the constraints."
  [db-spec creator-id name filters min-users]
  ;; Persist the filters as JSON
  (let [filters-json (cheshire/generate-string filters)]
    (jdbc/insert! db-spec :leaderboards
      {:creator_id creator-id
       :name name
       :filters filters-json
       :min_users min-users})
    (let [users (jdbc/query db-spec (build-leaderboard-query db-spec filters min-users creator-id))]
      (if (and (>= (count users) min-users)
               ;; Ensure the creator is at the top of the sorted list
               (= (:id (first users)) creator-id))
        {:status :created :leaderboard users}
        {:status :invalid
         :message "Does not meet criteria"})))))

(defn get-leaderboard
  "Return the leaderboard row and the associated users by applying the stored
  filters."
  [db-spec id]
  (let [lb (first
            (jdbc/query db-spec
              (sql/format
                (-> (h/select :*)
                    (h/from :leaderboards)
                    (h/where [:= :id id])))))]
    {:leaderboard lb
     :users (jdbc/query db-spec
              (build-leaderboard-query
                db-spec
                (cheshire/parse-string (:filters lb) true)
                (:min_users lb)
                (:creator_id lb)))}))

;; -----------------------------------------------------------------------------
;; HTTP routes and handler

(defn make-routes
  "Construct the Compojure route definitions bound to a particular db-spec."
  [db-spec]
  (routes
    (GET "/" [] (response "Welcome to Leaderboarder API MVP"))
    (POST "/users" req
      ;; Expect JSON body with :username and optional profile fields
      (let [body (:body req)]
        (jdbc/insert! db-spec :users
          (select-keys body [:username :geography :sex :age_group]))
        (response {:message "User created"})))
    (POST "/credits/use" req
      ;; Spend a credit: body should contain :user_id, :action and optional :target_id
      (let [{:keys [user_id action target_id]} (:body req)
            ;; Convert the action string into a keyword to match our use-credit function
            act (if (keyword? action) action (keyword action))]
        (use-credit db-spec user_id act target_id)
        (response {:message "Credit used"})))
    (POST "/leaderboards" req
      ;; Create a new filtered leaderboard
      (let [{:keys [creator_id name filters min_users]} (:body req)]
        (response
          (create-leaderboard db-spec creator_id name filters min_users))))
    (GET "/leaderboards/:id" [id]
      (response (get-leaderboard db-spec (Integer/parseInt id))))
    (route/not-found "Not Found")))

(defn make-handler
  "Wrap the routes in JSON and site defaults middleware."
  [routes]
  (-> routes
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      (wrap-defaults site-defaults)))

;; -----------------------------------------------------------------------------
;; Integrant component definitions

;; DB spec initialization
(defmethod ig/init-key :db/spec [_ spec]
  (init-db spec))

;; Periodic credit incrementer
(defmethod ig/init-key :credit/incrementer [_ {:keys [db-spec interval-ms]}]
  (start-credit-incrementer db-spec interval-ms))

(defmethod ig/halt-key! :credit/incrementer [_ scheduler]
  (stop-credit-incrementer scheduler))

;; Routing component
(defmethod ig/init-key :handler/routes [_ {:keys [db-spec]}]
  (make-routes db-spec))

;; Ring handler component
(defmethod ig/init-key :handler/app [_ {:keys [routes]}]
  (make-handler routes))

;; Jetty server component
(defmethod ig/init-key :server/jetty [_ {:keys [handler port]}]
  (jetty/run-jetty handler {:port port :join? false}))

(defmethod ig/halt-key! :server/jetty [_ server]
  (.stop server))

;; -----------------------------------------------------------------------------
;; Application configuration

;; A default Integrant configuration defining all components.  In a more
;; sophisticated application this data could live in an external EDN file
;; (e.g. resources/system.edn) and be loaded at runtime.

(def config
  {:db/spec {:dbtype "h2:mem" :dbname "leaderboarder-mvp"}
   :credit/incrementer {:db-spec (ig/ref :db/spec)
                        :interval-ms (* 60 1000)}
   :handler/routes {:db-spec (ig/ref :db/spec)}
   :handler/app {:routes (ig/ref :handler/routes)}
   :server/jetty {:handler (ig/ref :handler/app)
                  :port 3000}})

;; -----------------------------------------------------------------------------
;; Entry point

;; The -main function is invoked when the application is started from the
;; command line (via `lein run` or `clojure -M`).  It boots all the
;; components defined in `config`.  Integrant will also handle halting
;; components cleanly on JVM shutdown.

(defn -main
  [& _args]
  ;; Initialize the system.  The returned map of component instances is not
  ;; used here but could be bound to a var for later introspection or for
  ;; manual shutdown.
  (ig/init config))
