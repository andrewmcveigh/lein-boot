(ns leiningen.boot.hooks.test
  "Enables `lein test` to use the `lein boot` functionality and namespace"
  (:require 
    [leiningen.boot]
    [robert.hooke]))

(def ^:dynamic *in-hook?* nil)

(defn test-hook [f project & args]
  (if *in-hook?*
    (apply f project args)
    (binding [*in-hook?* true]
      (apply leiningen.boot/boot project "test" args))))

(defn activate []
  (robert.hooke/add-hook #'leiningen.test/test test-hook))
