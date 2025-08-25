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
    [clojure.string :as str])
  (:gen-class)
  (:import (org.eclipse.jetty.server Server)))

(def tokens (atom {}))

;; -----------------------------------------------------------------------------
;; Periodic credit incrementer
;;
;; Each tick, all users receive one additional credit using a scheduler.

(jobs/defjob CreditIncrementJob [ctx]
  (let [data (.getMergedJobDataMap ctx)
        db-spec (.get data "db-spec")]
    (db/increment-all-credits db-spec)))

(defn start-credit-incrementer
  "Initialize and start a Quartz scheduler that increments all user credits.
  Returns a map containing the scheduler and job identifiers."
  [db-spec interval-ms]
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
  (when (and scheduler trigger-key)
    (qs/delete-trigger scheduler trigger-key))
  (when (and scheduler job-key)
    (qs/delete-job scheduler job-key))
  (when scheduler
    (qs/shutdown scheduler)))

;; -----------------------------------------------------------------------------
;; HTTP routes and handler

(defn make-routes
  "Construct the Compojure route definitions bound to a particular db-spec."
  [db-spec]
  (routes
    (GET "/" [] (resource-response "index.html"))
    (POST "/users" req
      ;; Expect JSON body with :username, :password and optional profile fields
      (let [body (:body req)]
        (db/create-user db-spec (select-keys body [:username :password :geography :sex :age_group]))
        (response {:message "User created"})))
    (POST "/login" req
      (let [{:keys [username password]} (:body req)]
        (if-let [user (db/authenticate db-spec username password)]
          (let [token (str (java.util.UUID/randomUUID))]
            (swap! tokens assoc token (:id user))
            (response {:token token}))
          {:status 401 :body {:error "Invalid credentials"}})))
    (POST "/credits/use" req
      ;; Spend a credit: body should contain :action and optional :target_id
      (let [{:keys [action target_id]} (:body req)
            user-id (:user-id req)
            ;; Convert the action string into a keyword to match our use-credit function
            act (if (keyword? action) action (keyword action))]
        (db/use-credit db-spec user-id act target_id)
        (response {:message "Credit used"})))
    (POST "/leaderboards" req
      ;; Create a new filtered leaderboard
      (let [{:keys [name filters min_users]} (:body req)
            creator-id (:user-id req)]
        (response
          (db/create-leaderboard db-spec creator-id name filters min_users))))
    (GET "/leaderboards/:id" [id]
      (response (db/get-leaderboard db-spec (Integer/parseInt id))))
    (route/not-found "Not Found")))

(defn wrap-auth [handler]
  (fn [req]
    (if (#{"/" "/users" "/login"} (:uri req))
      (handler req)
      (if-let [auth (get-in req [:headers "authorization"])]
        (let [token (last (str/split auth #" "))]
          (if-let [uid (@tokens token)]
            (handler (assoc req :user-id uid))
            {:status 401 :body {:error "Unauthorized"}}))
        {:status 401 :body {:error "Unauthorized"}}))))

(defn make-handler
  "Wrap the routes in JSON and site defaults middleware."
  [routes]
  (-> routes
      wrap-auth
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      (wrap-defaults site-defaults)))

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
;; Application configuration

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

(defn -main
  [& _args]
  ;; Initialize the system.  The returned map of component instances is not
  ;; used here but could be bound to a var for later introspection or for
  ;; manual shutdown.
  (ig/init config))
