# inside sandbox: DNS + HTTP(S) checks
import socket, sys, urllib.request, ssl
def dns(h):
    try: return ",".join(sorted({a[4][0] for a in socket.getaddrinfo(h, 443, socket.AF_INET)}))
    except Exception as e: return f"NXDOMAIN/ERR({e})"
def get(url, host=None):
    try:
        req = urllib.request.Request(url, headers={"Host": host} if host else {})
        ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
        r = urllib.request.urlopen(req, timeout=6, context=ctx); return f"OPEN {r.status}"
    except urllib.error.HTTPError as e: return f"OPEN(HTTP {e.code})"
    except Exception as e: return f"BLOCKED ({type(e).__name__}: {str(e)[:80]})"
for h in ["example.com", "www.google.com", "github.com"]:
    print(f"dns  {h:18s} -> {dns(h)}")
print("http example.com         ->", get("http://example.com/"))
print("https example.com        ->", get("https://example.com/"))
print("https www.google.com     ->", get("https://www.google.com/"))
print("https 1.1.1.1 (by IP)    ->", get("https://1.1.1.1/"))
ip = sys.argv[1] if len(sys.argv) > 1 else ""
if ip: print(f"http {ip} (example.com IP, Host: example.com) ->", get(f"http://{ip}/", "example.com"))
print("http 8.8.8.8:53 tcp      ->", end=" ")
try: socket.create_connection(("8.8.8.8", 53), timeout=4); print("OPEN")
except Exception as e: print("BLOCKED", type(e).__name__)
