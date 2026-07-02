(ns syosetsuka.graphs.verify-store
  "Durable store verification graph — the kotoba sovereign ゲート (CLAUDE.md
  §7, ADR-2606071600 §7). kotoba には本番ロールバック先例がある
  (shinshi/yukkuri が kotoba→RW/D1) ため、sovereign 宣言の前にこの graph で
  transact→pull/q round-trip と multi-value `:nv/tag` fidelity を kotoba
  storage substrate に対して実測する。実測先は **kotobase.net の tenant
  Datom plane**（ADR-2607022300: kotoba=storage / murakumo=compute /
  aozora=publish。k8s の lg-* pod は prune 済み）で、認証は actor 自身の
  Ed25519 鍵による CACAO 自己発行（syosetsuka.store.kotoba）。

  The probe writes a synthetic author/work/episode using the
  kotoba-lang/shousetsu vocabulary (ADR-2607023000) and verifies:

    :transact          — ops accepted
    :pull-roundtrip    — title/status/author come back intact (entity lookup
                         by unique id attr → pull)
    :tag-fidelity-pull — multi-value :nv/tag survives with full cardinality
    :tag-fidelity-q    — the same set via the query path
    :body-as-blob      — :ep/bodyBlobKey present, :ep/body absent

  The store api is injected ({:label :transact! :entity :values}):
  `mem-api` (default — deterministic, CI-safe, no network) or
  `syosetsuka.store.kotoba/api` (input {:store \"kotoba\"}, JVM — the
  kotobase.net live run)."
  (:require [shousetsu.serialization :as serialization]
            [syosetsuka.store.kotoba :as kotoba]))

;; ───────────────────────── in-memory api ─────────────────────────

(defn mem-api
  "Deterministic in-memory datom store implementing the verify surface.
  Entities are located the same way as on a real store: by unique id attr
  value, never by assuming tempid strings survive as entity ids."
  []
  (let [datoms (atom [])]
    (letfn [(eid-of [id-attr id-value]
              (some (fn [[e a v]] (when (and (= a id-attr) (= v id-value)) e))
                    @datoms))]
      {:label "mem"
       :transact!
       (fn [ops]
         (swap! datoms into (keep (fn [[op e a v]] (when (= :db/add op) [e a v])) ops))
         {:ok true})
       :entity
       (fn [id-attr id-value]
         (when-let [e (eid-of id-attr id-value)]
           (reduce (fn [m [_ a v]]
                     (update m a (fn [cur]
                                   (cond
                                     (nil? cur) v
                                     (vector? cur) (conj cur v)
                                     :else [cur v]))))
                   {}
                   (filterv #(= e (first %)) @datoms))))
       :values
       (fn [id-attr id-value attr]
         (when-let [e (eid-of id-attr id-value)]
           (mapv peek (filterv #(and (= e (first %)) (= attr (second %))) @datoms))))})))

;; ───────────────────────── checks ─────────────────────────

(defn- as-value-set [v]
  (cond (nil? v) #{} (coll? v) (set v) :else #{v}))

(defn run-checks
  "Run the §7 verification against a store api. Returns
  {:ok? bool :store label :checks [...]}."
  [api {:keys [probe-slug tags]
        :or {probe-slug "sovereign-probe" tags ["異世界" "内政" "図書館"]}}]
  (let [author-id (serialization/author-id (str "verify-" probe-slug))
        work-id (serialization/work-id probe-slug)
        episode-id (serialization/episode-id probe-slug 1)
        ops (vec (concat
                  (serialization/author->ops {:author_id author-id
                                              :pen_name "検証作者"
                                              :genre_affinity "検証"})
                  (serialization/work->ops {:work_id work-id
                                            :title "Sovereign Probe"
                                            :author_id author-id
                                            :status "serializing"
                                            :tags tags})
                  (serialization/episode-meta->ops {:episode_id episode-id
                                                    :work_id work-id
                                                    :index 1
                                                    :title "第一話"
                                                    :body_blob_key "probe-blob"
                                                    :char_count 42
                                                    :status "published"})))
        _ ((:transact! api) ops)
        work ((:entity api) :nv/id work-id)
        episode ((:entity api) :ep/id episode-id)
        pull-tags (as-value-set (:nv/tag work))
        q-tags ((:values api) :nv/id work-id :nv/tag)
        checks
        [{:check :transact :ok true :ops (count ops)}
         {:check :pull-roundtrip
          :ok (and (= "Sovereign Probe" (:nv/title work))
                   (= author-id (:nv/author work))
                   (= "serializing" (:nv/status work)))
          :work work}
         {:check :tag-fidelity-pull
          :ok (and (= (set tags) pull-tags)
                   (= (count tags) (count pull-tags)))
          :expected (vec tags) :actual (vec pull-tags)}
         {:check :tag-fidelity-q
          :ok (and (= (set tags) (set q-tags))
                   (= (count tags) (count q-tags)))
          :expected (vec tags) :actual (vec q-tags)}
         {:check :body-as-blob
          :ok (and (= "probe-blob" (:ep/bodyBlobKey episode))
                   (nil? (:ep/body episode)))}]]
    {:ok? (every? :ok checks)
     :store (:label api)
     :checks checks}))

;; ───────────────────────── graph handler ─────────────────────────

(defn- input-value [m & ks] (some #(get m %) ks))

(defn handler
  "Graph handler for ai.gftd.apps.syosetsuka.verifyStore.

  input:
    :store       \"mem\" (default — deterministic, no network) or
                 \"kotoba\" (kotobase.net tenant plane live run; CACAO
                 self-issued from .syosetsuka/identity.edn)
    :probe_slug  unique probe entity slug (durable stores need a fresh one
                 per run; default \"sovereign-probe\")

  Sovereign 宣言の判定材料は {:store \"kotoba\"} での :ok true のみ。mem は
  gate 自体の regression 検知用。"
  [input _thread-id]
  (let [store (or (input-value input :store "store") "mem")
        probe-slug (input-value input :probe_slug :probe-slug "probe_slug")]
    (try
      (let [api (case store
                  "mem" (mem-api)
                  "kotoba" #?(:clj (kotoba/api)
                              :cljs (throw (ex-info "kotoba store run is JVM-only" {})))
                  (throw (ex-info (str "unknown store: " store) {:store store})))
            result (run-checks api (cond-> {}
                                     probe-slug (assoc :probe-slug probe-slug)))]
        (merge {:ok (:ok? result)
                :store (:store result)
                :checks (mapv #(dissoc % :work) (:checks result))
                :sovereign_ready (and (:ok? result) (= "kotoba" (:store result)))}
               (when (= "kotoba" store)
                 {:did (:did api) :graph (:graph api) :db_name (:db-name api)})
               (when (= "mem" store)
                 {:note "mem run — sovereign 判定は {:store \"kotoba\"} での kotobase.net 実測のみ"})))
      (catch #?(:clj Exception :cljs :default) e
        {:ok false :store store :error (str #?(:clj (.getMessage ^Exception e)
                                               :cljs e))}))))
