(defproject com.andrewmcveigh/lein-boot "0.2.1"
  :description "A Leiningen plugin to run ring-servlet with Servlet 3 API."
  :url "http://github.com/andrewmcveigh/lein-boot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/tools.nrepl "0.2.3"]
                 [ring/ring-servlet "1.3.1" :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-webapp "8.1.16.v20140903"]
                 [leinjacker "0.4.1"]]
  :eval-in-leiningen true)
