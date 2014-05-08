(ns board-fly.core
  (:gen-class)
  (:use [board-fly.ring.adapter.netty]
        [board-fly.route :only [app]]))

(defn start-server
  []
  (run-netty app {:port 4000
                  :netty {"reuseAddress" true}}))


(defn -main
  [& args]
  (println "start server")
  (start-server))
