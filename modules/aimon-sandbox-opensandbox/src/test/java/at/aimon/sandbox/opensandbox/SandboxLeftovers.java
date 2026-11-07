package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import at.aimon.core.shell.ShellCommandResult;

/**
 * What a shell-engine run leaves in a sandbox (docs/design/ptc-shell-provider.md §9 Q-C4): the sandbox's processes as
 * {@code ps} lists them, the ones that count as left over — a zombie, a core runtime ({@code aimon_ptc}) or a child the
 * test's program started — and which of them are new since a baseline. A zombie is shown with its parent, the process
 * that did not reap it. Also the program a run executes to record the runtime's process topology from the inside: its
 * anchor and its watchdog (aimon-core#336), which have both gone by the time the test can look.
 */
final class SandboxLeftovers {

    /**
     * Four fields, the start time ({@code lstart}: five words in the C locale, to the second) and the args, so the
     * command's own line ({@code ps -eo …}) is parsed like any other and filtered by args.
     */
    static final String PS = "LC_ALL=C ps -eo pid=,ppid=,pgid=,stat=,lstart=,args=";

    /** How long a check waits for the watchdog's kill and the reaper's {@code wait} to finish: 50 × 200 ms. */
    private static final int ATTEMPTS = 50;
    private static final long PAUSE_MILLIS = 200;

    private SandboxLeftovers() {
    }

    /** A sandbox shell, as {@link SharedSandbox#bash} runs it. */
    @FunctionalInterface
    interface Shell {
        ShellCommandResult bash(String command) throws Exception;
    }

    /** One line of {@link #PS}; {@code started} is its {@code lstart}, empty where nothing recorded it. */
    record Proc(int pid, int ppid, int pgid, String stat, String started, String args) {

        boolean zombie() {
            return stat.startsWith("Z");
        }

        /** A process, not just a pid: a pid an exited leftover freed and a new one took has another start time. */
        Key key() {
            return new Key(pid, started);
        }

        /** {@code pid ppid pgid stat args}, and for a zombie {@code [parent: <its parent's args>]}. */
        String render(Map<Integer, Proc> all) {
            final String line = pid + " " + ppid + " " + pgid + " " + stat + " " + args;
            if (!zombie()) {
                return line;
            }
            final Proc parent = all.get(ppid);
            return line + " [parent: " + (parent == null ? "gone" : parent.args()) + "]";
        }
    }

    /** What a baseline holds of a process: its pid and its start time, which a reused pid does not share. */
    record Key(int pid, String started) {
    }

    /**
     * @param ps
     *            the output of {@link #PS}
     * @return its processes; a line that does not have the four fields, the five words of the start time and the args
     *         is skipped
     */
    static List<Proc> parse(String ps) {
        final List<Proc> procs = new ArrayList<>();
        for (String line : ps.lines().map(String::strip).toList()) {
            final String[] fields = line.split("\\s+", 10);
            if (fields.length < 10) {
                continue;
            }
            try {
                procs.add(
                        new Proc(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                                fields[3], String.join(" ", List.of(fields).subList(4, 9)), fields[9]));
            } catch (NumberFormatException e) {
                // a header or a wrapped line: not a process
            }
        }
        return procs;
    }

    /**
     * @param mark
     *            the argument of the {@code sleep} a test's program started, or {@code null}
     * @return what counts as left over: a zombie, a core runtime, or {@code sleep <mark>}
     */
    static Predicate<Proc> leftOver(String mark) {
        return proc -> proc.zombie() || proc.args().contains("aimon_ptc")
                || (mark != null && proc.args().equals("sleep " + mark));
    }

    /**
     * @return every process of the sandbox. A {@code ps} that fails or does not list PID 1 fails the test instead of
     *         passing it
     */
    static List<Proc> snapshot(Shell shell) throws Exception {
        final ShellCommandResult ps = shell.bash(PS);
        assertThat(ps.exitCode()).as("ps in the sandbox: %s", ps.stderr()).isZero();
        final List<Proc> procs = parse(ps.stdout());
        assertThat(procs).as("ps lists the sandbox's PID 1:%n%s", ps.stdout()).anyMatch(proc -> proc.pid() == 1);
        return procs;
    }

