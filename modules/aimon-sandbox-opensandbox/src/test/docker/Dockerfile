# The sandbox image of the docker test tier: the image contract of docs/design/workspace-sandbox.md §13.3 (bash,
# coreutils, git, rg, flock, sha256sum) and a writable /workspace. It runs as root, as every sandbox of the Docker
# runtime does — the tier's profiles waive HARDENED_SECURITY_CONTEXT and NETWORK_ISOLATION.
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash coreutils util-linux git ripgrep procps ca-certificates curl dnsutils \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /workspace && chmod 0777 /workspace
CMD ["tail", "-f", "/dev/null"]
