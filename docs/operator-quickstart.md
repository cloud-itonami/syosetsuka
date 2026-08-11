# operator quickstart — 5 手で動かして、届く範囲を自分で確かめる

**2026-08-11 に実際に踏んで、下の出力を記録した。** 引用してある出力は
手打ちではなく実行結果である。

前提: `clojure`（このリポジトリは 1.12.0）と `curl`。ネットワークは初回の
依存解決にだけ要る。

---

## S1. テストが緑であることを先に見る

自分の変更の可否を判定できるようにするため、**触る前に**一度通す。

```bash
cd clj
clojure -M:test
```

**期待**:

```
Ran 15 tests containing 76 assertions.
0 failures, 0 errors.
```

> **`:dev` alias は west checkout の中でしか動かない。**
> `:dev` は `shousetsu` / `langchain` / `sha256d` を `../../../kotoba-lang/<name>`
> の `:local/root` に差し替える。`/tmp` に切った worktree など west の外では
> このパスが存在せず解決に失敗する。**worktree では alias 無し**（`deps.edn` の
> git 座標）で回すこと —— 上のコマンドはそれで通る。

## S2. サーバを起こす

```bash
PORT=8000 clojure -M:run
```

以降は別端末から叩く（この文書では `PORT=8123` で実測した）。

## S3. 何が居るかを訊く

```bash
curl -sS http://localhost:8000/ok
curl -sS http://localhost:8000/health
```

**実測**:

```clojure
{:ok true, :graphs ["health" "list_works" "get_work" "list_episodes" "get_episode"
                    "list_authors" "get_author" "generate_author" "compose_work"
                    "generate_episode" "continue_serialization" "produce" "verify_store"],
 :version "0.1.0", :impl "clj"}
{:ok true, :checkpointer false, :impl "clj"}
```

`:checkpointer false` は誤りではない —— この runtime は永続化を持たない。

## S4. 作者を 1 人生成して、決定論であることを確かめる

「AI が生成する」と言いつつ **LLM は呼ばない**。同じ入力からは必ず同じ作者が出る。
そこが確かめたい性質なので、2 回以上叩いて比べる。

```bash
curl -sS -X POST http://localhost:8000/runs \
  -H 'content-type: application/json' \
  -d '{"assistant_id":"generate_author","input":{"theme":"深海の図書館"}}'
```

**実測**（3 回叩いて 3 回とも同一）:

```json
{"ok":true,
 "result":{"author_id":"author:x576e64d","authorId":"author:x576e64d","status":"created",
           "author":{"author_id":"author:x576e64d","penName":"AI小説家 x576e64d",
                     "genreAffinity":"自由","voice":"deterministic-clj"}},
 "assistantId":"generate_author","latencyMs":0}
```

theme を `"砂漠の郵便局"` に変えると `author:xac775a2`。**theme が同じなら id も同じ、
違えば違う** —— これが決定論スキャフォールドの意味で、`voice` が
`"deterministic-clj"` と自称しているとおりである。

**表現の選択**: `content-type` か `accept` に `edn` を含めると EDN、`json` を
含めると JSON が返る（既定は EDN）。

```bash
curl -sS -X POST http://localhost:8000/runs \
  -H 'content-type: application/edn' -H 'accept: application/edn' \
  -d '{:assistant_id "health" :input {}}'
# => {:ok true, :result {:ok true, :store_ok false, ...}, :assistantId "health", :latencyMs 0}
```

**存在しない graph** は 404:

```bash
curl -sS -X POST http://localhost:8000/runs -H 'content-type: application/json' \
  -H 'accept: application/json' -d '{"assistant_id":"no_such_graph","input":{}}'
# => {"ok":false,"error":"unknown graph/nsid: no_such_graph",...}   [404]
```

## S5. 2 つの入口があり、鍵が掛かるのは片方だけ

`LG_API_KEY` を設定するとサーバは `x-api-key` を要求する。**ただし要求するのは
`/runs` だけで、`/xrpc/*` は素通りする。**

```bash
LG_API_KEY=secret123 PORT=8000 clojure -M:run
```

実測（サーバは `LG_API_KEY=secret123` で起動している）:

| 叩き方 | 結果 |
|---|---|
| `POST /runs` key 無し | `{"error":"invalid x-api-key"}` **401** |
| `POST /runs` 誤った key | `{"error":"invalid x-api-key"}` **401** |
| `POST /runs` 正しい key | **200** |
| `POST /xrpc/ai.gftd.apps.syosetsuka.health` **key 無し** | **200** |
| `POST /xrpc/ai.gftd.apps.syosetsuka.generateAuthor` **key 無し** | **200**（`author:x576e64d` が返る） |

**同じ graph が、`/runs` 経由では 401、`/xrpc` 経由では 200 になる。**
再現:

```bash
curl -sS -X POST http://localhost:8000/xrpc/ai.gftd.apps.syosetsuka.generateAuthor \
  -H 'content-type: application/json' -d '{"theme":"深海の図書館"}'      # 200
curl -sS -X POST http://localhost:8000/runs -H 'content-type: application/json' \
  -d '{"assistant_id":"generate_author","input":{"theme":"深海の図書館"}}'  # 401
```

コード上も `runs-handler` だけが `api-key-ok?` を通り、`xrpc-handler` は通らない
（`clj/src/syosetsuka/server.cljc`）。**これが意図なのか漏れなのかは、この文書では
決めない** —— atproto の XRPC を公開面として開ける設計はありうる。ただし
**現状の `LG_API_KEY` は「graph へのアクセス制御」ではなく「`/runs` という入口の
制御」でしかない**、という事実だけは踏む前に知っておくこと。公開ホストに置く前に
どちらなのかを確定させる。

---

## この手順が答えないこと

- **公開経路が動くかどうか。** 動かない。`syosetsuka.gftd.ai` と
  `atproto.gftd.ai` は実測 2026-08-11 で **522**、後継 `pds.aozora.app` は
  `ai.gftd.apps.syosetsuka.*` に **404 MethodNotImplemented** を返す。
  ここで確かめられるのは**ローカルの runtime だけ**（README の表を参照）。
- **kotoba / B2 / LLM に本当に書けるか。** 書かない。`verify_store` graph は
  その round-trip を実測するための gate だが、実測先（kotobase.net の tenant
  Datom plane）への疎通は別途要る。設計上の位置づけは `CLAUDE.md` の
  「kotoba sovereign ゲート」節。
