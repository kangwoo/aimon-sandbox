"""Probe d: execd /command semantics."""
import threading
import time

from oslib import *


def run():
    tag = f"d{int(time.time())}"
    sid = create({"spike-run": tag}, timeout=1800).json()["id"]
    ex = Execd(sid)
    log(f"execd base={ex.base} headers={ex.headers}")

    log("== d1 stdout/stderr separation, exit code, framing")
    o = ex.run("echo out1; echo err1 >&2; echo out2; exit 7")
    log(f"   events={o['events']}")
    verdict("PASS" if o["stdout"] == "out1\nout2\n" and o["stderr"] == "err1\n" else "FAIL", "stdout/stderr separated", f"stdout={o['stdout']!r} stderr={o['stderr']!r}")
    verdict("OBSERVED", "exit code 7 reported as", f"error={o['error']}")
    o = ex.run("true")
    verdict("OBSERVED", "exit 0 reported as", f"last event={o['events'][-1]}")
    o = ex.run("printf 'no-newline'; printf 'A\\r\\nB\\n\\n\\nC'; printf '\\x00\\xff\\n'")
    verdict("OBSERVED", "byte fidelity (\\r, blank lines, trailing newline, NUL, 0xff)", f"events={[e for e in o['events'] if e.get('type') == 'stdout']}")
    o = ex.run("for i in 1 2 3; do echo o$i; echo e$i >&2; done")
    verdict("OBSERVED", "interleaving order of events", str([(e['type'], e.get('text')) for e in o['events'] if e.get('type') in ('stdout', 'stderr')]))
    o = ex.run("head -c 200000 /dev/zero | tr '\\0' 'x'; echo")
    verdict("OBSERVED", "one 200000-byte line", f"n_stdout_events={o.get('n_stdout_ev')} len={len(o['stdout'])}")

    log("== d2 cwd")
    o = ex.run("pwd")
    verdict("OBSERVED", "default cwd (no cwd given)", o["stdout"].strip())
    o = ex.run("pwd", cwd="/tmp")
    verdict("PASS" if o["stdout"].strip() == "/tmp" else "FAIL", "cwd=/tmp", o["stdout"].strip())
    o = ex.run("pwd", cwd="/does/not/exist")
    verdict("OBSERVED", "cwd that does not exist", f"http={o['status']} http_text={o['http_text'][:300]!r} stdout={o['stdout']!r} error={o['error']} events={o['events'][:4]}")
    o = ex.run("pwd", cwd="$HOME")
    verdict("OBSERVED", "cwd=$HOME expansion", f"{o['status']} {o['stdout'].strip()!r} {o['http_text'][:200]}")

    log("== d3 env")
    o = ex.run("env | sort", envs={"FOO": "bar baz", "MULTI": "l1\nl2", "PATH": "/usr/bin:/bin"})
    log("   env:\n      " + o["stdout"].replace("\n", "\n      ")[:2000])
    verdict("PASS" if "FOO=bar baz" in o["stdout"] else "FAIL", "envs injected; PATH overridable", "")
    o = ex.run("echo \"$MULTI\" | od -c | head -2", envs={"MULTI": "l1\nl2"})
    verdict("OBSERVED", "multi-line env value", o["stdout"].strip())
    o = ex.run("echo ${#BIG}", envs={"BIG": "x" * 200000})
    verdict("OBSERVED", "200 KB env value (> MAX_ARG_STRLEN 128 KiB)", f"http={o['status']} stdout={o['stdout'].strip()!r} error={o['error']} {o['http_text'][:200]}")

    log("== d4 how the command is passed (argv vs stdin)")
    o = ex.run("echo SELF=$$; tr '\\0' '|' < /proc/$$/cmdline; echo; ls -l /proc/$$/fd/0; ps -o pid,ppid,pgid,sid,user,args -e")
    log("   " + o["stdout"].replace("\n", "\n   "))
    verdict("OBSERVED", "command transport", "see cmdline above")

    log("== d5 large command bodies")
    for size in (64 * 1024, 127 * 1024, 129 * 1024, 1024 * 1024, 8 * 1024 * 1024):
        cmd = ": '" + "a" * size + "'; echo ok"
        t0 = time.time()
        try:
            o = ex.run(cmd)
            verdict("OBSERVED", f"command of {size} bytes", f"http={o['status']} stdout={o['stdout'].strip()!r} error={o['error']} http_text={o['http_text'][:200]!r} {time.time() - t0:.1f}s")
        except Exception as e:
            verdict("OBSERVED", f"command of {size} bytes", f"{type(e).__name__}: {e}")

    log("== d6 kill a running foreground command (DELETE /command?id=) — process group, & children, setsid children")

    def stream_until_started(c, command):
        """Open a /command stream, return (response_ctx, init_id) once 'started' is printed."""
        cm = c.stream("POST", "/command", json={"command": command})
        r = cm.__enter__()
        init = None
        it = r.iter_lines()
        for line in it:
            if '"init"' in line:
                init = json.loads(line.removeprefix("data:").strip())["text"]
            if "started" in line:
                break
        return cm, r, it, init

    c = httpx.Client(base_url=ex.base, timeout=None)
    cm, r, it, init = stream_until_started(c, "sleep 701 & setsid sleep 702 & nohup sleep 703 >/dev/null 2>&1 & echo started; exec sleep 700")
    before = Execd(sid).sh("ps -o pid,ppid,pgid,sid,args -e | grep -E 'sleep 70[0-3]' | grep -v grep")
    log("   before kill (pid ppid pgid sid args):\n      " + before.replace("\n", "\n      "))
    t0 = time.time()
    k = httpx.delete(ex.base + "/command", params={"id": init}, timeout=30)
    log(f"   DELETE /command?id={init} -> {k.status_code} {k.text[:200]!r} ({time.time() - t0:.2f}s)")
    rest = []
    try:
        for line in it:
            if line.strip():
                rest.append(line[:200])
    except Exception as e:
        rest.append(f"<{type(e).__name__}: {e}>")
    cm.__exit__(None, None, None)
    log(f"   stream after kill: {rest}")
    st = httpx.get(ex.base + f"/command/status/{init}", timeout=10)
    log(f"   status: {st.status_code} {st.text[:300]}")
    time.sleep(1)
    after = Execd(sid).sh("ps -o pid,pgid,sid,args -e | grep -E 'sleep 70[0-3]' | grep -v grep || echo NONE")
    log("   after DELETE /command:\n      " + after.replace("\n", "\n      "))
    verdict("OBSERVED", "survivors after interrupt", after.replace("\n", " | "))
    ex.sh("pkill -f 'sleep 70'; true")

    log("== d6b client disconnect: does closing the SSE stream kill the command?")
    cm, r, it, init = stream_until_started(c, "sleep 801 & setsid sleep 802 & echo started; exec sleep 800")
    cm.__exit__(None, None, None)
    c.close()
    time.sleep(2)
    after = ex.sh("ps -o pid,pgid,args -e | grep -E 'sleep 80[0-2]' | grep -v grep || echo NONE")
    verdict("OBSERVED", "after client disconnect", after.replace("\n", " | "))
    st = httpx.get(ex.base + f"/command/status/{init}", timeout=10)
    log(f"   status: {st.status_code} {st.text[:300]}")
    ex.sh("pkill -f 'sleep 80'; true")

    log("== d7 foreground command that leaves a background child: does the call return when the shell exits?")
    t0 = time.time()
    o = ex.run("sleep 30 & echo parent-done")
    verdict("PASS" if time.time() - t0 < 10 else "FAIL", "fg returns without waiting for '&' child", f"{time.time() - t0:.1f}s stdout={o['stdout']!r}")
    t0 = time.time()
    o = ex.run("sleep 20 > /dev/null 2>&1 & echo parent-done")
    verdict("OBSERVED", "same with child redirected", f"{time.time() - t0:.1f}s")
    ex.sh("pkill -f 'sleep 30'; pkill -f 'sleep 20'; true")

    log("== d8 timeout param (ms)")
    t0 = time.time()
    o = ex.run("sleep 901 & setsid sleep 902 & exec sleep 900", timeout_ms=2000)
    log(f"   {time.time() - t0:.1f}s events={o['events']}")
    time.sleep(1)
    after = ex.sh("ps -o pid,pgid,args -e | grep -E 'sleep 90[0-2]' | grep -v grep || echo NONE")
    verdict("OBSERVED", "survivors after timeout", after.replace("\n", " | "))
    ex.sh("pkill -f 'sleep 90'; true")

    log("== d9 background mode")
    o = ex.run("echo bg-start; echo bg-err >&2; sleep 3; echo bg-end; exit 4", background=True)
    log(f"   events={o['events']}")
    bid = o["init_id"]
    st = httpx.get(ex.base + f"/command/status/{bid}")
    log(f"   status now: {st.text}")
    time.sleep(5)
    st = httpx.get(ex.base + f"/command/status/{bid}")
    lg = httpx.get(ex.base + f"/command/{bid}/logs")
    verdict("OBSERVED", "background status/logs", f"status={st.text} logs={lg.text!r} headers={dict((k, v) for k, v in lg.headers.items() if 'cursor' in k.lower())}")
    lg = httpx.get(ex.base + f"/command/{bid}/logs", params={"cursor": 1})
    verdict("OBSERVED", "logs with cursor=1", repr(lg.text))

    log("== d10 output volume: 50 MB on stdout")
    t0 = time.time()
    o = ex.run("yes 0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789 | head -c 50000000; echo; echo done >&2")
    verdict("OBSERVED", "50 MB output", f"received stdout chars={len(o['stdout'])} events={o.get('n_stdout_ev')} sse_bytes={o['bytes']} stderr={o['stderr']!r} error={o['error']} {time.time() - t0:.1f}s")
    o = ex.run("df -h /tmp /; ls -la /tmp | head; ls /var/log 2>/dev/null | head")
    log("   " + o["stdout"].replace("\n", "\n   "))

    # d11 lives in d_uid.py


main_guard(run)
