(ns leaderboarder.core
  (:require
    [integrant.core :as ig]
    [compojure.core :refer [routes GET POST]]
    [compojure.route :as route]
    [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
    [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
    [ring.util.response :refer [response resource-response]]
    [ring.adapter.jetty :as jetty]
    [leaderboarder.db :as db]
    [clojurewerkz.quartzite.scheduler :as qs]
    [clojurewerkz.quartzite.jobs :as jobs]
    [clojurewerkz.quartzite.triggers :as t]
    [clojurewerkz.quartzite.schedule.simple :as s]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:gen-class)
  (:import (org.eclipse.jetty.server Server)
           (org.slf4j MDC)))

(def tokens (atom {}))

(defn wrap-correlation-id [handler]
  (fn [req]
    (let [cid (or (get-in req [:headers "x-correlation-id"])
                  (str (java.util.UUID/randomUUID)))]
      (MDC/put "correlation-id" cid)
      (try
        (-> (handler (assoc req :correlation-id cid))
            (update :headers assoc "X-Correlation-ID" cid))
        (finally
          (MDC/remove "correlation-id"))))))

(defn wrap-request-logging [handler]
  (fn [req]
    (let [cid (:correlation-id req)]
      (log/info {:event :request/start
                 :correlation-id cid
                 :method (:request-method req)
                 :uri (:uri req)})
      (let [resp (handler req)
            status (:status resp)]
        (cond
          (< status 400) (log/info {:event :request/end :correlation-id cid :status status})
          (< status 500) (log/warn {:event :request/end :correlation-id cid :status status})
          :else (log/error {:event :request/end :correlation-id cid :status status}))
        resp))))

(defn wrap-error-handling [handler]
  (fn [req]
    (try
      (let [resp (handler req)]
        (if (>= (or (:status resp) 200) 400)
          (let [cid (:correlation-id req)
                b (:body resp)
                msg (cond
                      (string? b) b
                      (map? b) (or (:error b) "")
                      :else "")
                msg (if (str/blank? msg) "Error" msg)]
            {:status (:status resp)
             :headers (assoc (:headers resp) "Content-Type" "application/json")
             :body {:error msg :correlation-id cid}})
          resp))
      (catch Exception e
        (let [cid (:correlation-id req)]
          (log/error e {:event :request/exception :correlation-id cid})
          {:status 500
           :headers {"Content-Type" "application/json"}
           :body {:error "Internal server error" :correlation-id cid}})))))

;; -----------------------------------------------------------------------------
;; Periodic credit incrementer
;;
;; Each tick, all users receive one additional credit using a scheduler.

(jobs/defjob CreditIncrementJob [ctx]
  (let [data (.getMergedJobDataMap ctx)
        db-spec (.get data "db-spec")]
    (log/info {:event :scheduler/run :task :credit-increment})
    (try
      (db/increment-all-credits db-spec)
      (log/info {:event :scheduler/run :task :credit-increment :status :success})
      (catch Exception e
        (log/error e {:event :scheduler/run :task :credit-increment :status :failure})))))

(defn start-credit-incrementer
  "Initialize and start a Quartz scheduler that increments all user credits.
  Returns a map containing the scheduler and job identifiers."
  [db-spec interval-ms]
  (log/info {:event :scheduler/start :task :credit-increment :interval-ms interval-ms})
  (let [scheduler (qs/start (qs/initialize))
        job-key (jobs/key "credit-incrementer-job")
        trigger-key (t/key "credit-incrementer-trigger")
        job (jobs/build
              (jobs/of-type CreditIncrementJob)
              (jobs/with-identity job-key)
              (jobs/using-job-data {:db-spec db-spec}))
        trigger (t/build
                  (t/with-identity trigger-key)
                  (t/start-now)
                  (t/with-schedule (s/schedule
                                     (s/with-interval-in-milliseconds interval-ms)
                                     (s/repeat-forever))))]
    (qs/schedule scheduler job trigger)
    {:scheduler scheduler :job-key job-key :trigger-key trigger-key}))

(defn stop-credit-incrementer
  "Unschedule the periodic credit incrementer job and shut down the scheduler."
  [{:keys [scheduler job-key trigger-key]}]
  (log/info {:event :scheduler/stop :task :credit-increment})
  (when (and scheduler trigger-key)
    (qs/delete-trigger scheduler trigger-key))
  (when (and scheduler job-key)
    (qs/delete-job scheduler job-key))
  (when scheduler
    (qs/shutdown scheduler)))

;; HTTP routes and handler

(defn make-routes
  "Construct the Compojure route definitions bound to a particular db-spec."
  [db-spec]
  (routes
    (GET "/" [] (resource-response "index.html"))
    (POST "/users" req
      ;; Expect JSON body with :username, :password and optional profile fields
      (let [body (:body req)]
        (try
          (db/create-user db-spec (select-keys body [:username :password :geography :sex :age_group]))
          (response {:message "User created"})
          (catch Exception e
            (let [msg (or (.getMessage e) "")
                  cause (or (.getMessage (.getCause e)) "")
                  m (str msg " " cause)]
              (if (re-find #"(?i)unique|23505" m)
                {:status 409 :body {:error "Username already exists"}}
                {:status 400 :body {:error "Unable to create user"}}))))))
    (POST "/login" req
      (let [{:keys [username password]} (:body req)
            cid (:correlation-id req)]
        (if-let [user (db/authenticate db-spec username password)]
          (let [token (str (java.util.UUID/randomUUID))]
            (swap! tokens assoc token (:id user))
            (log/info {:event :auth/login-success :username username :correlation-id cid})
            (response {:token token
                       :credits (:credits user)
                       :score (:score user)}))
          (do
            (log/warn {:event :auth/login-failure :username username :correlation-id cid})
            {:status 401 :body {:error "Invalid credentials"}}))))
    (GET "/users/me" req
      (let [uid (:user-id req)]
        (when uid
          (db/touch-last-active db-spec uid)
          (response (select-keys (db/get-user-by-id db-spec uid) [:id :username :credits :score])))))
    (POST "/credits/use" req
      ;; Spend a credit: body should contain :action and optional :target_id or :target_username
      (let [{:keys [action target_id target_username]} (:body req)
            user-id (:user-id req)
            target-id (or target_id (some-> (and target_username (db/get-user db-spec (str/lower-case target_username))) :id))
            ;; Convert the action string into a keyword to match our use-credit function
            act (if (keyword? action) action (keyword action))]
        (db/touch-last-active db-spec user-id)
        (try
          (db/use-credit db-spec user-id act target-id)
          (response (select-keys (db/get-user-by-id db-spec user-id) [:credits :score]))
          (catch clojure.lang.ExceptionInfo e
            (let [{:keys [type]} (ex-data e)]
              (if (= type :validation)
                {:status 400 :body {:error (.getMessage e)}}
                {:status 500 :body {:error "Unable to use credit"}}))))))
    (POST "/leaderboards" req
      ;; Create a new filtered leaderboard
      (let [{:keys [name filters min_users]} (:body req)
            creator-id (:user-id req)]
        (db/touch-last-active db-spec creator-id)
        (response
          (db/create-leaderboard db-spec creator-id name filters min_users))))
    (GET "/leaderboards/:id" [id :as req]
      (when-let [uid (:user-id req)]
        (db/touch-last-active db-spec uid))
      (response (db/get-leaderboard db-spec (Integer/parseInt id))))
    (route/not-found "Not Found")))

(defn wrap-auth [handler]
  (fn [req]
    (if (#{"/" "/users" "/login"} (:uri req))
      (handler req)
      (if-let [auth (get-in req [:headers "authorization"])]
        (let [token (last (str/split auth #" "))
              cid (:correlation-id req)]
          (if-let [uid (@tokens token)]
            (do
              (log/info {:event :auth/authorized :user-id uid :correlation-id cid})
              (handler (assoc req :user-id uid)))
            (do
              (log/warn {:event :auth/invalid-token :token token :correlation-id cid})
              {:status 401 :body {:error "Unauthorized"}})))
        (do
          (log/warn {:event :auth/missing-token :correlation-id (:correlation-id req)})
          {:status 401 :body {:error "Unauthorized"}})))))

(defn make-handler
  "Wrap the routes in JSON and site defaults middleware."
  [routes]
  (-> routes
      wrap-auth
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      (wrap-defaults site-defaults)
      wrap-error-handling
      wrap-request-logging
      wrap-correlation-id))

;; -----------------------------------------------------------------------------
;; Integrant component definitions

;; DB spec initialization
(defmethod ig/init-key :db/spec [_ spec]
  (db/init-db spec))

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

(defmethod ig/halt-key! :server/jetty [_ ^Server server]
  (.stop server))

;; -----------------------------------------------------------------------------
;; Application configuration (environment-driven)

(defn- getenv
  "Read an environment variable, returning default if missing or blank."
  [k default]
  (let [v (System/getenv k)]
    (if (and v (not (str/blank? v))) v default)))

(defn- parse-long-safe [s default]
  (try
    (Long/parseLong (str s))
    (catch Exception _ default)))

(defn- build-db-spec []
  ;; Support a full JDBC URL via DB_URL; otherwise default to in-memory H2.
  (if-let [jdbc-url (let [u (System/getenv "DB_URL")] (when (and u (not (str/blank? u))) u))]
    {:connection-uri jdbc-url}
    (let [dbtype (getenv "DB_TYPE" "h2:mem")
          dbname (getenv "DB_NAME" "leaderboarder-mvp")]
      {:dbtype dbtype :dbname dbname})))

(defn build-config []
  (let [port (parse-long-safe (getenv "PORT" "3000") 3000)
        interval (parse-long-safe (getenv "SCHEDULER_INTERVAL_MS" (str (* 60 1000))) (* 60 1000))
        db-spec (build-db-spec)]
    {:db/spec db-spec
     :credit/incrementer {:db-spec (ig/ref :db/spec)
                          :interval-ms interval}
     :handler/routes {:db-spec (ig/ref :db/spec)}
     :handler/app {:routes (ig/ref :handler/routes)}
     :server/jetty {:handler (ig/ref :handler/app)
                    :port port}}))

(def config (build-config))

;; -----------------------------------------------------------------------------
;; Entry point

(defn -main
  [& _args]
  ;; Initialize the system.  The returned map of component instances is not
  ;; used here but could be bound to a var for later introspection or for
  ;; manual shutdown.
  (ig/init (build-config)))
