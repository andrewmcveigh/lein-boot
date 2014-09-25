(ns leiningen.boot.util
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]))

(def | (System/getProperty "file.separator"))

(def web-app-ignore #{"/WEB-INF" #_"/META-INF" "/.DS_Store"})

(defn join-path [& args]
  {:pre [(every? (comp not nil?) args)]}
  (let [ensure-no-delims #(string/replace % (re-pattern (format "(?:^%s)|(?:%s$)" | |)) "")]
    (str (when (.startsWith (first args) |) |)
         (string/join | (map ensure-no-delims args)))))

(defn find-webapp-root [project]
  (let [a (->> (:resource-paths project)
               (map #(io/file % "public"))
               (filter #(.exists %))
               (first))
        b (io/file (join-path "resources" "public"))
        c (io/file "public")
        d (io/file "META-INF/resources")]
    (cond a (.getCanonicalPath a)
          (.exists b) (.getCanonicalPath b)
          (.exists c) (.getCanonicalPath c)
          (.exists d) (.getCanonicalPath d))))

(defn servlet-mappings [project & ignore]
  (or (get-in project [:ring :servlet-mappings])
      (let [root (find-webapp-root project)]
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

(def meta-inf-resource
  '(defn meta-inf-resource [file]
     (`Resource/newResource
       (str "jar:file:" (.getCanonicalPath file) "!/META-INF/resources"))))

(defn default-main-namespace [project]
  (let [handler-sym (get-in project [:ring :handler])
        handler-sym (if (sequential? handler-sym)
                      (first handler-sym)
                      handler-sym)]
    (str (namespace handler-sym) ".main")))

(defn main-namespace [project]
  (or (get-in project [:ring :main])
      (default-main-namespace project)))
