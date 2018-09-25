(defproject syncsnap "0.2.0"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.cli "0.3.5"]
                 [clj-time "0.14.2"]
                 ]
  :main ^:skip-aot syncsnap.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
