(ns leiningen.boot.hooks.uberjar
  "Enables `lein ring uberjar` to use the `lein boot` functionality and namespace"
  (:require 
    leiningen.boot.server
    leiningen.ring.uberjar
    robert.hooke))

(defn uberjar-hook [f project & args]
  (leiningen.boot.server/uberjar project))

(defn activate []
  (robert.hooke/add-hook #'leiningen.ring.uberjar/uberjar uberjar-hook))
