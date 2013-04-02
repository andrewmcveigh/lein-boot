(ns leiningen.boot
  (:require
    [clojure.pprint]
    [leiningen.repl :as repl]
    [ring.util.servlet :as servlet]
    [clojure.java.io :as io]
    [clojure.string :as string])
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
    [org.eclipse.jetty.webapp Configuration WebAppContext WebAppClassLoader
     WebInfConfiguration WebXmlConfiguration MetaInfConfiguration FragmentConfiguration
     JettyWebXmlConfiguration TagLibConfiguration]))

(defn find-webapp-root []
  (cond (.exists (io/as-file "src/test/webapp")) "src/test/webapp"
        (.exists (io/as-file "src/main/webapp")) "src/main/webapp"
        (.exists (io/as-file "public")) "public"))

(defn boot-server [port handler]
  `(do
     (require 'ring.util.servlet)
     (require '~(symbol (namespace handler)))
     (def ~'ring-server (atom nil))
     (defn ~'start-server []
       (let [path# ~(find-webapp-root)
             context# (WebAppContext. path# "/")
             cloader# (WebAppClassLoader. context#)
             meta-conf# (MetaInfConfiguration.)]
         (.setConfigurationDiscovered context# true)
         (doseq [x# (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))
                 :let [file# (io/as-file x#)
                       resource# (Resource/newResource file#)
                       filename# (.getName file#)]
                 :when (.endsWith filename# ".jar")]
           (let [meta-inf-resource# (Resource/newResource
                                      (str "jar:file:"
                                           (.getCanonicalPath file#)
                                           "!/META-INF/resources"))]
             (when (.exists meta-inf-resource#)
               (.addResource meta-conf#
                             context#
                             WebInfConfiguration/RESOURCE_URLS
                             meta-inf-resource#)))
           (.addJars cloader# resource#))
         (.setConfigurations
           context#
           (into-array Configuration
                       [(WebInfConfiguration.)
                        (WebXmlConfiguration.)
                        meta-conf#
                        (FragmentConfiguration.)
                        (JettyWebXmlConfiguration.)]))
         (.setClassLoader context# cloader#)
         (when-not @~'ring-server (reset! ~'ring-server (Server. ~port)))
         (doto @~'ring-server
           (.stop)
           (.setHandler (doto context#
                          (.addServlet
                            (ServletHolder.
                              (servlet/servlet  (var ~handler)))
                            "/*")))
           (.start))))
     (defn ~'stop-server [] (.stop @~'ring-server))
     (~'start-server)))

(defn boot [project & args]
  (let [{:keys [port]}
        (apply hash-map
               (mapcat (fn [[k v]] [(keyword (string/replace k #"^:" "")) v])
                       (partition 2 args)))
        port (try (Integer. port) (catch Exception _))
        port (or port (:port (:ring project)) 8080)
        handler (or (:handler (:ring project)) (:boot project))
        project (update-in project
                           [:repl-options :init]
                           #(list 'do % (boot-server port handler)))]
    (repl/repl project)))
