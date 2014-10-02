(ns leiningen.boot.util
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   [org.eclipse.jetty.server Server]
   [org.eclipse.jetty.servlet DefaultServlet ServletHolder]
   [org.eclipse.jetty.util.resource Resource]
   [org.eclipse.jetty.webapp Configuration WebAppContext WebAppClassLoader
    WebInfConfiguration WebXmlConfiguration MetaInfConfiguration
    FragmentConfiguration JettyWebXmlConfiguration]))

(def | (System/getProperty "file.separator"))

(def web-app-ignore #{"/WEB-INF" #_"/META-INF" "/.DS_Store"})

(defn join-path [& args]
  {:pre [(every? (comp not nil?) args)]}
  (let [ensure-no-delims #(string/replace % (re-pattern (format "(?:^%s)|(?:%s$)" | |)) "")]
    (str (when (.startsWith (first args) |) |)
         (string/join | (map ensure-no-delims args)))))

(defn resource-paths [project]
  (->> project
       :resource-paths
       (map #(do
               (let [dir-path (.getCanonicalPath (io/file "."))
                     r-path (.getCanonicalPath (io/file %))]
                 (-> r-path
                     (string/replace dir-path "")
                     (string/replace #"^/" "")))))
       distinct
       vec))

(defn resource-paths-public [resource-paths]
  (->> resource-paths
       (map #(io/file % "public"))
       (filter #(.exists %))))

(defn find-webapp-root [resource-paths]
  (let [a (first (resource-paths-public resource-paths))
        b (io/file (join-path "resources" "public"))
        c (io/file "public")
        d (io/file "META-INF/resources")]
    (cond a (.getCanonicalPath a)
          (.exists b) (.getCanonicalPath b)
          (.exists c) (.getCanonicalPath c)
          (.exists d) (.getCanonicalPath d))))

(defn servlet-mappings [project & ignore]
  (or (get-in project [:ring :servlet-mappings])
      (let [root (find-webapp-root (resource-paths project))]
        (vec
         (distinct
          (concat
           (when-let [root (io/file root)]
             (when (.exists root)
               (->> (.listFiles root)
                    (map #(str \/ (.getName %) (when (.isDirectory %) "/*")))
                    (remove (fn [path]
                              (some #(.startsWith path %)
                                    (concat web-app-ignore ignore)))))))
           (get-in project [:ring :default-mappings])))))))

(defn ->default-servlet-mapping [mappings]
  {:pre [(sequential? mappings) (every? string? mappings)]}
  (doto (org.eclipse.jetty.servlet.ServletMapping.)
    (.setServletName "default")
    (.setPathSpecs (into-array String mappings))))

(defn add-servlet-mappings [context & mappings]
  (doseq [mapping mappings]
    (.addServletMapping (.getServletHandler context) mapping)))

(defn meta-inf-resource [file]
  (org.eclipse.jetty.util.resource.Resource/newResource
   (str "jar:file:" (.getCanonicalPath file) "!/META-INF/resources")))

(defn default-main-namespace [project]
  (let [handler-sym (get-in project [:ring :handler])
        handler-sym (if (sequential? handler-sym)
                      (first handler-sym)
                      handler-sym)]
    (str (namespace handler-sym) ".main")))

(defn main-namespace [project]
  (or (get-in project [:ring :main])
      (default-main-namespace project)))

(defn str-path [dir? x]
  (str | x (when (dir? x) \*)))

(defn gen-mappings* [context meta-conf cloader files]
  (apply concat
         (for [file files
               :let [resource (Resource/newResource file)
                     filename (.getName file)]
               :when (.endsWith filename ".jar")]
           (do
             (.addJars cloader resource)
             (let [meta-inf-resource (Resource/newResource
                                      (str "jar:file:"
                                           (.getCanonicalPath file)
                                           "!/META-INF/resources"))]
               (when (.exists meta-inf-resource)
                 (.addResource meta-conf
                               context
                               WebInfConfiguration/RESOURCE_URLS
                               meta-inf-resource)
                 (map (partial str-path
                               #(.isDirectory (Resource/newResource (str meta-inf-resource %))))
                      (.list meta-inf-resource))))))))

(defn gen-mappings [context meta-conf cloader]
  (concat
   (gen-mappings*
    context meta-conf cloader
    (map io/file (.getURLs (java.lang.ClassLoader/getSystemClassLoader))))
   (gen-mappings*
    context meta-conf cloader
    (.listFiles (io/file "META-INF")))))

(defn mappings [defaults resource-paths context meta-conf class-loader]
  (distinct
   (remove nil?
           (remove web-app-ignore
                   (concat defaults
                           (gen-mappings context meta-conf class-loader))))))
