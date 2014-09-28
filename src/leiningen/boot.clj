(ns leiningen.boot
  (:require
   [clojure.java.io :as io]
   [clojure.pprint]
   [clojure.string :as string]
   [clojure.tools.nrepl.ack :as nrepl.ack]
   [clojure.tools.nrepl.server :as nrepl.server]
   [leiningen.boot.core :as core]
   [leiningen.boot.util :as util]
   [leiningen.core.eval :as eval]
   [leiningen.core.main :as main]
   [leiningen.core.user :as user]
   [leiningen.repl :as repl]
   [leiningen.test :as test]
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

(def tasks #{"pprint" "exit" "repl"})

(defn boot [project & [task & more :as args]]
  (let [{:keys [port]}
        (if (tasks task)
          {}
          (apply hash-map
                 (mapcat (fn [[k v]] [(keyword (string/replace k #"^:" "")) v])
                         (partition 2 args))))
        port (try (Integer. port) (catch Exception _))]
    (case task
      "exit" nil
      "test" (do (core/server project)
                 (test/test project))
      (->> (core/server project port)
           (repl/client project)))))
