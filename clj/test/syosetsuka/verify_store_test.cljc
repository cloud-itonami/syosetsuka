(ns syosetsuka.verify-store-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [syosetsuka.cacao :as cacao]
            [syosetsuka.graphs.registry :as reg]
            [syosetsuka.graphs.verify-store :as verify-store]
            [syosetsuka.store.kotoba :as kotoba]))

(deftest mem-store-passes-the-gate
  (let [result (verify-store/run-checks (verify-store/mem-api) {})]
    (is (:ok? result))
    (is (= "mem" (:store result)))
    (is (= [:transact :pull-roundtrip :tag-fidelity-pull :tag-fidelity-q :body-as-blob]
           (mapv :check (:checks result))))
    (is (every? :ok (:checks result)))))

(deftest gate-detects-multi-value-tag-loss
  ;; the historical rollback cause (shinshi/yukkuri): multi-value datoms
  ;; collapsing. Simulate a store that keeps only the first :nv/tag.
  (let [inner (verify-store/mem-api)
        lossy (assoc inner :transact!
                     (fn [ops]
                       (let [seen (atom false)
                             keep? (fn [[_ _ a _]]
                                     (if (= :nv/tag a)
                                       (when-not @seen (reset! seen true) true)
                                       true))]
                         ((:transact! inner) (filterv keep? ops)))))
        result (verify-store/run-checks lossy {})]
    (is (not (:ok? result)))
    (let [failing (set (map :check (remove :ok (:checks result))))]
      (is (contains? failing :tag-fidelity-pull))
      (is (contains? failing :tag-fidelity-q)))))

