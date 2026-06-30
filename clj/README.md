# syosetsuka.gftd.ai — Clojure edition

CLJ graph server replacing the old Python `lg_syosetsuka` LangGraph runtime.

Surface:

- `GET /ok`, `/health`
- `POST /runs`
- `POST /xrpc/ai.gftd.apps.syosetsuka.*`

The CLJ runtime preserves the 12 graph names and NSIDs. It currently runs
deterministic scaffolds and EDN/datom helpers only; it does not call kotoba/D1,
B2, or an LLM.

```bash
clojure -M:test
PORT=8000 clojure -M:run
```
