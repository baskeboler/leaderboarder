(ns leaderboarder.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [leaderboarder.core :as core]
            [clojure.java.jdbc :as jdbc]
            [honey.sql :as sql]))

(deftest get-user-test
  (testing "retrieve user after insert"
    (let [db {:dbtype "h2:mem" :dbname "userdb;DB_CLOSE_DELAY=-1"}
          db (core/init-db db)]
      (jdbc/insert! db :users {:username "alice"})
      (is (= "alice" (:username (core/get-user db "alice")))))))

(deftest use-credit-test
  (testing "credits increment and decrement operations"
    (let [db {:dbtype "h2:mem" :dbname "creditdb;DB_CLOSE_DELAY=-1"}
          db (core/init-db db)]
      (jdbc/insert! db :users {:username "alice" :credits 2 :score 0})
      (jdbc/insert! db :users {:username "bob" :credits 0 :score 2})
      (let [alice (core/get-user db "alice")
            bob (core/get-user db "bob")]
        ;; increment self
        (core/use-credit db (:id alice) :increment-self nil)
        (is (= {:score 1 :credits 1}
               (select-keys (core/get-user db "alice") [:score :credits])))
        ;; decrement other
        (core/use-credit db (:id alice) :attack (:id bob))
        (is (= 1 (:score (core/get-user db "bob"))))
        (is (= 0 (:credits (core/get-user db "alice"))))
        ;; no credits left -> no-op
        (core/use-credit db (:id alice) :increment-self nil)
        (is (= {:score 1 :credits 0}
               (select-keys (core/get-user db "alice") [:score :credits])))))))

(deftest time-of-day-predicate-test
  (testing "time-of-day filters"
    (let [hour (sql/format [:raw "EXTRACT(HOUR FROM last_active)"])]
      (is (= [:between hour 6 11]
             (#'core/time-of-day->predicate "morning")))
      (is (= [:or [:between hour 22 23] [:between hour 0 5]]
             (#'core/time-of-day->predicate "night")))
      (is (nil? (#'core/time-of-day->predicate "dawn"))))))

