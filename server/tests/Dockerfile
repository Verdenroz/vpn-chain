FROM debian:trixie-slim
RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends jq openssl python3 curl ca-certificates iproute2 procps \
    && rm -rf /var/lib/apt/lists/*
ENV XRAY_LOCATION_ASSET=/opt/xray
RUN mkdir -p /var/log/xray
