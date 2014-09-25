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
   [leiningen.core.project :as project]
   [leiningen.repl :as repl]
   [leiningen.ring.util :refer [update-project]]
   [leiningen.test :as test]
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

(defn add-deps [project & deps-specs]
  (reduce #(update-project %1 deps/add-if-missing %2)
          project
          deps-specs))

(def tasks #{"pprint" "exit" "repl"})

(defn server [project cfg headless? port mappings handlers]
  (nrepl.ack/reset-ack-port!)
  (when-not (repl/nrepl-dependency? project)
    (main/info "Warning: no nREPL dependency detected.")
    (main/info "Be sure to include org.clojure/tools.nrepl in :dependencies"
               "of your profile."))
  (let [prep-blocker @eval/prep-blocker
        ack-port (:port @repl/ack-server)]
    (-> (bound-fn []
          (binding [eval/*pump-in* false]
            (eval/eval-in-project
             project
             `(let [server# (clojure.tools.nrepl.server/start-server
                             :bind ~(:host cfg) :port ~(:port cfg)
                             :ack-port ~ack-port
                             :handler ~(#'repl/handler-for project))
                    port# (:port server#)
                    repl-port-file# (apply io/file ~(if (:root project)
                                                      [(:root project) ".nrepl-port"]
                                                      [(user/leiningen-home) "repl-port"]))
                    legacy-repl-port# (if (.exists (io/file ~(:target-path project)))
                                        (io/file ~(:target-path project) "repl-port"))]
                (spit (doto repl-port-file# .deleteOnExit) port#)
                (when legacy-repl-port#
                  (spit (doto legacy-repl-port# .deleteOnExit) port#))
                @(promise))
             `(do ~(when-let [init-ns (repl/init-ns project)]
                     `(try (doto '~init-ns require in-ns)
                           (catch Exception e# (println e#) (ns ~init-ns))))
                  ~@(for [n (#'repl/init-requires project)]
                      `(try (require ~n)
                            (catch Throwable t#
                              (println "Error loading" (str ~n ":")
                                       (or (.getMessage t#) (type t#))))))
                  ~(core/gen-main-form
                    'boot
                    (util/find-webapp-root project)
                    handlers
                    mappings
                    port
                    :task :boot)
                  (~'boot/start-server port)))))
        (Thread.)
        (.start))
    (when project @prep-blocker)
    (when headless? @(promise))
    (if-let [repl-port (nrepl.ack/wait-for-ack
                        (get-in project [:repl-options :timeout] 60000))]
      (do (main/info "nREPL server started on port"
                     repl-port "on host" (:host cfg))
          repl-port)
      (main/abort "REPL server launch timed out."))))

(defn boot [project & [task & more :as args]]
  (let [{:keys [port]}
        (if (tasks task)
          {}
          (apply hash-map
                 (mapcat (fn [[k v]] [(keyword (string/replace k #"^:" "")) v])
                         (partition 2 args))))
        port (try (Integer. port) (catch Exception _))
        port (or port (:port (:ring project)) 8080)
        handlers (or (:handler (:ring project)) (:boot project))
        handlers (if (sequential? handlers) handlers [handlers])
        mappings (util/servlet-mappings project)
        project (project/merge-profiles project [:repl])
        austin? (some (comp #{'com.cemerick/austin} first) (:dependencies project))
        project (if austin?
                  (update-in project
                             [:injections]
                             (fnil into [])
                             '[(when (try (require 'cemerick.austin.repls) true
                                          (catch Exception _))
                                 (defn cljs-repl []
                                   (let [repl-env (reset! cemerick.austin.repls/browser-repl-env
                                                          (cemerick.austin/repl-env))]
                                     (cemerick.austin.repls/cljs-repl repl-env))))])
                  project)
        project (add-deps project
                          '[org.clojure/tools.nrepl "0.2.5"]
                          '[ring/ring-core "1.3.1"]
                          '[ring/ring-servlet "1.3.1"]
                          '[org.eclipse.jetty/jetty-webapp "8.1.16.v20140903"])]
    (case task
      "exit" nil
      "test" (let [cfg {:host (repl/repl-host project)
                        :port (repl/repl-port project)}]
               (server project cfg false nil mappings handlers)
               (test/test project))
      (let [cfg {:host (repl/repl-host project)
                 :port (repl/repl-port project)}]
        (->> (server project cfg false port mappings handlers)
             (repl/client project))))))
