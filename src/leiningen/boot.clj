(ns leiningen.boot
  (:require
    [clojure.pprint]
    [leinjacker.deps :as deps]
    [leiningen.repl :as repl]
    [leinjacker.eval :refer (eval-in-project)]
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
    [org.eclipse.jetty.servlet ServletHolder ServletMapping]
    [javax.servlet.http HttpServletRequest HttpServletResponse]
    [org.eclipse.jetty.util.resource Resource]
    [org.eclipse.jetty.webapp Configuration AbstractConfiguration WebAppContext
     WebAppClassLoader WebInfConfiguration WebXmlConfiguration
     MetaInfConfiguration FragmentConfiguration JettyWebXmlConfiguration
     TagLibConfiguration]))

(defn war-resources-path [project]
  (:war-resources-path project "war-resources"))

(defn find-webapp-root [project]
  (let [war-resources (war-resources-path project)]
    (if (.exists (io/as-file war-resources))
      war-resources
      (cond (.exists (io/as-file "src/test/webapp")) "src/test/webapp"
            (.exists (io/as-file "src/main/webapp")) "src/main/webapp"
            (.exists (io/as-file "public")) "public"))))

(def web-app-ignore
  #{"/WEB-INF" "/META-INF" "/.DS_Store"})

(defn servlet-mappings [project & ignore]
  (vec
    (distinct
      (concat (remove (fn [path]
                        (some #(.startsWith path %)
                              (concat web-app-ignore ignore)))
                      (map #(str \/ (.getName %)
                                 (when (.isDirectory %) "/*"))
                           (.listFiles
                             (io/as-file
                               (find-webapp-root project)))))
              (get-in project [:ring :default-mappings])))))

(def ->default-servlet-mapping
  '(defn ->default-servlet-mapping [mappings]
     {:pre [(sequential? mappings) (every? string? mappings)]}
     (doto (org.eclipse.jetty.servlet.ServletMapping.)
       (.setServletName "default")
       (.setPathSpecs (into-array String mappings)))))

(def add-servlet-mappings
  '(defn add-servlet-mappings [context & mappings]
     (doseq [mapping mappings]
       (.addServletMapping (.getServletHandler context) mapping))))

(defn add-jetty-dep [project]
  (update-in project
             [:dependencies]
             #(if (some (comp #{'org.eclipse.jetty/jetty-webapp} first) %)
                %
                (conj % '[org.eclipse.jetty/jetty-webapp "8.1.10.v20130312"]))))

(def meta-inf-resource
  '(defn meta-inf-resource [file]
     (`Resource/newResource
       (str "jar:file:" (.getCanonicalPath file) "!/META-INF/resources"))))

(def classpath-resources
  '(for [url (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))
         :let [file (io/as-file url)
               resource (Resource/newResource file)
               filename (.getName file)
               meta-resource (meta-inf-resource file)]
         :when (.endsWith filename ".jar")]
     (merge {:jar-resource resource}
            (when (.exists meta-resource)
              {:meta-inf-resource meta-resource}))))

(defn boot-server [webapp-root port default-mappings handlers]
  `(do
     (ns ~'boot)
     (require 'ring.util.servlet)
     (require '[clojure.string :as string])
     ~(cons 'do
            (for [handler (distinct (map (comp symbol namespace) handlers))]
              `(require '~handler)))
     ~meta-inf-resource
     ~->default-servlet-mapping
     ~add-servlet-mappings
     (def ~'ring-server (atom nil))
     (defn ~'start-server []
       (let [path# ~webapp-root
             context# (WebAppContext. path# "/")
             cloader# (WebAppClassLoader. context#)
             meta-conf# (MetaInfConfiguration.)
             mappings# (for [x# (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))
                             :let [file# (io/as-file x#)
                                   resource# (Resource/newResource file#)
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
                                       (str \/ % (when dir?# \*)))
                                    (.list meta-inf-resource#))))))]
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
         (when-not @~'ring-server (reset! ~'ring-server (Server. ~port)))
         (doseq [handler# ~(mapv (fn [x] `(var ~x)) handlers)
                 :let [ctx# (-> handler# meta :name name)]]
           (doto context#
             (.addServlet
               (ServletHolder.
                 (servlet/servlet handler#))
               (str (when (and ~(> (count handlers) 1) ctx#)
                      (str \/ ctx#)) "/*"))))
         (.addServlet (.getServletHandler context#)
                      (doto (ServletHolder.
                              (org.eclipse.jetty.servlet.DefaultServlet.))
                        (.setName "default")))
         (~'add-servlet-mappings
           context#
           (~'->default-servlet-mapping
             (distinct (apply concat ~default-mappings mappings#))))
         (doto @~'ring-server
           (.stop)
           (.setHandler context#)
           (.start))))
     (defn ~'stop-server [] (.stop @~'ring-server))
     (~'start-server)
     (ns ~'user)))

(defn update-project
  "Update the project map using a function."
  [project func & args]
  (vary-meta
   (apply func project args)
   update-in [:without-profiles] #(apply func % args)))

(defn add-deps [project & deps-specs]
  (reduce #(update-project %1 deps/add-if-missing %2)
          project
          deps-specs))

(defn boot [project & args]
  (let [{:keys [port]}
        (apply hash-map
               (mapcat (fn [[k v]] [(keyword (string/replace k #"^:" "")) v])
                       (partition 2 args)))
        port (try (Integer. port) (catch Exception _))
        port (or port (:port (:ring project)) 8080)
        handlers (or (:handler (:ring project)) (:boot project))
        handlers (if (sequential? handlers) handlers [handlers])
        mappings (servlet-mappings project)
        ;_ (clojure.pprint/pprint (boot-server (find-webapp-root project)
                                              ;port
                                              ;mappings
                                              ;handlers))
        ;_ (prn)
        project (add-deps project
                          '[ring/ring-servlet "1.1.8"]
                          '[org.eclipse.jetty/jetty-webapp "8.1.0.RC5"])
        project (update-in project
                           [:repl-options :init]
                           #(list 'do % (boot-server (find-webapp-root project)
                                                     port
                                                     mappings
                                                     handlers)))]
    (repl/repl project)))
