(ns syosetsuka.store.kotoba
  "kotoba-datomic backend over XRPC — JVM-only, transport-injected.

  Mirrors the sibling mangaka.store.kotoba client shape (ADR-2606071600):

    - endpoint  KOTOBA_XRPC_URL / KOTOBA_URL (default in-cluster kotoba svc)
    - nsid      KOTOBA_DATOMIC_NSID (default ai.gftd.apps.kotobase.datomic,
                repos.edn :kotoba :xrpc)
    - graph     KOTOBA_GRAPH (default kotobase-kg-v1, CLAUDE.md Domain Model)
    - auth      Bearer (KOTOBA_BEARER); the edge BFF is the trust boundary
    - transact  POST {nsid}.transact {graph, tx_edn}
    - q         POST {nsid}.q        {graph, query_edn}   → {rows_edn}
    - pull      POST {nsid}.pull     {graph, entity, pattern_edn} → {entity_edn}

  The transport (`post-fn`) is injected, so a fake kotoba server can drive
  the full api in tests; `api` returns the minimal store surface that
  syosetsuka.graphs.verify-store (the §7 sovereign gate) exercises."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            #?@(:clj [[org.httpkit.client :as http]
                      [jsonista.core :as j]])))

(defn- env [k] #?(:clj (System/getenv k) :cljs nil))

(defn configured?
  "True when a kotoba endpoint is configured in the environment."
  []
  (boolean (or (env "KOTOBA_XRPC_URL") (env "KOTOBA_URL"))))

(defn- nsid []
  (or (env "KOTOBA_DATOMIC_NSID") "ai.gftd.apps.kotobase.datomic"))

(defn- endpoint []
  (str/replace (or (env "KOTOBA_XRPC_URL")
                   (env "KOTOBA_URL")
                   "http://kotoba.kotoba.svc.cluster.local:8080")
               #"/+$" ""))

#?(:clj
   (defn- headers []
     (let [tok (env "KOTOBA_BEARER")]
       (cond-> {"content-type" "application/json"}
         tok (assoc "authorization" (str "Bearer " tok))))))

#?(:clj
   (defn http-post!
     "Default transport: POST {endpoint}/xrpc/{nsid}.{method} with a JSON
     body, returns the decoded JSON response (keyword keys). Throws on
     transport or non-2xx errors."
     [method body]
     (let [{:keys [status body error]}
           @(http/post (str (endpoint) "/xrpc/" (nsid) "." method)
                       {:headers (headers) :body (j/write-value-as-string body)})]
       (when error
         (throw (ex-info "kotoba XRPC transport error" {:method method :error error})))
       (when-not (<= 200 status 299)
         (throw (ex-info "kotoba XRPC error" {:method method :status status :body body})))
       (j/read-value body j/keyword-keys-object-mapper))))

(defn api
  "The minimal store surface the verify-store gate exercises:
  {:label :transact! :pull :q-values}. `post-fn` defaults to `http-post!`
  (JVM); inject a fake for tests."
  ([] (api {}))
  ([{:keys [post-fn graph]}]
   (let [post-fn (or post-fn #?(:clj http-post! :cljs nil))
         graph (or graph (env "KOTOBA_GRAPH") "kotobase-kg-v1")]
     (when-not post-fn
       (throw (ex-info "kotoba api needs a post-fn on this platform" {})))
     {:label "kotoba"
      :transact!
      (fn [ops]
        (post-fn "transact" {:graph graph :tx_edn (pr-str (vec ops))}))
      :pull
      (fn [eid]
        (let [{:keys [entity_edn entity]}
              (post-fn "pull" {:graph graph
                               :entity (pr-str eid)
                               :pattern_edn (pr-str '[*])})]
          (some-> (or entity_edn entity)
                  (#(if (string? %) (edn/read-string %) %)))))
      :q-values
      (fn [eid attr]
        (let [{:keys [rows_edn rows]}
              (post-fn "q" {:graph graph
                            :query_edn (pr-str [:find '?v :where [eid attr '?v]])})]
          (cond
            rows_edn (mapv (comp edn/read-string first) rows_edn)
            rows (mapv first rows)
            :else [])))})))
