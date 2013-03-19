(ns leiningen.boot
  (:require 
    [leiningen.repl :as repl]
    [ring.util.servlet :as servlet]
    [clojure.java.io :as io]) 
  (:gen-class :extends javax.servlet.http.HttpServlet) 
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


(def server (atom nil))

(defn find-webapp-root []
  (cond (.exists (io/as-file "src/test/webapp")) "src/test/webapp"
        (.exists (io/as-file "src/main/webapp")) "src/main/webapp"
        (.exists (io/as-file "public")) "public"))

(defn boot-server
  ([handler port]
   (let [path (find-webapp-root)
         context (WebAppContext. path "/")
         cloader (WebAppClassLoader. context)]
     (doseq [x (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))
             :let [file (io/as-file x)
                   resource (Resource/newResource file)
                   filename (.getName file)]
             :when (.endsWith filename ".jar")]
       (.addJars cloader resource))
     (.setClassLoader context cloader)
     (when-not @server (reset! server (Server. port)))
     (doto @server
       (.stop)
       (.setHandler (doto context
                      (.addServlet
                        (ServletHolder.
                          (proxy [javax.servlet.http.HttpServlet] []
                            (service [request response]
                              ((ring.util.servlet/make-service-method (resolve handler))
                               this request response))))
                        "/*")))
       (.start))))
  ([handler] (boot-server handler 8080)))

(defn boot [project & args]
  (let [handler (or (:handler (:ring project)) (:boot project))
        project (update-in project
                           [:repl-options :init]
                           #(list 'do %
                                  (list `boot-server handler)))]
    (repl/repl project)))
