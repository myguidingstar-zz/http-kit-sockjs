(defproject http-kit-sockjs "0.2.0-SNAPSHOT"
  :description "A sockjs implementation on top of http-kit server"
  :url "https://github.com/jenshaase/methojure"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [http-kit "2.1.2"]
                 [cheshire "5.2.0"]
                 [compojure "1.1.5"]]
  :profiles {:dev {:dependencies [[ring/ring-devel "1.1.8"]]}})
