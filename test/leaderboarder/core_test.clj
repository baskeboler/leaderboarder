(ns leaderboarder.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [leaderboarder.db :as db]
            [honey.sql :as sql]))

(deftest get-user-test
  (testing "retrieve user after insert"
    (let [db {:dbtype "h2:mem" :dbname "userdb;DB_CLOSE_DELAY=-1"}
          db (db/init-db db)]
      (db/create-user db {:username "alice"})
      (is (= "alice" (:username (db/get-user db "alice")))))))

(deftest use-credit-test
    (testing "credits increment and decrement operations"
      (let [db {:dbtype "h2:mem" :dbname "creditdb;DB_CLOSE_DELAY=-1"}
            db (db/init-db db)]
        (db/create-user db {:username "alice" :credits 2 :score 0})
        (db/create-user db {:username "bob" :credits 0 :score 2})
        (let [alice (db/get-user db "alice")
              bob (db/get-user db "bob")]
          ;; increment self
          (db/use-credit db (:id alice) :increment-self nil)
          (is (= {:score 1 :credits 1}
                 (select-keys (db/get-user db "alice") [:score :credits])))
          ;; decrement other
          (db/use-credit db (:id alice) :attack (:id bob))
          (is (= 1 (:score (db/get-user db "bob"))))
          (is (= 0 (:credits (db/get-user db "alice"))))
          ;; no credits left -> no-op
          (db/use-credit db (:id alice) :increment-self nil)
          (is (= {:score 1 :credits 0}
                 (select-keys (db/get-user db "alice") [:score :credits])))))))

(deftest time-of-day-predicate-test
  (testing "time-of-day filters"
      (let [hour (sql/format [:raw "EXTRACT(HOUR FROM last_active)"])]
        (is (= [:between hour 6 11]
               (#'db/time-of-day->predicate "morning")))
        (is (= [:or [:between hour 22 23] [:between hour 0 5]]
               (#'db/time-of-day->predicate "night")))
        (is (nil? (#'db/time-of-day->predicate "dawn"))))))

