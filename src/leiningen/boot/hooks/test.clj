(ns leiningen.boot.hooks.test
  "Enables `lein test` to use the `lein boot` functionality and namespace"
  (:require 
    [leiningen.boot]
    [leiningen.boot.core :as core]
    [robert.hooke]))

(def ^:dynamic *in-hook?* nil)

(defn test-hook [f project & args]
  (core/server project)
  (apply f project args))

(defn activate []
  (robert.hooke/add-hook #'leiningen.test/test test-hook))
