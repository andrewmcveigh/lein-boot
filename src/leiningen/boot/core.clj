(ns leiningen.boot.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.nrepl.ack :as nrepl.ack]
   [clojure.tools.nrepl.server :as nrepl.server]
   [leiningen.boot.util :as util]
   [leiningen.core.eval :as eval]
   [leiningen.core.main :as main]
   [leiningen.core.user :as user]
   [leiningen.core.project :as project]
   [leiningen.repl :as repl]
   [leiningen.ring.util :refer [update-project]]
   [leinjacker.deps :as deps]
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
  [project ns-sym handlers default-mappings port & {:keys [task]}]
  `(do
     ~@(if (#{:jar :uberjar} task)
         `((ns ~ns-sym
             (:require ring.util.servlet
                       leiningen.boot.util
                       [clojure.string :as ~'string]
                       ~@(distinct (map (comp symbol namespace) handlers)))
             (:gen-class)))
         `((println)
           (println "lein-boot...")
           (ns ~ns-sym)
           (require 'ring.util.servlet)
           (require 'leiningen.boot.util)
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
       (let [path# (util/find-webapp-root ~(util/resource-paths project))
             context# (WebAppContext. path# ~util/|)
             cloader# (WebAppClassLoader. context#)
             meta-conf# (MetaInfConfiguration.)
             mappings#
             (distinct
              (remove nil?
                      (apply concat
                             ~default-mappings
                             (remove ~util/web-app-ignore
                                     (~'gen-mappings context# meta-conf# cloader#)))))]
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
         (let [port# (.getLocalPort (first (.getConnectors @~'ring-server)))]
           (println "Started server on port: " port#)
           (println "Classpath:")
           (doseq [x# (.getURLs (java.lang.ClassLoader/getSystemClassLoader))]
             (println x#))
           (when-not ~(#{:jar :uberjar} task)
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

(defn add-deps [project & deps-specs]
  (reduce #(update-project %1 deps/add-if-missing %2)
          project
          deps-specs))

(defn add-project-deps [project]
  (let [ver (->> (:plugins project)
                 (filter #(= 'com.andrewmcveigh/lein-boot (first %)))
                 first
                 second)]
    (add-deps project
              `[com.andrewmcveigh/lein-boot ~ver]
              '[org.clojure/tools.nrepl "0.2.5"]
              '[ring/ring-core "1.3.1"]
              '[ring/ring-servlet "1.3.1"]
              '[org.eclipse.jetty/jetty-webapp "8.1.16.v20140903"])))

(defn server [project & [port]]
  (let [headless? false
        handlers (or (:handler (:ring project)) (:boot project))
        handlers (if (sequential? handlers) handlers [handlers])
        mappings (util/servlet-mappings project)
        project (project/merge-profiles project [:repl])
        austin? (some (comp #{'com.cemerick/austin} first) (:dependencies project))
        project (if austin?
                  (update-in project
                             [:injections]
                             (fnil into [])
                             '[(when (try (require 'cemerick.austin.repls) true
                                          (catch Exception _))
                                 (defn cljs-repl []
                                   (let [repl-env (reset! cemerick.austin.repls/browser-repl-env
                                                          (cemerick.austin/repl-env))]
                                     (cemerick.austin.repls/cljs-repl repl-env))))])
                  project)
        project (add-project-deps project)
        cfg {:host (repl/repl-host project)
             :port (repl/repl-port project)}]
    (nrepl.ack/reset-ack-port!)
    (when-not (repl/nrepl-dependency? project)
      (main/info "Warning: no nREPL dependency detected.")
      (main/info "Be sure to include org.clojure/tools.nrepl in :dependencies"
                 "of your profile."))
    (let [prep-blocker @eval/prep-blocker
          ack-port (:port @repl/ack-server)]
      (-> (bound-fn []
            (binding [eval/*pump-in* false]
              (eval/eval-in-project
               project
               `(let [server# (clojure.tools.nrepl.server/start-server
                               :bind ~(:host cfg) :port ~(:port cfg)
                               :ack-port ~ack-port
                               :handler ~(#'repl/handler-for project))
                      port# (:port server#)
                      repl-port-file# (apply io/file ~(if (:root project)
                                                        [(:root project) ".nrepl-port"]
                                                        [(user/leiningen-home) "repl-port"]))
                      legacy-repl-port# (if (.exists (io/file ~(:target-path project)))
                                          (io/file ~(:target-path project) "repl-port"))]
                  (spit (doto repl-port-file# .deleteOnExit) port#)
                  (when legacy-repl-port#
                    (spit (doto legacy-repl-port# .deleteOnExit) port#))
                  @(promise))
               `(do ~(when-let [init-ns (repl/init-ns project)]
                       `(try (doto '~init-ns require in-ns)
                             (catch Exception e# (println e#) (ns ~init-ns))))
                    ~@(for [n (#'repl/init-requires project)]
                        `(try (require ~n)
                              (catch Throwable t#
                                (println "Error loading" (str ~n ":")
                                         (or (.getMessage t#) (type t#))))))
                    ~(gen-main-form
                      project
                      'boot
                      handlers
                      mappings
                      port
                      :task :boot)
                    (~'boot/start-server)))))
          (Thread.)
          (.start))
      (when project @prep-blocker)
      (when headless? @(promise))
      (if-let [repl-port (nrepl.ack/wait-for-ack
                          (get-in project [:repl-options :timeout] 60000))]
        (do (main/info "nREPL server started on port"
                       repl-port "on host" (:host cfg))
            repl-port)
        (main/abort "REPL server launch timed out.")))))