    /**
     * @return the processes of {@code procs} that count as left over, by pid and start time: the baseline a later check
     *         subtracts
     */
    static Set<Key> baseline(List<Proc> procs, Predicate<Proc> leftOver) {
        return procs.stream().filter(leftOver).map(Proc::key).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The left-over processes of {@code procs}, split by {@code baseline} and rendered for a failure message.
     *
     * @return empty when nothing new is left over; otherwise the new lines, then the ones present before
     */
    static String describeNew(List<Proc> procs, Set<Key> baseline, Predicate<Proc> leftOver) {
        final Map<Integer, Proc> all = procs.stream()
                .collect(Collectors.toMap(Proc::pid, Function.identity(), (a, b) -> a));
        final List<String> fresh = new ArrayList<>();
        final List<String> before = new ArrayList<>();
        for (Proc proc : procs) {
            if (leftOver.test(proc)) {
                (baseline.contains(proc.key()) ? before : fresh).add(proc.render(all));
            }
        }
        if (fresh.isEmpty()) {
            return "";
        }
        return "new since this run (pid ppid pgid stat args):\n  " + String.join("\n  ", fresh)
                + (before.isEmpty() ? "" : "\npresent before this run:\n  " + String.join("\n  ", before));
    }

    /**
     * Polls until nothing left over is new since {@code baseline}, and fails naming {@code label} otherwise. The
     * watchdog's kill and PID 1's reaping are asynchronous to the shell call's return.
     */
    static void awaitNone(Shell shell, Set<Key> baseline, Predicate<Proc> leftOver, String label) throws Exception {
        String left = describeNew(snapshot(shell), baseline, leftOver);
        for (int attempt = 0; attempt < ATTEMPTS && !left.isEmpty(); attempt++) {
            Thread.sleep(PAUSE_MILLIS);
            left = describeNew(snapshot(shell), baseline, leftOver);
        }
        assertThat(left).as("%s left processes in the sandbox", label).isEmpty();
    }

    /**
     * A Python program that records, before it does anything else, what the process running it sees: itself, its
     * parent (the anchor, in the shell's group) and the watchdog candidates — processes whose group is the one it leads
     * ({@code pgid == self}) that are neither itself nor its direct children, with their command lines. aimon_ptc.py's
     * double fork leaves the watchdog there, reparented to PID 1 or a subreaper; {@link WatchdogProbe#watchdogs} picks
     * it out. It writes {@code key=value} lines to {@code path}; {@link #parseProbe} reads them back.
     */
    static String watchdogProbeProgram(String path) {
        return String.join("\n", "import os as _os", "def _probe_stat(pid):",
                "    with open('/proc/%d/stat' % pid) as f:", "        rest = f.read().rsplit(')', 1)[1].split()",
                "    return int(rest[1]), int(rest[2])", "def _probe_args(pid):",
                "    with open('/proc/%d/cmdline' % pid, 'rb') as f:",
                "        return f.read().replace(b'\\0', b' ').decode('utf-8', 'replace').strip()",
                "_me = _os.getpid()", "_ppid, _pgid = _probe_stat(_me)", "_lines = ['self=%d pgid=%d' % (_me, _pgid)]",
                "_lines.append('parent=%d pgid=%d args=%s' % (_ppid, _probe_stat(_ppid)[1], _probe_args(_ppid)))",
                "for _d in _os.listdir('/proc'):", "    if _d.isdigit() and int(_d) != _me:", "        try:",
                "            _p, _g = _probe_stat(int(_d))", "        except (OSError, ValueError, IndexError):",
                "            continue", "        if _g == _me and _p != _me:",
                "            _lines.append('watchdog=%s ppid=%d pgid=%d args=%s' % (_d, _p, _g, _probe_args(int(_d))))",
                "with open('" + path + "', 'w') as f:", "    f.write('\\n'.join(_lines) + '\\n')", "");
    }

    /** What {@link #watchdogProbeProgram} saw. */
    record WatchdogProbe(int self, int selfPgid, int parent, int parentPgid, String parentArgs, List<Proc> candidates) {

        /** The runtime leads its own group, which is not the anchor's (the exec's). */
        boolean runtimeLeadsItsOwnGroup() {
            return selfPgid == self && parentPgid != selfPgid;
        }

        /**
         * @return the candidates that are the runtime's watchdog: a fork of the runtime, so its command line names
         *         {@code aimon_ptc}, whose parent is neither the runtime nor the anchor — an unrelated grandchild in
         *         the
         *         group, or a child of the anchor, does not count
         */
        List<Proc> watchdogs() {
            return candidates.stream().filter(c -> c.ppid() != self && c.ppid() != parent)
                    .filter(c -> c.args().contains("aimon_ptc")).toList();
        }
    }

    /**
     * @param text
     *            the probe file
     * @return what it recorded
     * @throws IllegalArgumentException
     *             when a {@code self} or {@code parent} line is missing
     */
    static WatchdogProbe parseProbe(String text) {
        Map<String, String> self = null;
        Map<String, String> parent = null;
        final List<Proc> candidates = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (line.startsWith("self=")) {
                self = fields(line, 2);
            } else if (line.startsWith("parent=")) {
                parent = fields(line, 3);
            } else if (line.startsWith("watchdog=")) {
                final Map<String, String> w = fields(line, 4);
                candidates.add(new Proc(Integer.parseInt(w.get("watchdog")), Integer.parseInt(w.get("ppid")),
                        Integer.parseInt(w.get("pgid")), "", "", w.getOrDefault("args", "")));
            }
        }
        if (self == null || parent == null) {
            throw new IllegalArgumentException("not a complete watchdog probe:\n" + text);
        }
        return new WatchdogProbe(Integer.parseInt(self.get("self")), Integer.parseInt(self.get("pgid")),
                Integer.parseInt(parent.get("parent")), Integer.parseInt(parent.get("pgid")), parent.get("args"),
                List.copyOf(candidates));
    }

