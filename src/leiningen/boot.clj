(ns leiningen.boot
  (:require
    [clojure.pprint]
    [leinjacker.deps :as deps]
    [leiningen.repl :as repl]
    [leiningen.test :as test]
    [leiningen.core.eval :as eval]
    [leiningen.core.main :as main]
    [leiningen.core.project :as project]
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

(def | (System/getProperty "file.separator"))

(defn join-path [& args] 
  {:pre [(every? (comp not nil?) args)]}
  (let [ensure-no-delims #(string/replace % (re-pattern (format "(?:^%s)|(?:%s$)" | |)) "")]
    (str (when (.startsWith (first args) |) |)
         (string/join | (map ensure-no-delims args)))))

(defn war-resources-path [project]
  (:war-resources-path project "war-resources"))

(defn find-webapp-root [project]
  (let [war-resources (war-resources-path project)]
    (if (.exists (io/as-file war-resources))
      war-resources
      (cond (.exists (io/as-file (join-path "src" "test" "webapp"))) (join-path "src" "test" "webapp")
            (.exists (io/as-file (join-path "src" "main" "webapp"))) (join-path "src" "main" "webapp")
            (.exists (io/as-file (join-path "resources" "public"))) (join-path "resources" "public")
            (.exists (io/as-file "public")) "public"))))

(def web-app-ignore
  #{(str | "WEB-INF") (str | "META-INF") (str | ".DS_Store")})

(defn servlet-mappings [project & ignore]
  (let [root (find-webapp-root project)]
    (vec
      (distinct
        (concat (when (.exists (io/as-file root))
                  (remove (fn [path]
                            (some #(.startsWith path %)
                                  (concat web-app-ignore ignore)))
                          (map #(str \/ (.getName %)
                                     (when (.isDirectory %) "/*"))
                               (.listFiles (io/as-file root)))))
                (get-in project [:ring :default-mappings]))))))

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
     (println)
     (println "lein-boot...")
     (ns ~'boot)
     (require 'ring.util.servlet)
     (require '[clojure.string :as ~'string])
     ~(cons 'do
            (for [handler (distinct (map (comp symbol namespace) handlers))]
              `(try
                 (require '~handler)
                 (catch RuntimeException e#
                   (println)
                   (println "Couldn't require handler namespace: " '~handler)
                   (println)
                   (.printStackTrace e#)))))
     ~meta-inf-resource
     ~->default-servlet-mapping
     ~add-servlet-mappings
     (def ~'ring-server (atom nil))
     (defn ~'start-server [& [port#]]
       (println "Starting server on port: " (or port# ~port))
       (let [path# ~webapp-root
             context# (WebAppContext. path# |)
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
         (when-not @~'ring-server (reset! ~'ring-server (Server. (or port# ~port))))
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
     (defn ~'stop-server [] (.stop @~'ring-server) (reset! ~'ring-server nil))
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

(def tasks #{"test" "repl"})

(defn test2
  "Run the project's tests.

  Marking deftest or ns forms with metadata allows you to pick selectors to
  specify a subset of your test suite to run:

  (deftest ^:integration network-heavy-test
  (is (= [1 2 3] (:numbers (network-operation)))))

  Write the selectors in project.clj:

  :test-selectors {:default (complement :integration)
  :integration :integration
  :all (constantly true)}

  Arguments to this task will be considered test selectors if they are keywords,
  otherwise arguments must be test namespaces or files to run. With no
  arguments the :default test selector is used if present, otherwise all
  tests are run. Test selector arguments must come after the list of namespaces.

  A default :only test-selector is available to run select tests. For example,
  `lein test :only leiningen.test.test/test-default-selector` only runs the
  specified test. A default :all test-selector is available to run all tests."
  [project form2 & tests]
  (binding [main/*exit-process?* (if (= :leiningen (:eval-in project))
                                   false
                                   main/*exit-process?*)
            test/*exit-after-tests* (if (= :leiningen (:eval-in project))
                                      false
                                      test/*exit-after-tests*)]
    (let [project (project/merge-profiles project [:leiningen/test :test])
          [nses selectors] (#'test/read-args tests project)
          form (#'test/form-for-testing-namespaces nses nil (vec selectors))]
      (try (when-let [n (eval/eval-in-project
                          project
                          `(do ~form2
                               ;'(require 'boot)
                               ~form)
                          '(require 'clojure.test))]
             (when (and (number? n) (pos? n))
               (throw (ex-info "Tests Failed" {:exit-code n}))))
        (catch clojure.lang.ExceptionInfo e
          (main/abort "Tests failed."))))))

(defn boot [project & [task & more :as args]]
  (let [{:keys [port]}
        (if (tasks task)
          {}
          (apply hash-map
                 (mapcat (fn [[k v]] [(keyword (string/replace k #"^:" "")) v])
                         (partition 2 args))))
        port (try (Integer. port) (catch Exception _))
        port (or port (:port (:ring project)) 8080)
        handlers (or (:handler (:ring project)) (:boot project))
        handlers (if (sequential? handlers) handlers [handlers])
        mappings (servlet-mappings project)
        project (add-deps project
                          '[ring/ring-servlet "1.1.8"]
                          '[org.eclipse.jetty/jetty-webapp "8.1.0.RC5"])
        project (update-in project
                           [:repl-options :init]
                           #(list 'do
                                  %
                                  (boot-server (find-webapp-root project)
                                               port
                                               mappings
                                               handlers)
                                  '(boot/start-server)))]
    (case task
      "exit" nil
      "test" (test2 project
                    (boot-server (find-webapp-root project)
                                 port
                                 mappings
                                 handlers))
      (repl/repl project))))
