(ns syosetsuka.verify-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [syosetsuka.graphs.registry :as reg]
            [syosetsuka.graphs.verify-store :as verify-store]))

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
        broken (assoc inner :pull (fn [_eid] nil))
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

(deftest handler-refuses-unconfigured-kotoba
  (let [h (:handler (reg/resolve-entry (reg/build) "verify_store"))
        result (h {:store "kotoba"} nil)]
    ;; CI has no KOTOBA_XRPC_URL/KOTOBA_URL: the gate must fail closed,
    ;; not silently pass.
    (testing "fails closed without a configured endpoint"
      (is (false? (:ok result)))
      (is (:error result)))))
