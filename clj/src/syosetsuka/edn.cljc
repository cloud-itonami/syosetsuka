(ns syosetsuka.edn
  "Facade over kotoba-lang/shousetsu (ADR-2607023000) — the work-agnostic
  serialized-fiction vocabulary (entity ids, slug, datom tx helpers,
  record→ops builders) lives in shousetsu.serialization; this ns re-exposes
  it under the original names so graph/server code is unchanged."
  (:require [shousetsu.serialization :as serialization]))

(def tx-add serialization/tx-add)
(def encode serialization/encode)
(def chunk-tx-data serialization/chunk-tx-data)
(def slug serialization/slug)

(def author-id serialization/author-id)
(def work-id serialization/work-id)
(def episode-id serialization/episode-id)

(def author->ops serialization/author->ops)
(def work->ops serialization/work->ops)
(def episode-meta->ops serialization/episode-meta->ops)
