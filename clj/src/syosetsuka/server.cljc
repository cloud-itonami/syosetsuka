(ns syosetsuka.server
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jsonista.core :as j]
            [org.httpkit.server :as hk]
            [reitit.ring :as ring]
            [syosetsuka.graphs.registry :as reg])
  (:gen-class))

(defonce ^:private server (atom nil))
(def ^:private registry (delay (reg/build)))
(def ^:private cors-headers {"access-control-allow-origin" "*"
                             "access-control-allow-methods" "GET, POST, OPTIONS"
                             "access-control-allow-headers" "content-type, accept, x-api-key"})

(defn- read-body [{:keys [headers body]}]
  (when body
    (let [s (slurp body) ct (get headers "content-type" "")]
      (when (seq s)
        (if (str/includes? ct "edn")
          (edn/read-string s)
          (j/read-value s j/keyword-keys-object-mapper))))))

(defn- wants-json? [req]
  (let [accept (str (get-in req [:headers "accept"])) ct (str (get-in req [:headers "content-type"]))]
    (and (not (str/includes? accept "edn")) (or (str/includes? accept "json") (str/includes? ct "json")))))

(defn- data-resp
  ([req data] (data-resp req 200 data))
  ([req status data]
   (if (wants-json? req)
     {:status status :headers {"content-type" "application/json"} :body (j/write-value-as-string data)}
     {:status status :headers {"content-type" "application/edn"} :body (pr-str data)})))

(defn- with-body [req f]
  (try (f (or (read-body req) {}))
       (catch Exception e (data-resp req 400 {:error (.getMessage e)}))))

(defn- api-key-ok? [req]
  (let [required (System/getenv "LG_API_KEY")]
    (or (str/blank? required) (= required (get-in req [:headers "x-api-key"])))))

(defn- invoke! [ident input thread-id]
  (if-let [h (:handler (reg/resolve-entry @registry ident))]
    (try {:ok true :result (h input thread-id)}
         (catch Throwable e {:ok false :status 500 :error (.getMessage e)}))
    {:ok false :status 404 :error (str "unknown graph/nsid: " ident)}))

(defn- ok-handler [req]
  (data-resp req {:ok true :graphs reg/graph-names :version "0.1.0" :impl "clj"}))

(defn- health-handler [req]
  (data-resp req {:ok true :checkpointer false :impl "clj"}))

(defn- runs-handler [req]
  (if-not (api-key-ok? req)
    (data-resp req 401 {:error "invalid x-api-key"})
    (with-body req
      (fn [{:keys [assistant_id nsid input config]}]
        (let [{:keys [ok result status error]} (invoke! (or nsid assistant_id) (or input {}) (get-in config [:configurable :thread_id]))]
          (if ok
            (data-resp req {:ok true :result result :assistantId assistant_id :latencyMs 0})
            (data-resp req (or status 404) {:ok false :error error :assistantId assistant_id :latencyMs 0})))))))

(defn- xrpc-handler [req]
  (with-body req
    (fn [input]
      (let [nsid (get-in req [:path-params :nsid])
            entry (reg/resolve-entry @registry nsid)
            {:keys [ok result status error]} (invoke! nsid input (:thread-id input))]
        (if ok
          (data-resp req (assoc result :assistantId (name (:assistant entry)) :latencyMs 0))
          (data-resp req (or status 404) {:error error}))))))

(defn- wrap-cors [handler]
  (fn [req]
    (if (= :options (:request-method req))
      {:status 204 :headers cors-headers}
      (update (handler req) :headers merge cors-headers))))

(def app
  (wrap-cors
   (ring/ring-handler
    (ring/router
     [["/" {:get ok-handler}]
      ["/ok" {:get ok-handler}]
      ["/health" {:get health-handler}]
      ["/runs" {:post runs-handler}]
      ["/xrpc/:nsid" {:post xrpc-handler}]])
    (ring/create-default-handler
     {:not-found (constantly {:status 404 :headers {"content-type" "application/json"}
                              :body "{\"error\":\"not found\"}"})}))))

(defn start!
  ([] (start! (Integer/parseInt (or (System/getenv "PORT") "8000"))))
  ([port]
   (reset! server (hk/run-server app {:port port :legacy-return-value? false}))
   (println (str "syosetsuka.gftd.ai (clj) listening on :" port))
   @server))

(defn stop! []
  (when-let [s @server] (hk/server-stop! s) (reset! server nil)))

(defn -main [& _] (start!) @(promise))
