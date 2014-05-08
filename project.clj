(defproject board-fly "0.1.0"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [environ "0.3.0"]
                 [compojure "1.1.3"]
                 [org.clojure/tools.logging "0.2.4"]
                 [org.clojure/data.json "0.2.0"]
                 [clj-http "0.7.6"]
                 ;[ring/ring-core "1.2.1"]
                 ;[ring/ring-servlet "1.2.1"]
                 [ring/ring-core "1.2.1"
                  :exclusions [javax.servlet/servlet-api]]
                 [ring/ring-servlet "1.2.1"
                  :exclusions [javax.servlet/servlet-api]]
                 [javax.servlet/javax.servlet-api "3.1.0"]
                 [com.corundumstudio.socketio/netty-socketio "1.6.4"]
                 [beary-restful "0.1.1"]]
  :java-source-paths ["src/java"]
  :main ^:skip-aot board-fly.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
