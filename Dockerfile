# syntax=docker/dockerfile:1
# Builds both the JVM web application and the native OpenCV renderer.
FROM eclipse-temurin:26-jdk AS build

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    build-essential cmake pkg-config \
    ffmpeg libavformat-dev libavcodec-dev libavutil-dev libopencv-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY web-app web-app
RUN chmod +x gradlew && ./gradlew --no-daemon :web-app:installDist

COPY render-engine render-engine
RUN cmake -S render-engine -B render-engine/build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build render-engine/build --parallel 2

FROM eclipse-temurin:26-jre

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ffmpeg libavformat-dev libavcodec-dev libavutil-dev libopencv-dev \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 jamal

WORKDIR /app
COPY --from=build /src/web-app/build/install/web-app /app/web-app
COPY --from=build /src/render-engine/build/jamal-render-engine /app/render-engine/build/jamal-render-engine
ARG MODNET_SHA256=5069a5e306b9f5e9f4f2b0360264c9f8ea13b257c7c39943c7cf6a2ec3a102ae
COPY models/modnet_photographic.onnx /app/models/modnet_photographic.onnx
COPY docker/start.sh /app/start.sh

RUN chmod +x /app/start.sh /app/render-engine/build/jamal-render-engine \
    && mkdir -p /data/exports \
    && echo "$MODNET_SHA256  /app/models/modnet_photographic.onnx" | sha256sum -c - \
    && chown -R jamal:jamal /app /data

USER jamal
# UTF-8 file names, so project folders keep Cyrillic and other non-Latin names.
ENV LANG=C.UTF-8 \
    JAMAL_BIND_HOST=0.0.0.0 \
    JAMAL_DATA_DIR=/data \
    JAMAL_EXPORTS_DIR=/data/exports \
    JAVA_OPTS="-Djamal.exports.dir=/data/exports"
EXPOSE 8787
VOLUME ["/data"]
ENTRYPOINT ["/app/start.sh"]
