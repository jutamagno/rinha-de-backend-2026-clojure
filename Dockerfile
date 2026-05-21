# ─── Stage 1: Gerar index.bin a partir de references.json.gz ─────────────────
FROM amazoncorretto:21-alpine-jdk AS indexer

WORKDIR /build

RUN apk add --no-cache bash curl rlwrap && \
    curl -sL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh | bash

COPY deps.edn .
RUN clojure -P -A:index

COPY src/     src/
COPY scripts/ scripts/
COPY resources/references.json.gz /data/references.json.gz

RUN clojure -J-Xmx512m -M:index /data/references.json.gz /data/index.bin


# ─── Stage 2: Compilar uberjar ────────────────────────────────────────────────
FROM amazoncorretto:21-alpine-jdk AS builder

WORKDIR /build

RUN apk add --no-cache bash curl rlwrap && \
    curl -sL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh | bash

COPY deps.edn build.clj ./
RUN clojure -P && clojure -P -T:build

ARG CACHEBUST=1
COPY src/       src/
COPY src-java/  src-java/
COPY resources/ resources/

RUN clojure -T:build uber


# ─── Stage 3: GraalVM native image ────────────────────────────────────────────
FROM ghcr.io/graalvm/native-image-community:21 AS native

RUN microdnf install -y curl findutils

WORKDIR /build

COPY --from=builder /build/target/rinha.jar ./rinha.jar
COPY --from=indexer /data/index.bin         /data/index.bin
COPY scripts/sample-payload.json            ./sample-payload.json
COPY src-java/rinha/ClojureFeature.java     ./ClojureFeature.java

ENV INDEX_PATH=/data/index.bin
ENV PORT=3000

# Compile ClojureFeature and inject into uber JAR
RUN mkdir -p /build/feature-classes && \
    javac \
      --module-path $JAVA_HOME/jmods \
      --add-modules org.graalvm.nativeimage \
      -d /build/feature-classes \
      /build/ClojureFeature.java && \
    jar uf /build/rinha.jar -C /build/feature-classes .

# Rodar com native-image-agent para coletar configurações de reflexão
RUN set -e; \
    mkdir -p /build/graal-config; \
    java -agentlib:native-image-agent=config-output-dir=/build/graal-config \
         -Xmx512m -jar /build/rinha.jar & \
    SERVER_PID=$!; \
    for i in $(seq 1 60); do \
      sleep 2; \
      if curl -sf http://localhost:3000/ready > /dev/null 2>&1; then \
        echo "Servidor pronto em $((i * 2))s"; break; \
      fi; \
    done; \
    curl -s -X POST http://localhost:3000/fraud-score \
      -H 'Content-Type: application/json' \
      -d @/build/sample-payload.json; \
    echo; \
    kill "$SERVER_PID" 2>/dev/null || true; \
    wait "$SERVER_PID" 2>/dev/null || true; \
    ls -la /build/graal-config/ && \
    echo "=== reflect-config classes ===" && \
    grep -o '"name":"[^"]*"' /build/graal-config/reflect-config.json | grep -i "clojure.core\|clojure.lang.RT\|clojure.lang.PersistentList" | head -30 || true

RUN native-image \
    --features=rinha.ClojureFeature \
    --initialize-at-build-time=clojure,rinha,com.fasterxml.jackson,org.httpkit \
    -H:ConfigurationFileDirectories=/build/graal-config \
    -jar /build/rinha.jar \
    -o /build/rinha-server


# ─── Stage 4: Runtime mínimo ─────────────────────────────────────────────────
FROM debian:12-slim

COPY --from=native /build/rinha-server /rinha-server
COPY --from=indexer /data/index.bin    /data/index.bin

ENV PORT=3000
ENV INDEX_PATH=/data/index.bin

EXPOSE 3000
ENTRYPOINT ["/rinha-server"]
