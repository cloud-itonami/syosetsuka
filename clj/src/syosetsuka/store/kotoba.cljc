(ns syosetsuka.store.kotoba
  "kotobase.net tenant Datom plane client — the kotoba STORAGE substrate
  (ADR-2607022300: kotoba=storage / murakumo=compute / aozora=publish;
  k8s の lg-* pod は prune 済み).

  Auth is CACAO self-issued (CLAUDE.md Actors section): the actor loads or
  creates its own Ed25519 identity (.syosetsuka/identity.edn, gitignored)
  and mints per-op CACAOs via syosetsuka.cacao — datom:transact+tx:create
  for writes, datom:read for reads. Its writable namespace is
  `kotobase/db/<actor-did>/<db-name>`; the wire goes through
  langchain.kotoba-db (kotoba-lang/langchain), which encodes the live edge
  empirics: writes send `db_name` (edge derives + pins the canonical graph
  from the CACAO's DID), reads send the precomputed canonical graph CID.

  Env:
    KOTOBA_URL           endpoint (default https://kotobase.net)
    KOTOBA_DB_NAME       tenant db (default syosetsuka-verify)
    KOTOBA_OPERATOR_DID  CACAO aud (default did:web:kotobase.net)
    KOTOBA_IDENTITY      identity path (default .syosetsuka/identity.edn)

  The transport (:http-fn) is injected so a fake edge can drive the full
  client in tests; `api` returns the verify-store surface
  {:label :transact! :entity :values}."
  (:require [langchain.kotoba-db :as kdb]
            #?@(:clj [[jsonista.core :as j]
                      [org.httpkit.client :as http]
                      [syosetsuka.cacao :as cacao]])))

(defn- env [k] #?(:clj (System/getenv k) :cljs nil))

(defn config []
  {:url (or (env "KOTOBA_URL") "https://kotobase.net")
   :db-name (or (env "KOTOBA_DB_NAME") "syosetsuka-verify")
   :operator-did (or (env "KOTOBA_OPERATOR_DID") "did:web:kotobase.net")
   :identity-path (or (env "KOTOBA_IDENTITY") ".syosetsuka/identity.edn")})

#?(:clj
   (defn http-fn
     "Default transport (http-kit)."
     [{:keys [url method headers body]}]
     (let [{:keys [status body error]}
           @(http/request {:url url
                           :method (or method :post)
                           :headers headers
                           :body body})]
       (when error
         (throw (ex-info "kotoba transport error" {:url url :error error})))
       {:status status :body body})))

#?(:clj
   (defn- host-caps [http-fn*]
     {:http-fn (or http-fn* http-fn)
      :json-write j/write-value-as-string
      :json-read #(j/read-value % j/keyword-keys-object-mapper)}))

#?(:clj
   (defn api
     "Build the verify-store api surface against the kotobase.net tenant
     plane. opts (all optional; tests inject :http-fn and :identity):
       :http-fn :identity :url :db-name :operator-did :now :nonce"
     ([] (api {}))
     ([{:keys [http-fn identity url db-name operator-did now nonce]}]
      (let [cfg (config)
            url (or url (:url cfg))
            db-name (or db-name (:db-name cfg))
            operator-did (or operator-did (:operator-did cfg))
            identity (or identity (cacao/load-or-create-identity! (:identity-path cfg)))
            graph (cacao/canonical-graph (:did identity) db-name)
            mint (fn [capability extra]
                   (:cacao-b64 (cacao/mint-cacao
                                (cond-> {:identity identity
                                         :aud operator-did
                                         :capability capability
                                         :extra-capabilities extra
                                         :graph graph}
                                  now (assoc :now now)
                                  nonce (assoc :nonce nonce)))))
            caps (host-caps http-fn)
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
                 rconn)))}))))
