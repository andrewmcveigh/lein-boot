(ns leiningen.boot.server
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.pprint]
   [leiningen.boot.core :as core]
   [leiningen.boot.util :as util]
   [leiningen.core.eval :as eval]
   [leiningen.core.main :as main]
   [leiningen.core.project :as project]
   [leiningen.jar]
   [leiningen.repl :as repl]
   [leiningen.ring.server :refer (add-server-dep)]
   [leiningen.ring.util :refer [compile-form ensure-handler-set! update-project]]
   [leiningen.test :as test]
   [leiningen.uberjar]
   [leinjacker.deps :as deps]
   [leinjacker.eval :refer (eval-in-project)]
   [ring.util.servlet :as servlet])
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

(defn compile-main [project]
  (let [main-ns (symbol (util/main-namespace project))
        options (-> (select-keys project [:ring])
                    (assoc-in [:ring :open-browser?] false)
                    (assoc-in [:ring :stacktraces?] false)
                    (assoc-in [:ring :auto-reload?] false))
        handlers (-> project :ring :handler)
        handlers (if (sequential? handlers) handlers [handlers])
        port (-> project :ring :port)
        default-mappings (util/servlet-mappings project)]
    (compile-form project main-ns
                  (core/gen-main-form
                   main-ns
                   (util/find-webapp-root project)
                   handlers
                   default-mappings
                   port
                   :task :jar))))

(defn add-main-class [project]
  (update-project project assoc :main (symbol (util/main-namespace project))))

(defn alter-project
  "Update the project map using a function."
  [project func & args]
  (vary-meta
   (apply func project args)
   update-in [:without-profiles] #(apply func % args)))

(defn add-deps [project & deps-specs]
  (reduce #(alter-project %1 deps/add-if-missing %2)
          project
          deps-specs))

(defn jar
  "Create an executable $PROJECT-$VERSION.jar file."
  [f project]
  (ensure-handler-set! project)
  (let [project (-> project add-server-dep add-main-class)
        project (add-deps project
                          '[ring/ring-servlet "1.1.8"]
                          '[org.eclipse.jetty/jetty-webapp "8.1.0.RC5"])]
    (compile-main project)
    (leiningen.jar/jar project)))

(defn uberjar
  "Create an executable $PROJECT-$VERSION.jar file."
  [project]
  (prn `uberjar)
  (ensure-handler-set! project)
  (let [project (-> project add-server-dep add-main-class)
        project (add-deps project
                          '[ring/ring-servlet "1.1.8"]
                          '[org.eclipse.jetty/jetty-webapp "8.1.0.RC5"])]
    (compile-main project)
    (leiningen.uberjar/uberjar project)))
