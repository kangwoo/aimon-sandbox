# direct DNS query (UDP and TCP) to an external resolver, bypassing /etc/resolv.conf
import socket, struct, sys, random
def q(name):
    h = struct.pack(">HHHHHH", random.randint(0,65535), 0x0100, 1, 0, 0, 0)
    return h + b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\0" + struct.pack(">HH", 1, 1)
def parse(r): 
    an = struct.unpack(">H", r[6:8])[0]; rc = r[3] & 0xF; return f"rcode={rc} answers={an}"
for srv in sys.argv[1:]:
    for name in ["www.google.com", "exfil-abc123.example.org"]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(4); s.sendto(q(name), (srv, 53)); r = s.recv(512); print(f"udp {srv} {name}: {parse(r)}")
        except Exception as e: print(f"udp {srv} {name}: BLOCKED {type(e).__name__}")
        try:
            s = socket.create_connection((srv, 53), timeout=4); m = q(name); s.sendall(struct.pack(">H", len(m)) + m); r = s.recv(514)[2:]; print(f"tcp {srv} {name}: {parse(r) if r else 'empty/closed'}")
        except Exception as e: print(f"tcp {srv} {name}: BLOCKED {type(e).__name__}")
