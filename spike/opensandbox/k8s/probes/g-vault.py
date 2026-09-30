# Probe g (vault): credential injection scoped by host+method+path, via the Python SDK through the server proxy
import asyncio, json, sys
from datetime import timedelta
from opensandbox import Sandbox
from opensandbox.config import ConnectionConfig
from opensandbox.models.sandboxes import NetworkPolicy, NetworkRule, CredentialProxyConfig, Credential, CredentialBinding

CHECK = r'''
import urllib.request, ssl, json
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
for m, url in [("GET","https://httpbin.org/headers"),("POST","https://httpbin.org/anything/x"),("GET","https://httpbin.org/anything/x"),("GET","https://httpbin.org/get")]:
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, method=m, data=b"" if m=="POST" else None), timeout=15, context=ctx)
        h = json.loads(r.read()).get("headers", {})
        print(m, url, r.status, "X-Api-Key=", h.get("X-Api-Key"))
    except Exception as e: print(m, url, "ERR", e)
import os; print("env has secret?", any("s3cr3t" in v for v in os.environ.values()))
'''
async def main():
    cfg = ConnectionConfig(domain="127.0.0.1:8091", api_key="spike-key", protocol="http", use_server_proxy=True)
    sb = await Sandbox.create("python:3.12-slim", connection_config=cfg, timeout=timedelta(minutes=15),
        resource={"cpu": "250m", "memory": "128Mi"}, metadata={"aimon.at/probe": "g-vault"},
        entrypoint=["sleep", "infinity"],
        network_policy=NetworkPolicy(defaultAction="deny", egress=[NetworkRule(action="allow", target="httpbin.org")]),
        credential_proxy=CredentialProxyConfig(enabled=True))
    print("sandbox", sb.id)
    try:
        r = await sb.credential_vault.create(
            credentials=[Credential(name="tok", source={"value": "s3cr3t-value"})],
            bindings=[CredentialBinding(name="tok", match={"schemes": ["https"], "hosts": ["httpbin.org"], "methods": ["GET"], "paths": ["/headers"]},
                                        auth={"type": "apiKey", "name": "X-Api-Key", "credential": "tok"})])
        print("vault create ->", r)
        ex = await sb.commands.run("python3 - <<'PY'\n" + CHECK + "\nPY")
        for l in ex.logs.stdout: print("  ", l.text)
        for l in ex.logs.stderr: print("  ERR", l.text)
    finally:
        await sb.kill()
asyncio.run(main())
