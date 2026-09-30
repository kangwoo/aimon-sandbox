# OpenSandbox 스파이크 — 프로브와 근거

[`docs/design/opensandbox-spike.md`](../../docs/design/opensandbox-spike.md) 의 근거 자료다. 빌드에 들어가지 않는다.
4단계의 `aimon-sandbox-opensandbox` 가 계약 스위트를 실서버에 돌리기 시작하면 이 프로브는 역할을 다한다.

| 경로 | 내용 |
|------|------|
| `reports/A-source.md` | OpenSandbox `3738975` 소스 정적 분석. 주장마다 file:line |
| `reports/B-docker.md` | Docker 런타임 실측 |
| `reports/C-k8s.md` | kind 위 K8s 런타임 실측 |
| `docker/` | 서버 설정(`config.toml`) · 기동 스크립트 · 샌드박스 이미지(`image/Dockerfile`) · 프로브(`probes/`, REST 직접 호출) · 원본 출력(`evidence/`) |
| `k8s/` | kind 설정 · 차트 값과 BatchSandbox 템플릿 · 운영자 NetworkPolicy 예(`deploy/`) · 프로브와 원본 출력(`probes/`, `probes/out/`) |

API 키(`spike-docker-key-…`, `spike-key`)는 로컬에서 쓰고 버린 값이다.

## Docker 런타임

```bash
git clone https://github.com/opensandbox-group/OpenSandbox && git -C OpenSandbox checkout 3738975
docker build -t spike-docker-base:1 spike/opensandbox/docker/image
OPENSANDBOX_SRC=$PWD/OpenSandbox spike/opensandbox/docker/start-server.sh &   # :8090
cd spike/opensandbox/docker && uv run --with httpx python probes/a_lifecycle.py
```

프로브는 `OS_BASE`(기본 `http://localhost:8090/v1`) · `OS_KEY` · `OS_IMAGE` 를 읽는다. `f_network.py` 의 `dns+nft` 부분은
`config.toml` 의 `[egress].mode` 를 바꾸고 서버를 다시 띄운 뒤 돈다. macOS 에서는 `[proxy].resolve_internal = false` 가 필요하고,
Linux 에서는 기본값(`true`)이 동작한다.

## K8s 런타임

`reports/C-k8s.md` §1 이 절차다 — kind 클러스터 `spike-os`, 차트 `manifests/charts/opensandbox`(`1.1.1-rc.1`)를
`k8s/deploy/values.yaml` 로 설치하고 `kubectl port-forward svc/opensandbox-server 8091:80`. 프로브는 `k8s/probes/lib.sh`
를 source 하며 `API` · `KEY` · `IMG` 를 읽는다. pause 프로브는 로컬 레지스트리와 containerd `config_path` 패치가, RWX
프로브는 NFS provisioner 가 필요하다.
