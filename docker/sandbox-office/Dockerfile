FROM ghcr.io/agent-infra/sandbox:latest

USER root

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        libreoffice \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
