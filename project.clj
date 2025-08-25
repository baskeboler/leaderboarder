;; Project configuration for Leaderboarder MVP API
;;
;; This Leiningen project file defines the dependencies and entry point for
;; the MVP implementation of a leaderboard-based social game.  The
;; application exposes a small HTTP API for creating users, spending
;; credits to increment or decrement scores, and managing custom
;; filtered leaderboards.  It uses Ring and Compojure for routing and
;; middleware, HoneySQL for SQL query construction, Integrant for
;; component lifecycle management, and an in‑memory H2 database
;; suitable for development.  Adjust versions or database settings as
;; needed for production deployment.

(defproject leaderboarder "0.1.0-SNAPSHOT"
  :description "MVP backend for a credit-driven leaderboard social game"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/clojurescript "1.11.60"]
                 [reagent "1.2.0"]
                 [re-frame "1.3.0"]
                 [cljsjs/react "18.2.0-1"]
                 [cljsjs/react-dom "18.2.0-1"]
                 [compojure "1.7.1"]
                 [ring/ring-defaults "0.4.0"]
                 [ring/ring-json "0.5.1"]
                 [ring/ring-jetty-adapter "1.9.6"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [com.github.seancorfield/honeysql "2.6.1126"]
                 [integrant "0.8.1"]
                 [cheshire "5.13.0"]
                 [buddy/buddy-hashers "2.0.167"]
                 [clj-time "0.15.2"]
                 [com.h2database/h2 "2.3.232"]
                 [clojurewerkz/quartzite "2.1.0"]
                 [migratus "1.6.3"]]
  :plugins [[lein-cljsbuild "1.1.8"]]
  :main ^:skip-aot leaderboarder.core
  :target-path "target/%s"
  :cljsbuild {:builds [{:id "app"
                        :source-paths ["src/cljs"]
                        :compiler {:main leaderboarder.ui
                                   :output-to "resources/public/js/app.js"
                                   :optimizations :advanced
                                   :pretty-print false}}]}
  :profiles {:uberjar {:aot :all}})
