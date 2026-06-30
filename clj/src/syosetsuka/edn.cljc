(ns syosetsuka.edn
  (:require [clojure.string :as str]))

(defn tx-add [e attr v]
  [:db/add e (keyword attr) v])

(defn encode [x]
  (pr-str x))

(defn- byte-count [s]
  #?(:clj (count (.getBytes (str s) "UTF-8"))
     :cljs (count (js/TextEncoder.encode (js/TextEncoder.) (str s)))))

(defn chunk-tx-data
  ([ops] (chunk-tx-data ops 900000))
  ([ops max-bytes]
   (loop [xs ops chunks [] cur [] cur-bytes 2]
     (if (empty? xs)
       (cond-> chunks (seq cur) (conj (encode (vec cur))))
       (let [op (first xs)
             op-bytes (byte-count (encode op))
             sep-bytes (if (seq cur) 1 0)
             n (+ cur-bytes sep-bytes op-bytes)]
         (if (and (seq cur) (> n max-bytes))
           (recur xs (conj chunks (encode (vec cur))) [] 2)
           (recur (rest xs) chunks (conj cur op) n)))))))

(defn slug [s]
  (let [base (-> (str s)
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-|-$)" ""))]
    (if (seq base)
      base
      (str "x" (Long/toUnsignedString (hash s) 16)))))

(defn work-id [slug] (str "work:" slug))
(defn episode-id [work-slug idx] (str "episode:" work-slug ":" idx))
(defn author-id [slug] (str "author:" slug))

(defn work->ops [{:keys [work_id title author_id status tags]}]
  (vec (concat
        [(tx-add work_id "nv/type" "Work")
         (tx-add work_id "nv/id" work_id)
         (tx-add work_id "nv/title" title)
         (tx-add work_id "nv/author" author_id)
         (tx-add work_id "nv/status" status)]
        (map #(tx-add work_id "nv/tag" %) (or tags [])))))

(defn episode-meta->ops [{:keys [episode_id work_id index title body_blob_key char_count status]}]
  [(tx-add episode_id "nv/type" "Episode")
   (tx-add episode_id "ep/id" episode_id)
   (tx-add episode_id "ep/work" work_id)
   (tx-add episode_id "ep/index" index)
   (tx-add episode_id "ep/title" title)
   (tx-add episode_id "ep/bodyBlobKey" body_blob_key)
   (tx-add episode_id "ep/charCount" char_count)
   (tx-add episode_id "ep/status" status)])
