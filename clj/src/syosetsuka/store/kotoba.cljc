(ns syosetsuka.store.kotoba
  "kotobase.net tenant Datom plane client — the kotoba STORAGE substrate
  (ADR-2607022300: kotoba=storage / murakumo=compute / aozora=publish;
  k8s の lg-* pod は prune 済み). **Portable .cljc**: all logic is pure and
  host capabilities are injected (ADR-0001) — :clj ships working defaults
  (http-kit transport, jsonista, JDK identity), other hosts (cljs / SCI /
  WASM) inject {:http-fn :json-write :json-read :identity} via `api` opts or
  `set-default-opts!`.

  Auth is CACAO self-issued (CLAUDE.md Actors section): the actor loads or
  creates its own Ed25519 identity (.syosetsuka/identity.edn on the JVM,
  gitignored; other hosts pass an identity built with
  syosetsuka.cacao/identity-from-signer) and mints per-op CACAOs —
  datom:transact+tx:create for writes, datom:read for reads. Its writable
  namespace is `kotobase/db/<actor-did>/<db-name>`; the wire goes through
  langchain.kotoba-db (kotoba-lang/langchain), which encodes the live edge
  empirics: writes send `db_name` (edge derives + pins the canonical graph
  from the CACAO's DID), reads send the precomputed canonical graph CID.

  Env (JVM):
    KOTOBA_URL           endpoint (default https://kotobase.net)
    KOTOBA_DB_NAME       tenant db (default syosetsuka-verify)
    KOTOBA_OPERATOR_DID  CACAO aud (default did:web:kotobase.net)
    KOTOBA_IDENTITY      identity path (default .syosetsuka/identity.edn)

  `api` returns the verify-store surface {:label :transact! :entity :values}."
  (:require [langchain.kotoba-db :as kdb]
            [syosetsuka.cacao :as cacao]
            #?@(:clj [[jsonista.core :as j]
                      [org.httpkit.client :as http]])))

(defn- env [k]
  #?(:clj (System/getenv k)
     :cljs (when (exists? js/process) (aget (.-env js/process) k))))

(defn config []
  {:url (or (env "KOTOBA_URL") "https://kotobase.net")
   :db-name (or (env "KOTOBA_DB_NAME") "syosetsuka-verify")
   :operator-did (or (env "KOTOBA_OPERATOR_DID") "did:web:kotobase.net")
   :identity-path (or (env "KOTOBA_IDENTITY") ".syosetsuka/identity.edn")})

#?(:clj
   (defn http-fn
     "Default JVM transport (http-kit)."
     [{:keys [url method headers body]}]
     (let [{:keys [status body error]}
           @(http/request {:url url
                           :method (or method :post)
                           :headers headers
                           :body body})]
       (when error
         (throw (ex-info "kotoba transport error" {:url url :error error})))
       {:status status :body body})))

(defonce ^{:doc "Host-injected default `api` opts (cljs/SCI/WASM hosts set
  {:http-fn :json-write :json-read :identity} once at bootstrap; the JVM
  needs nothing here)."}
  default-opts
  (atom {}))

(defn set-default-opts! [opts] (reset! default-opts opts))

(defn- host-caps [{:keys [http-fn* json-write json-read]}]
  {:http-fn (or http-fn*
                #?(:clj http-fn :cljs nil)
                (throw (ex-info "no :http-fn — inject one (set-default-opts! or api opts)" {})))
   :json-write (or json-write
                   #?(:clj j/write-value-as-string
                      :cljs #(js/JSON.stringify (clj->js %))))
   :json-read (or json-read
                  #?(:clj #(j/read-value % j/keyword-keys-object-mapper)
                     :cljs #(js->clj (js/JSON.parse %) :keywordize-keys true)))})

(defn- resolve-identity [identity identity-path]
  (or identity
      #?(:clj (cacao/load-or-create-identity! identity-path)
         :cljs (throw (ex-info "no :identity — build one with syosetsuka.cacao/identity-from-signer and inject it"
                               {})))))

(defn api
  "Build the verify-store api surface against the kotobase.net tenant plane.
  opts (all optional on the JVM; other hosts must inject :http-fn and
  :identity — either here or via set-default-opts!):
    :http-fn :json-write :json-read :identity
    :url :db-name :operator-did :now-iso :nonce"
  ([] (api {}))
  ([opts]
   (let [{:keys [http-fn identity url db-name operator-did now-iso nonce
                 json-write json-read]}
         (merge @default-opts opts)
         cfg (config)
         url (or url (:url cfg))
         db-name (or db-name (:db-name cfg))
         operator-did (or operator-did (:operator-did cfg))
         identity (resolve-identity identity (:identity-path cfg))
         graph (cacao/canonical-graph (:did identity) db-name)
         mint (fn [capability extra]
                (:cacao-b64 (cacao/mint-cacao
                             (cond-> {:identity identity
                                      :aud operator-did
                                      :capability capability
                                      :extra-capabilities extra
                                      :graph graph}
                               now-iso (assoc :now-iso now-iso)
                               nonce (assoc :nonce nonce)))))
         caps (host-caps {:http-fn* http-fn
                          :json-write json-write
                          :json-read json-read})
         kapi (kdb/kotoba-api caps)
         conn (fn [cacao-b64]
                (kdb/kotoba-conn* url db-name {:graph graph
                                               :cacao cacao-b64
                                               :did (:did identity)}))
         wconn (conn (mint "datom:transact" ["tx:create"]))
         rconn (conn (mint "datom:read" []))]
     {:label "kotoba"
      :did (:did identity)
      :graph graph
      :db-name db-name
      :transact!
      (fn [ops] ((:transact! kapi) wconn ops))
      :entity
      (fn [id-attr id-value]
        (when-let [eid ((:q kapi) [:find '?e '. :where ['?e id-attr id-value]]
                        rconn)]
          ((:pull kapi) rconn '[*] eid)))
      :values
      (fn [id-attr id-value attr]
        (vec ((:q kapi) [:find '[?v ...]
                         :where ['?e id-attr id-value] ['?e attr '?v]]
              rconn)))})))
