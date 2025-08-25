(ns leaderboarder.db
  (:require [clojure.java.jdbc :as jdbc]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [cheshire.core :as cheshire]
            [migratus.core :as migratus]
            [buddy.hashers :as hashers]))

;; -----------------------------------------------------------------------------
;; Database initialization via Migratus

(def migration-config
  {:store         :database
   :migration-dir "migrations"})

(defn init-db
  "Run database migrations and return the db-spec."
  [db-spec]
  (migratus/migrate (assoc migration-config :db db-spec))
  db-spec)

(defn increment-all-credits
  "Increment credits for all users."
  [db-spec]
  (jdbc/execute! db-spec
                 (sql/format
                   (-> (h/update :users)
                       (h/set {:credits [:+ :credits 1]})))))

(defn create-user
  "Insert a new user row. If a `:password` key is present its value is hashed
  before storage."
  [db-spec user]
  (jdbc/insert! db-spec :users (update user :password #(when % (hashers/derive %)))))

(defn get-user
  "Retrieve a user row by username."
  [db-spec username]
  (first
    (jdbc/query db-spec
                (sql/format
                  (-> (h/select :*)
                      (h/from :users)
                      (h/where [:= :username username]))))))

(defn authenticate
  "Return the user row if `username` and `password` match, otherwise nil."
  [db-spec username password]
  (when-let [user (get-user db-spec username)]
    (when (hashers/check password (:password user))
      user)))

(defn use-credit
  "Spend a credit for a user. If `action` is :increment-self the user's own
  score is incremented; otherwise `target-id` is decremented. No-op if the
  acting user has no credits."
  [db-spec user-id action target-id]
  (jdbc/with-db-transaction
    [tx db-spec]
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
                                 (h/where [:= :id target-id]))))))))))
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
  "Construct a HoneySQL query for a leaderboard given filter criteria."
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
            :time_of_day (if-let [pred (time-of-day->predicate value)]
                           (h/where q pred)
                           q)
            ;; Unknown filter keys are ignored
            q))
        base-query
        filters))))

(defn create-leaderboard
  "Create a new leaderboard with the given filters and return its status."
  [db-spec creator-id name filters min-users]
  (jdbc/with-db-transaction
    [tx db-spec]
    (jdbc/insert! tx :leaderboards
                  {:creator_id creator-id
                   :name       name
                   :filters    (cheshire/generate-string filters)
                   :min_users  min-users})
    (let [users (jdbc/query tx (build-leaderboard-query tx filters min-users creator-id))]
      (if (and (>= (count users) min-users)
               ;; Ensure the creator is at the top of the sorted list
               (= (:id (first users)) creator-id))
        {:status :created :leaderboard users}
        {:status  :invalid
         :message "Does not meet criteria"}))))

(defn get-leaderboard
  "Return the leaderboard row and the associated users by applying the stored filters."
  [db-spec id]
  (let [lb (first
             (jdbc/query db-spec
                         (sql/format
                           (-> (h/select :*)
                               (h/from :leaderboards)
                               (h/where [:= :id id])))))]
    {:leaderboard lb
     :users       (jdbc/query db-spec
                              (build-leaderboard-query
                                db-spec
                                (cheshire/parse-string (:filters lb) true)
                                (:min_users lb)
                                (:creator_id lb)))}))

