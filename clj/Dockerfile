# syosetsuka CLJ server image.
#
# Build:
#   cd orgs/gftdcojp/ai-gftd-syosetsuka/clj
#   docker buildx build --platform linux/amd64 \
#     -t ghcr.io/gftdcojp/lg-syosetsuka-clj:0.1.0-amd64 \
#     --push .

FROM clojure:temurin-21-tools-deps-bookworm

WORKDIR /app

COPY deps.edn bb.edn langgraph.edn /app/
COPY src /app/src

ENV PORT=8000

EXPOSE 8000

CMD ["clojure", "-M:run"]
