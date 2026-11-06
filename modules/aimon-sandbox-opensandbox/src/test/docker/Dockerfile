# The sandbox image of the docker test tier: the image contract of docs/design/workspace-sandbox.md §13.3 (bash,
# coreutils with sha256sum and base64, git, rg, flock) and a writable /workspace. It runs as root, as every
# sandbox of the Docker runtime does — the tier's profiles waive HARDENED_SECURITY_CONTEXT and NETWORK_ISOLATION.
# python3 (the full package, not python3-minimal, which lacks modules core's runtime imports) is the optional
# `python3` item of §13.3, so this tier can run core's Python ExecuteCode shell engine.
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash coreutils util-linux git ripgrep procps ca-certificates curl dnsutils python3 \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /workspace && chmod 0777 /workspace
CMD ["tail", "-f", "/dev/null"]
