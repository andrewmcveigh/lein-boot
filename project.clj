(defproject com.andrewmcveigh/lein-boot "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [ring/ring-servlet "1.1.8" :exclusions [javax.servlet/servlet-api]]]
  :profiles {:dev {:dependencies [[org.eclipse.jetty/jetty-webapp "8.1.10.v20130312"]]}}
  :eval-in-leiningen true)
