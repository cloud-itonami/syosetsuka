(ns syosetsuka.core-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [syosetsuka.edn :as se]
            [syosetsuka.graphs.registry :as reg]))

(deftest registry-and-manifest
  (let [r (reg/build)
        manifest (edn/read-string (slurp "langgraph.edn"))]
    (is (= 13 (count r)))
    (is (= (set (keys r)) (set (keys (:graphs manifest)))))
    (doseq [id ["health" "list_works" "get_work" "list_episodes" "get_episode"
                "list_authors" "get_author" "generate_author" "compose_work"
                "generate_episode" "continue_serialization" "produce" "verify_store"]]
      (is (reg/resolve-entry r id)))))

(deftest edn-helpers
  (is (= "[:db/add \"author:akagi-rin\" :au/penName \"赤城凛\"]"
         (se/encode (se/tx-add "author:akagi-rin" "au/penName" "赤城凛"))))
  (let [ops (mapv #(se/tx-add (str "work:w" %) "nv/title" (apply str (repeat 100 "x"))) (range 20000))
        chunks (se/chunk-tx-data ops 900000)]
    (is (> (count chunks) 1))
    (is (every? #(<= (count (.getBytes % "UTF-8")) 900000) chunks)))
  (is (= "work:reincarnated-librarian" (se/work-id "reincarnated-librarian")))
  (is (= "episode:reincarnated-librarian:1" (se/episode-id "reincarnated-librarian" 1)))
  (is (= "author:akagi-rin" (se/author-id "akagi-rin")))
  (is (.startsWith (se/slug "赤城凛") "x"))
  (is (= "reincarnated-librarian" (se/slug "Reincarnated Librarian"))))

(deftest datom-shapes
  (let [ops (se/work->ops {:work_id "work:test" :title "T" :author_id "author:a"
                           :status "serializing" :tags ["異世界" "内政"]})
        flat (map se/encode ops)]
    (is (some #(clojure.string/includes? % ":nv/type \"Work\"") flat))
    (is (some #(clojure.string/includes? % ":nv/id \"work:test\"") flat))
    (is (= 2 (count (filter #(clojure.string/includes? % ":nv/tag") flat)))))
  (let [flat (apply str (map se/encode (se/episode-meta->ops
                                         {:episode_id "episode:test:1" :work_id "work:test" :index 1
                                          :title "第一話" :body_blob_key "deadbeef"
                                          :char_count 3000 :status "published"})))]
    (is (clojure.string/includes? flat ":ep/bodyBlobKey"))
    (is (not (clojure.string/includes? flat ":ep/body ")))))

(deftest graph-scaffold-behavior
  (let [r (reg/build)
        author ((:handler (reg/resolve-entry r "generate_author")) {:theme "異世界内政"} nil)
        work ((:handler (reg/resolve-entry r "compose_work")) {:author_id (:author_id author) :theme "図書館国家"} nil)
        episode ((:handler (reg/resolve-entry r "generate_episode")) {:work_id (:work_id work)} nil)
        produce ((:handler (reg/resolve-entry r "produce")) {:theme "図書館国家" :episode_count 2} nil)]
    (is (= "created" (:status author)))
    (is (= "serializing" (:status work)))
    (is (= "published" (:status episode)))
    (is (= 2 (:produced_episodes produce)))
    (is (= "published" (:final_status produce)))))
