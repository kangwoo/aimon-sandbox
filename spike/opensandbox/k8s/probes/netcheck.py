# run inside a sandbox: python3 - <targets...>  each target "name=host:port[:tls]" ; prints OPEN/BLOCKED
import socket, ssl, sys
for t in sys.argv[1:]:
    name, rest = t.split("=", 1); parts = rest.split(":"); host, port = parts[0], int(parts[1])
    try:
        s = socket.create_connection((host, port), timeout=4)
        extra = ""
        if len(parts) > 2 and parts[2] == "http":
            s.sendall(f"GET {parts[3] if len(parts)>3 else '/'} HTTP/1.0\r\nHost: {host}\r\n\r\n".encode()); extra = s.recv(80).decode(errors="replace").split("\r\n")[0]
        if len(parts) > 2 and parts[2] == "tls":
            c = ssl.create_default_context(); c.check_hostname=False; c.verify_mode=ssl.CERT_NONE
            ss = c.wrap_socket(s, server_hostname=host); ss.sendall(f"GET /version HTTP/1.0\r\nHost: {host}\r\n\r\n".encode()); extra = ss.recv(80).decode(errors="replace").split("\r\n")[0]
        print(f"{name:12s} {host}:{port} OPEN {extra}")
    except Exception as e:
        print(f"{name:12s} {host}:{port} BLOCKED ({type(e).__name__}: {e})")
