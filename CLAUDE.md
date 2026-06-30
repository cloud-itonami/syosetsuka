# ai-gftd-project-syosetsuka — AI Web 小説プラットフォーム (小説家になろう型)

共通ルールは `60-apps/CLAUDE.md`。設計 SSoT は `90-docs/adr/2606071600-syosetsuka-ai-novel-author-work-langgraph-kotoba-datomic.md`。

## Overview

syosetsuka.gftd.ai — AI が「作者ペルソナ」と「作品(連載小説)」を生成する Web 小説投稿
プラットフォーム。読者は人間、供給(作者・作品・話)は内製で無限生成する。

- **作者** = kotoba datom entity のみ (単一 app DID `did:web:syosetsuka.gftd.ai` 所有、DID actor にしない)
- **永続** = kotoba Datomic (`kotobase-kg-v1`, `:au/:nv/:ep/`-namespaced datoms) + B2 blob (本文)
- **運営境界** (Consensys-pattern ADR-2606011400): 製品レイヤ = etzhayyim front / kotoba データ backend・blob・課金・liability = gftd function

## Identifier (ADR-0019)

| 層 | 値 |
|---|---|
| Primary DID | `did:web:syosetsuka.gftd.ai` |
| Handle | `syosetsuka.gftd.ai` |
| nanoid | `sy0stk7n` |
| NSID | `ai.gftd.apps.syosetsuka.*` |

## Domain Model (kotoba Datomic — ADR-2606071600 §3)

| 概念 | entity ID | type | attr prefix |
|---|---|---|---|
| 作者 | `author:<penSlug>` | Author | `:au/` |
| 作品 | `work:<workSlug>` | Work | `:nv/` |
| 話 | `episode:<workSlug>:<index>` | Episode | `:ep/` (本文は blob、datom は `:ep/bodyBlobKey` のみ) |
| 世界観 | `world:<workSlug>` | Worldview | `:wd/` |
| 登場人物 | `char:<workSlug>:<charSlug>` | Character | `:ch/` |
| 感想 | `review:<rkey>` | Review | `:rv/` |

関係は ref datom (`:nv/author` → author / `:ep/work` → work …)。**本文・glossary 等の長文は datom に入れず B2 blob** (kotoba 書き込み肥大回避、ADR §3.4)。

## XRPC / Graph Surface (12 graphs)

| NSID | assistant_id | type |
|---|---|---|
| `ai.gftd.apps.syosetsuka.health` | health | procedure |
| `ai.gftd.apps.syosetsuka.listWorks` / `getWork` | list_works / get_work | query |
| `ai.gftd.apps.syosetsuka.listEpisodes` / `getEpisode` | list_episodes / get_episode | query |
| `ai.gftd.apps.syosetsuka.listAuthors` / `getAuthor` | list_authors / get_author | query |
| `ai.gftd.apps.syosetsuka.generateAuthor` | generate_author | procedure (agentTool) |
| `ai.gftd.apps.syosetsuka.composeWork` | compose_work | procedure (agentTool) |
| `ai.gftd.apps.syosetsuka.generateEpisode` | generate_episode | procedure (agentTool) |
| `ai.gftd.apps.syosetsuka.continueSerialization` | continue_serialization | procedure (agentTool) |
| `ai.gftd.apps.syosetsuka.produce` | produce | procedure (agentTool) |

## Architecture Flow (8-Layer)

```
読者/外部AI(MCP) → CF edge → atproto.gftd.ai → syosetsuka.gftd.ai (L3 edge proxy)
  → bpmn-dispatcher → AgentGateway MCP → lg-syosetsuka pod (/xrpc/{nsid})
    → read : dm_q / dm_pull → kotoba (:8080)
    → write: LLM(litellm) → uploadBlob(本文→B2) → dm_transact(meta→kotoba)
    → social: 公開時 sdk.pds.dispatch(app.bsky.feed.post)  [app DID]
```

CF Worker は edge-only (ADR-2605111200). The active CLJ graph runtime is a
deterministic scaffold and does not call kotoba/D1, B2, or an LLM in-process.

## kotoba sovereign ゲート (CRITICAL — ADR §7)

kotoba は本番でロールバック先例あり (shinshi/yukkuri が kotoba→RW/D1)。sovereign 宣言の前に
CLJ runtime 側へ durable store verification graph を追加し、transact→pull/q round-trip +
multi-value `:nv/tag` fidelity を WARM pod で検証する。`90-docs/MIGRATION-rw-to-kotoba-sovereign.md`
に syosetsuka エントリ追加 (未)。

## Layout

```
60-apps/ai-gftd-project-syosetsuka/
├── CLAUDE.md
├── clj/                                 # CLJ graph server pod
│   ├── langgraph.edn / deps.edn / Dockerfile
│   ├── src/syosetsuka/{server,edn}.cljc*
│   ├── src/syosetsuka/graphs/registry.cljc  # 12 graphs
│   └── test/syosetsuka/*_test.cljc
└── appview/ai-gftd-wasm-syosetsuka-sy0stk7n/
    └── magatama.jsonld                  # appview edge proxy (svelte UI = follow-up)
```

## Migration Backlog

| 項目 | 状態 |
|---|---|
| 設計 ADR-2606071600 | DONE (2026-06-07) |
| clj/ scaffold (12 graphs + EDN helpers + server) | DONE (scaffold) |
| Lexicon JSON × 16 (`00-contracts/lexicons/ai/gftd/apps/syosetsuka/`) | DONE (scaffold) |
| Helm chart `lg-syosetsuka-pool` | DONE (scaffold) |
| `deps.toml` `[[projects]]` / `[[mitama_actors]]` 登録 | DONE (scaffold) |
| appview `_blob` 内部エンドポイント (pod→Worker→PDS uploadBlob→B2) | TODO |
| appview Svelte CSR UI (作品一覧 / 作者 / 話プレイヤー) | TODO |
| `src/app.cljc` (TS Native edge worker: XRPC proxy + social derive) | TODO |
| Lexicon bundle 再生成 (bundle-lexicons → gen-pds-lexicon-registry → wrangler deploy) | TODO |
| BuildKit build+push `ghcr.io/gftdcojp/lg-syosetsuka:0.1.0-amd64` + helm install | TODO (operator) |
| §7 verify → sovereign ゲート登録 | TODO |
