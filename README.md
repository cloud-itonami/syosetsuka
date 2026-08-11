# syosetsuka — AI が書く Web 小説プラットフォーム（の、決定論スキャフォールド）

**「小説家」。** AI が作者ペルソナと連載小説を生成し、読者（人間）に供給する
Web 小説投稿プラットフォーム（小説家になろう型）の実装 repo。名前が機能を
示さないので先に名乗る —— **供給側（作者・作品・話）を内製で生成する側**であって、
読む側の app ではない。

## いま実際に動くもの / まだ動かないもの

**この 2 つを混同しないために、この節を README の先頭に置く。**

| | 状態 |
|---|---|
| `clj/` の graph server（13 graph、HTTP + XRPC） | **動く。** テスト 15 本 / 76 assertion が緑 |
| kotoba / D1 / B2 / LLM の呼び出し | **動かない。** CLJ runtime は決定論スキャフォールドで、in-process では一切呼ばない（`health` graph 自身が `"CLJ syosetsuka scaffold does not ping kotoba/D1/B2."` と返す） |
| 公開エンドポイント `syosetsuka.gftd.ai` | **到達不能。** 実測 2026-08-11: HTTP **522**（edge は応答、origin が繋がらない） |
| その前段 `atproto.gftd.ai` | **到達不能。** 実測 2026-08-11: HTTP **522**。worker は 2026-07-29 に退役済み |
| 後継 PDS `pds.aozora.app` がこの穴を埋めるか | **埋めない。** ホスト自体は生きている（`/xrpc/_health` → 200）が、`ai.gftd.apps.syosetsuka.*` は **404 `MethodNotImplemented`**（実測 2026-08-11） |

つまり **ローカルでは動き、公開経路では届かない。** 入口を復旧するには
「どこに置くか」の判断が要る（技術的な詰まりではない）。前段 2 ホップの経緯と
8 層アーキテクチャ図は [`CLAUDE.md`](CLAUDE.md) が正本 —— 図は設計であって
現在の疎通ではない、という但し書きごとそこに書いてある。

## repo の地図

| 場所 | 中身 |
|---|---|
| `clj/` | **実装の本体。** graph server（http-kit + reitit）、13 graph の registry、kotoba store client、CACAO、verify-store gate。詳細は [`clj/README.md`](clj/README.md) |
| `CLAUDE.md` | 設計 SSoT へのポインタ、識別子（DID / handle / NSID）、ドメインモデル、sovereign ゲート、**到達不能ホップの実測記録** |
| `schema.edn` | datom schema |
| `appview/` | appview 側の JSON-LD |
| `edn-datomize.bb` | EDN → datom 変換 |
| `README.md.edn` | 分割元の記録（`ai-gftd-apps-gftdcojp` から west 管理へ切り出した経緯）。**この README とは別物**で、消さない |

## 動かしてみる

[`docs/operator-quickstart.md`](docs/operator-quickstart.md) に 5 手。
テスト → サーバ起動 → `/ok` → `/runs` で実際に作者を 1 人生成 → XRPC まで、
全部コピペで踏める（2026-08-11 に実際に踏んで出力を記録した）。

一番短い経路だけここに:

```bash
cd clj
clojure -M:test                    # Ran 15 tests containing 76 assertions. 0 failures, 0 errors.
PORT=8000 clojure -M:run           # 別端末で: curl localhost:8000/ok
```

## 識別子

| 層 | 値 |
|---|---|
| Primary DID | `did:web:syosetsuka.gftd.ai` |
| Handle | `syosetsuka.gftd.ai` |
| nanoid | `sy0stk7n` |
| NSID | `ai.gftd.apps.syosetsuka.*` |

作者は DID actor **ではない** —— kotoba datom entity であって、app DID
1 つ（上記）が全作者を所有する。この区別は設計上の判断なので
`CLAUDE.md` の該当節を読んでから触ること。
