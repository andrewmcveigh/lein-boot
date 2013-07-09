(ns leiningen.boot.hooks.install
  "Enables `lein ring install` to use the `lein boot` functionality and namespace.
  Installs the current project to the local repository."
  (:require 
    leiningen.boot.server
    leiningen.ring.uberjar
    leiningen.install
    robert.hooke  
    [cemerick.pomegranate.aether :as aether]
    [leiningen.core.project :as project]
    [leiningen.core.main :as main]
    [leiningen.jar :as jar]
    [leiningen.pom :as pom]
    [clojure.java.io :as io])
  (:import
    [java.util.jar JarFile]
    [java.util UUID]))

(defn install
  "Install current project to the local repository."
  [project]
  (when (not (or (:install-releases? project true)
                 (pom/snapshot? project)))
    (main/abort "Can't install release artifacts when :install-releases?"
                "is set to false."))
  (let [jarfiles (leiningen.boot.server/uberjar project)
        pomfile (pom/pom project)
        local-repo (:local-repo project)]
    (prn jarfiles)
    (aether/install
     :coordinates [(symbol (:group project) (:name project))
                   (str (:version project) "-standalone")]
     :artifact-map jarfiles  
     :jar-file jarfiles
     :pom-file (io/file pomfile)
     :local-repo local-repo)))

(defn install-hook [f project & args]
  (install project))

(defn activate []
  (robert.hooke/add-hook #'leiningen.install/install install-hook))
