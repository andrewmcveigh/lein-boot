(ns leiningen.boot.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [leiningen.boot.util :as util]
   [ring.util.servlet :as servlet])
  (:import
   [org.eclipse.jetty.server Server]
   [org.eclipse.jetty.servlet DefaultServlet ServletHolder]
   [org.eclipse.jetty.util.resource Resource]
   [org.eclipse.jetty.webapp Configuration WebAppContext WebAppClassLoader
    WebInfConfiguration WebXmlConfiguration MetaInfConfiguration
    FragmentConfiguration JettyWebXmlConfiguration]))

(def gen-mappings
  `(do
     (defn ~'gen-mappings* [context# meta-conf# cloader# files#]
       (for [file# files#
             :let [resource# (Resource/newResource file#)
                   filename# (.getName file#)]
             :when (.endsWith filename# ".jar")]
         (do
           (.addJars cloader# resource#)
           (let [meta-inf-resource# (Resource/newResource
                                    (str "jar:file:"
                                         (.getCanonicalPath file#)
                                         "!/META-INF/resources"))]
             (when (.exists meta-inf-resource#)
               (.addResource meta-conf#
                             context#
                             WebInfConfiguration/RESOURCE_URLS
                             meta-inf-resource#)
               (map #(let [dir?# (.isDirectory
                                  (Resource/newResource (str meta-inf-resource# %)))]
                       (str ~util/| % (when dir?# \*)))
                    (.list meta-inf-resource#)))))))
     (defn ~'gen-mappings [context# meta-conf# cloader#]
       (concat
        (~'gen-mappings*
         context# meta-conf# cloader#
         (map io/file (.getURLs (java.lang.ClassLoader/getSystemClassLoader))))
        (~'gen-mappings*
         context# meta-conf# cloader#
         (.listFiles (io/file "META-INF")))))))

(defn gen-main-form
  [ns-sym webapp-root handlers default-mappings port & {:keys [task]}]
  `(do
     ~@(if (#{:jar :uberjar} task)
         `((ns ~ns-sym
             (:require ring.util.servlet
                       [clojure.string :as ~'string]
                       ~@(distinct (map (comp symbol namespace) handlers)))
             (:gen-class)))
         `((println)
           (println "lein-boot...")
           (ns ~ns-sym)
           (require 'ring.util.servlet)
           (require '[clojure.string :as ~'string])
           ~@(for [handler (distinct (map (comp symbol namespace) handlers))]
               `(try
                  (require '~handler)
                  (catch RuntimeException e#
                    (println)
                    (println "Couldn't require handler namespace: " '~handler)
                    (println)
                    (.printStackTrace e#))))))
     ~util/meta-inf-resource
     ~util/->default-servlet-mapping
     ~util/add-servlet-mappings
     ~gen-mappings
     (def ~'ring-server (atom nil))
     (defn ~'start-server [& [port#]]
       (let [path# ~webapp-root
             context# (WebAppContext. path# ~util/|)
             cloader# (WebAppClassLoader. context#)
             meta-conf# (MetaInfConfiguration.)
             mappings#
             (distinct
              (remove nil?
                      (apply concat
                             ~default-mappings
                             (~'gen-mappings context# meta-conf# cloader#))))]
         (println "Mapping default handler:" mappings#)
         (.setConfigurationDiscovered context# true)
         (.setConfigurations
          context#
          (into-array Configuration
                      [(WebInfConfiguration.)
                       (WebXmlConfiguration.)
                       meta-conf#
                       (FragmentConfiguration.)
                       (JettyWebXmlConfiguration.)]))
         (.setClassLoader context# cloader#)
         (when-not @~'ring-server (reset! ~'ring-server (Server. (or port# ~port 0))))
         (doseq [handler# ~(mapv (fn [x] `(var ~x)) handlers)
                 :let [ctx# (-> handler# meta :name name)]]
           (doto context#
             (.addServlet (ServletHolder. (servlet/servlet handler#))
                          (str (when (and ~(> (count handlers) 1) ctx#)
                                 (str \/ ctx#)) "/*"))))
         (.addServlet (.getServletHandler context#)
                      (doto (ServletHolder. (DefaultServlet.))
                        (.setName "default")))
         (~'add-servlet-mappings
          context#
          (~'->default-servlet-mapping mappings#))
         (doto @~'ring-server
           (.stop)
           (.setHandler context#)
           (.start))
         (when-not ~(#{:jar :uberjar} task)
           (let [port# (.getLocalPort (first (.getConnectors @~'ring-server)))]
             (println "Started server on port: " port#)
             (spit "target/.boot-port" port#)))))
     (defn ~'stop-server [] (.stop @~'ring-server) (reset! ~'ring-server nil))
     ~(if (#{:jar :uberjar} task)
        `(defn ~'-main [& ~'args]
           {:pre [(or (empty? ~'args) (even? (count ~'args)))]}
           (let [args-parser# {:port #(Integer. %)}
                 ~'args (into {}
                              (map (fn [[k# v#]]
                                     {:pre [(.startsWith k# "--")]}
                                     (let [k# (keyword (string/replace k# "--" ""))]
                                       [k# ((args-parser# k# identity) v#)]))
                                   (partition 2 ~'args)))
                 port# (:port ~'args)]
             (~'start-server port#)))
        `(ns ~'user))))
