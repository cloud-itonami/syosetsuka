(ns syosetsuka.graphs.registry
  (:require [clojure.string :as str]
            [syosetsuka.edn :as se]
            [syosetsuka.graphs.verify-store :as verify-store]))

(def graph-names
  ["health" "list_works" "get_work" "list_episodes" "get_episode"
   "list_authors" "get_author" "generate_author" "compose_work"
   "generate_episode" "continue_serialization" "produce" "verify_store"])

(defn- input-value [m & ks] (some #(get m %) ks))
(defn- req [m ks msg] (if (some #(seq (str (get m %))) ks) nil {:error msg}))

(defn- health [_ _]
  {:ok true :store_ok false :latency_ms 0 :impl "clj"
   :note "CLJ syosetsuka scaffold does not ping kotoba/D1/B2."})

(defn- list-works [input _]
  {:works [] :total 0
   :offset (long (or (input-value input :offset "offset") 0))
   :limit (long (or (input-value input :limit "limit") 50))})

(defn- get-work [input _]
  (or (req input [:work_id :workId "work_id" "workId"] "workId is required")
      {:error "not found"}))

(defn- list-episodes [input _]
  (or (req input [:work_id :workId "work_id" "workId"] "workId is required")
      {:episodes [] :total 0
       :offset (long (or (input-value input :offset "offset") 0))
       :limit (long (or (input-value input :limit "limit") 50))}))

(defn- get-episode [input _]
  (or (req input [:episode_id :episodeId "episode_id" "episodeId"] "episodeId is required")
      {:error "not found"}))

(defn- list-authors [input _]
  {:authors [] :total 0
   :offset (long (or (input-value input :offset "offset") 0))
   :limit (long (or (input-value input :limit "limit") 50))})

(defn- get-author [input _]
  (or (req input [:author_id :authorId "author_id" "authorId"] "authorId is required")
      {:error "not found"}))

(defn- generate-author [input _]
  (let [theme (str/trim (str (or (input-value input :theme "theme") "")))]
    (if-not (seq theme)
      {:error "theme is required"}
      (let [slug (se/slug (or (input-value input :seed "seed") theme))
            aid (se/author-id slug)]
        {:author_id aid :authorId aid :status "created"
         :author {:author_id aid
                  :penName (str "AI小説家 " slug)
                  :genreAffinity (or (input-value input :genre_affinity :genreAffinity "genre_affinity" "genreAffinity") "自由")
                  :voice "deterministic-clj"}}))))

(defn- compose-work [input _]
  (let [author-id (or (input-value input :author_id :authorId "author_id" "authorId") "")
        theme (str/trim (str (or (input-value input :theme "theme") "")))]
    (cond
      (not (seq author-id)) {:error "authorId and theme are required"}
      (not (seq theme)) {:error "authorId and theme are required"}
      :else
      (let [slug (se/slug theme)
            wid (se/work-id slug)]
        {:work_id wid :workId wid :status "serializing"
         :work {:work_id wid :author_id author-id :title theme :status "serializing"
                :genre (or (input-value input :genre "genre") "自由") :tags []}}))))

(defn- generate-episode [input _]
  (let [work-id (or (input-value input :work_id :workId "work_id" "workId") "")]
    (if-not (seq work-id)
      {:error "workId is required"}
      (let [work-slug (last (str/split work-id #":"))
            idx (long (or (input-value input :index "index") 1))
            eid (se/episode-id work-slug idx)
            body "CLJ scaffold episode body. External LLM/B2 persistence is intentionally disabled."]
        {:episode_id eid :episodeId eid :index idx :char_count (count body)
         :charCount (count body) :status "published" :body body}))))

(defn- continue-serialization [input _]
  (let [work-id (or (input-value input :work_id :workId "work_id" "workId") "")
        n (max 1 (min (long (or (input-value input :count "count") 1)) 50))]
    (if-not (seq work-id)
      {:error "workId is required"}
      (let [work-slug (last (str/split work-id #":"))
            last-id (se/episode-id work-slug n)]
        {:produced n :last_episode_id last-id :lastEpisodeId last-id :stage_errors {}}))))

(defn- produce [input thread-id]
  (let [theme (str/trim (str (or (input-value input :theme "theme") "")))]
    (if-not (seq theme)
      {:error "theme is required" :final_status "failed"}
      (let [author (generate-author input thread-id)
            work (compose-work (assoc input :author_id (:author_id author)) thread-id)
            eps (continue-serialization {:work_id (:work_id work)
                                         :count (or (input-value input :episode_count :episodeCount "episode_count" "episodeCount") 3)}
                                        thread-id)]
        {:work_id (:work_id work)
         :workId (:work_id work)
         :produced_episodes (:produced eps)
         :producedEpisodes (:produced eps)
         :final_status "published"
         :finalStatus "published"
         :stage_errors {}}))))

(defn build []
  {"ai.gftd.apps.syosetsuka.health" {:assistant :health :handler health}
   "ai.gftd.apps.syosetsuka.listWorks" {:assistant :list_works :handler list-works}
   "ai.gftd.apps.syosetsuka.getWork" {:assistant :get_work :handler get-work}
   "ai.gftd.apps.syosetsuka.listEpisodes" {:assistant :list_episodes :handler list-episodes}
   "ai.gftd.apps.syosetsuka.getEpisode" {:assistant :get_episode :handler get-episode}
   "ai.gftd.apps.syosetsuka.listAuthors" {:assistant :list_authors :handler list-authors}
   "ai.gftd.apps.syosetsuka.getAuthor" {:assistant :get_author :handler get-author}
   "ai.gftd.apps.syosetsuka.generateAuthor" {:assistant :generate_author :handler generate-author}
   "ai.gftd.apps.syosetsuka.composeWork" {:assistant :compose_work :handler compose-work}
   "ai.gftd.apps.syosetsuka.generateEpisode" {:assistant :generate_episode :handler generate-episode}
   "ai.gftd.apps.syosetsuka.continueSerialization" {:assistant :continue_serialization :handler continue-serialization}
   "ai.gftd.apps.syosetsuka.produce" {:assistant :produce :handler produce}
   ;; §7 kotoba sovereign gate (durable store verification, ADR-2606071600 §7)
   "ai.gftd.apps.syosetsuka.verifyStore" {:assistant :verify_store
                                          :handler verify-store/handler}})

(defn nsids [registry] (vec (sort (keys registry))))

(defn resolve-entry [registry ident]
  (or (get registry ident)
      (some (fn [[_ entry]] (when (= (name (:assistant entry)) ident) entry)) registry)))
