(ns leiningen.boot.hooks.jar
  "Enables `lein ring jar` to use the `lein boot` functionality and namespace"
  (:require 
    [leiningen.boot.server]
    [leiningen.ring.jar]
    [robert.hooke]))

(defn jar-hook [f project & args]
  (leiningen.boot.server/jar project))

(defn activate []
  (robert.hooke/add-hook #'leiningen.ring.jar/jar jar-hook))
