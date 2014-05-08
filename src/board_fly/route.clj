(ns board-fly.route
  (:use compojure.core
        beary-restful.core
        beary-restful.middleware)
        ;linedesigner-api.middleware)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            ;[ring.middleware [multipart-params :as mp]]
            ))
  ;(:require [linedesigner-api.handler.landing :as landing]
  ;          [hbs.core :as hbs]))

(defhandler index
  []
  (do
    (println "Hello, World!")
    (success {})))

(defroutes app-routes
  (GET "/" [] index)

  (route/resources "/"))

(def app
  (handler/site (-> app-routes
                    wrap-request-logging
                    ;; TODO
                    ;wrap-cookie-session
                    wrap-json-params
                    wrap-error-handling)))

