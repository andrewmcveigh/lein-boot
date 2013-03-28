(ns leiningen.boot
  (:require 
    [clojure.pprint]
    [leiningen.repl :as repl]
    [ring.util.servlet :as servlet]
    [clojure.java.io :as io]) 
  (:import
    [org.eclipse.jetty.server Server Request]
    [org.eclipse.jetty.server.handler AbstractHandler]
    [org.eclipse.jetty.server.nio SelectChannelConnector]
    [org.eclipse.jetty.server.ssl SslSelectChannelConnector]
    [org.eclipse.jetty.util.thread QueuedThreadPool]
    [org.eclipse.jetty.util.ssl SslContextFactory]
    [org.eclipse.jetty.servlet ServletHolder]
    [javax.servlet.http HttpServletRequest HttpServletResponse]
    [org.eclipse.jetty.util.resource Resource]
    [org.eclipse.jetty.webapp WebAppContext WebAppClassLoader]))

(defn find-webapp-root []
  (cond (.exists (io/as-file "src/test/webapp")) "src/test/webapp"
        (.exists (io/as-file "src/main/webapp")) "src/main/webapp"
        (.exists (io/as-file "public")) "public"))

(defn boot [project & args]
  (let [handler (or (:handler (:ring project)) (:boot project))
        port 8080
        server (atom nil)
        boot-server `(do
                       (require 'ring.util.servlet)
                       ;(prn ~(symbol (namespace handler)))
                       (require '~(symbol (namespace handler)))
                       (let [path# ~(find-webapp-root)
                           context# (WebAppContext. path# "/")
                           cloader# (WebAppClassLoader. context#)]
                       (doseq [x# (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))
                               :let [file# (io/as-file x#)
                                     resource# (Resource/newResource file#)
                                     filename# (.getName file#)]
                               :when (.endsWith filename# ".jar")]
                         (.addJars cloader# resource#))
                       (.setClassLoader context# cloader#)
                       ;(when-not (deref ~server) (reset! ~server (Server. ~port)))
                       (doto (Server. ~port) 
                         (.stop)
                         (.setHandler (doto context#
                                        (.addServlet
                                          (ServletHolder.
                                            (proxy [javax.servlet.http.HttpServlet] []
                                              (service [request# response#]
                                                ((servlet/make-service-method (resolve '~handler))
                                                 ~'this request# response#))))
                                          "/*")))
                         (.start))))
        _ (do (clojure.pprint/pprint boot-server) (prn))
        project (update-in project
                           [:repl-options :init]
                           #(list 'do % boot-server))]
    (repl/repl project)))
