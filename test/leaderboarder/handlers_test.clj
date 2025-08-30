(ns leaderboarder.handlers-test
  (:require [cheshire.core :as cheshire]
            [clojure.test :refer [deftest is testing]]
            [leaderboarder.core :as core]))

(deftest correlation-id-and-header
  (testing "wrap-correlation-id generates an id and makes it available to handler and response header"
    (let [handler-fn (fn [req] {:status 200 :headers {} :body (or (:correlation-id req) "no-cid")})
          app (core/make-handler handler-fn)
          req {:request-method :get :uri "/" :headers {}}
          resp (app req)
          hdr (get-in resp [:headers "X-Correlation-ID"])
          body (:body resp)]
      (is (string? hdr) "X-Correlation-ID header should be present")
      (is (= hdr body) "handler should receive same correlation id"))))

(deftest auth-rejection-and-acceptance
  (testing "unauthenticated requests to protected endpoints are rejected"
    (let [handler-fn (fn [_req] {:status 200 :headers {} :body "ok"})
          app (core/make-handler handler-fn)
          resp (app {:request-method :get :uri "/protected" :headers {}})
          body-map  (:body resp) ]
      (is (= 401 (:status resp)))
      (is (= "Unauthorized" (:error body-map)))))
  (testing "authenticated requests with token are allowed"
    (let [token "test-token-123"
          _ (swap! core/tokens assoc token 42)
          handler-fn (fn [_req] {:status 200 :headers {} :body "ok"})
          app (core/make-handler handler-fn)
          resp (app {:request-method :get :uri "/protected"
                     :headers {"authorization" (str "Bearer " token)}})]
      (try
        (is (= 200 (:status resp)))
        (finally
          (swap! core/tokens dissoc token))))))

(deftest error-handling-exception-path
  (testing "exceptions in handlers are converted to a 500 with standardized body"
    (let [handler-fn (fn [_req] (throw (ex-info "boom" {})))
          app (core/make-handler handler-fn)
          resp (app {:request-method :get :uri "/" :headers {}})]
      (is (= 500 (:status resp)))
      (is (= "Internal server error" (get-in resp [:body :error])))
      (is (some? (get-in resp [:body :correlation-id]))))))