(deftest gate-detects-broken-roundtrip
  (let [inner (verify-store/mem-api)
        broken (assoc inner :entity (fn [_ _] nil))
        result (verify-store/run-checks broken {})]
    (is (not (:ok? result)))
    (is (some #(= :pull-roundtrip (:check %)) (remove :ok (:checks result))))))

(deftest handler-defaults-to-deterministic-mem
  (let [h (:handler (reg/resolve-entry (reg/build) "verify_store"))
        result (h {} nil)]
    (is (true? (:ok result)))
    (is (= "mem" (:store result)))
    (is (false? (:sovereign_ready result))
        "mem run must never claim sovereign readiness")
    (is (:note result))))

;; ───────── cacao / cid primitives ─────────

(deftest canonical-graph-matches-the-known-kg-cid
  ;; langchain.kotoba-db/KG-GRAPH-CID documents the CID of "kotobase-kg-v1";
  ;; our JVM port must reproduce it byte-exactly.
  (is (= "bafyreiglzym7s24os6nki3aknbg2d6dncy5dadwjftavcnuarkdswz6afa"
         (cacao/graph-cid-from-name "kotobase-kg-v1"))))

(deftest identity-and-cacao-shape
  (let [id (cacao/generate-identity)]
    (is (str/starts-with? (:did id) "did:key:z"))
    (testing "persist/reload round-trip"
      (let [reloaded (cacao/load-identity (select-keys id [:private-b64 :public-b64]))]
        (is (= (:did id) (:did reloaded)))))
    (testing "mint produces a base64 CBOR envelope with the caip122 dialect"
      (let [graph (cacao/canonical-graph (:did id) "syosetsuka-verify")
            {:keys [cacao-b64 did]} (cacao/mint-cacao {:identity id
                                                       :aud "did:web:kotobase.net"
                                                       :capability "datom:transact"
                                                       :extra-capabilities ["tx:create"]
                                                       :graph graph
                                                       :nonce "fixednonce123456"
                                                       :now-iso "2026-07-02T00:00:00Z"})
            raw (String. (.decode (java.util.Base64/getDecoder) ^String cacao-b64) "ISO-8859-1")]
        (is (= (:did id) did))
        (is (str/includes? raw "caip122"))
        (is (str/includes? raw "kotoba://can/datom:transact"))
        (is (str/includes? raw "kotoba://can/tx:create"))
        (is (str/includes? raw (str "kotoba://graph/" graph)))
        (is (str/includes? raw "kotobase.net"))))))

;; ───────── portability: minting needs NO host crypto ─────────

(deftest pure-mint-without-host-crypto
  ;; identity-from-signer + a fake signer: the whole mint path (SIWE,
  ;; DAG-CBOR, base58/base32, canonical-graph CID via sha256d) is pure cljc —
  ;; the only host capability is the injected Ed25519 :sign-fn.
  (let [pub-raw (vec (repeat 32 7))
        captured (atom nil)
        id (cacao/identity-from-signer
            pub-raw
            (fn [msg-bytes] (reset! captured msg-bytes) (vec (range 64))))
        graph (cacao/canonical-graph (:did id) "syosetsuka-verify")
        {:keys [cacao-b64 did]} (cacao/mint-cacao {:identity id
                                                   :aud "did:web:kotobase.net"
                                                   :capability "datom:read"
                                                   :graph graph
                                                   :nonce "fixednonce123456"
                                                   :now-iso "2026-07-02T00:00:00Z"})]
    (is (str/starts-with? did "did:key:z"))
    (is (string? cacao-b64))
    (testing "the signer received the SIWE message bytes"
      (is (vector? @captured))
      (is (str/starts-with?
           (apply str (map char @captured))
           "kotobase.net wants you to sign in with your Ethereum account:")))))

;; ───────── kotoba client wire (fake edge) ─────────

(defn- fake-edge
  "A fake kotobase edge: records every request and answers the §7 probe.
  Returns [requests-atom http-fn]."
  []
  (let [requests (atom [])
        entity-edn {"17" (pr-str {:nv/id "work:sovereign-probe"
                                  :nv/title "Sovereign Probe"
                                  :nv/author "author:verify-sovereign-probe"
                                  :nv/status "serializing"
                                  :nv/tag ["異世界" "内政" "図書館"]})
                    "42" (pr-str {:ep/id "episode:sovereign-probe:1"
                                  :ep/bodyBlobKey "probe-blob"
                                  :ep/charCount 42})}]
    [requests
     (fn [{:keys [url body] :as req}]
       (swap! requests conj req)
       (let [parsed (json/read-value body json/keyword-keys-object-mapper)
             reply (fn [m] {:status 200 :body (json/write-value-as-string m)})]
         (cond
           (str/ends-with? url ".transact") (reply {:ok true})
           (str/ends-with? url ".pull") (reply {:entity_edn (get entity-edn (:entity parsed))})
           (str/ends-with? url ".q")
           (let [q (:query_edn parsed)]
             (cond
               (str/includes? q ":nv/tag")
               (reply {:rows_edn [[(pr-str "異世界")] [(pr-str "内政")] [(pr-str "図書館")]]})
               (str/includes? q ":ep/id")
               (reply {:rows_edn [["42"]]})
               :else
               (reply {:rows_edn [["17"]]})))
           :else (reply {}))))]))

(deftest kotoba-client-wire-and-gate
  (let [[requests http-fn] (fake-edge)
        id (cacao/generate-identity)
        api (kotoba/api {:http-fn http-fn
                         :identity id
                         :url "https://kotobase.example"
                         :db-name "syosetsuka-verify"})
        result (verify-store/run-checks api {})]
    (testing "the gate passes against the fake edge"
      (is (:ok? result))
      (is (= "kotoba" (:store result))))
    (testing "writes carry db_name + CACAO (live edge tenant-write contract)"
      (let [tx (first (filter #(str/ends-with? (:url %) ".transact") @requests))
            body (json/read-value (:body tx) json/keyword-keys-object-mapper)]
        (is (= "syosetsuka-verify" (:db_name body)))
        (is (string? (:cacao_b64 body)))
        (is (str/starts-with? (get-in tx [:headers "authorization"]) "CACAO "))
        (is (= (:did id) (get-in tx [:headers "x-kotoba-did"])))))
    (testing "reads carry the precomputed canonical graph CID"
      (let [q (first (filter #(str/ends-with? (:url %) ".q") @requests))
            body (json/read-value (:body q) json/keyword-keys-object-mapper)]
        (is (= (cacao/canonical-graph (:did id) "syosetsuka-verify")
               (:graph body)))))))
