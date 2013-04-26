(ns leiningen.boot.hooks.test
  "Enables `lein test` to use the `lein boot` functionality and namespace"
  (:require 
    [leiningen.boot]
    [robert.hooke]))

(defn test-hook [f project & args]
  (apply leiningen.boot/boot project "test" args))

(defn activate []
  (robert.hooke/add-hook #'leiningen.test/test test-hook))