    /** A zombie under a live parent that never waits for it, both started by {@link #startUnreapedZombie}. */
    record Zombie(int parent, int zombie) {
    }

    /**
     * Starts, in the background of {@code shell}, a {@code python3} parent that forks a child which exits at once
     * while the parent sleeps without waiting: the child stays a zombie until the parent dies, then whoever adopts it
     * has to reap it. The background process holds none of the shell's descriptors, so the call returns at once.
     *
     * @return the parent and its zombie, once {@code ps} shows the zombie under the parent
     */
    static Zombie startUnreapedZombie(Shell shell) throws Exception {
        final ShellCommandResult started = shell.bash("python3 -c 'import os, time; os.fork() and time.sleep(600)' "
                + "aimon-zombie-parent </dev/null >/dev/null 2>&1 & echo $!");
        assertThat(started.exitCode()).as("starting the zombie's parent: %s", started.stderr()).isZero();
        final int parent = Integer.parseInt(started.stdout().strip());
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            for (Proc proc : snapshot(shell)) {
                if (proc.zombie() && proc.ppid() == parent) {
                    return new Zombie(parent, proc.pid());
                }
            }
            Thread.sleep(PAUSE_MILLIS);
        }
        throw new AssertionError("no zombie appeared under " + parent);
    }

    /** Polls until none of {@code pids} is in the sandbox any more, and fails naming what is still there. */
    static void awaitGone(Shell shell, Set<Integer> pids, String label) throws Exception {
        List<Proc> procs = snapshot(shell);
        for (int attempt = 0; attempt < ATTEMPTS && procs.stream().anyMatch(p -> pids.contains(p.pid())); attempt++) {
            Thread.sleep(PAUSE_MILLIS);
            procs = snapshot(shell);
        }
        final Map<Integer, Proc> all = procs.stream()
                .collect(Collectors.toMap(Proc::pid, Function.identity(), (a, b) -> a));
        assertThat(procs.stream().filter(p -> pids.contains(p.pid())).map(p -> p.render(all)).toList())
                .as("%s: still in the sandbox (pid ppid pgid stat args)", label).isEmpty();
    }

    /** {@code k=v k=v …}, the last field running to the end of the line (the parent's args contain spaces). */
    private static Map<String, String> fields(String line, int count) {
        final String[] parts = line.strip().split(" ", count);
        final Map<String, String> fields = new LinkedHashMap<>();
        for (String part : parts) {
            final int eq = part.indexOf('=');
            if (eq > 0) {
                fields.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return fields;
    }
}
