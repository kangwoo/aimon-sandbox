package at.aimon.sandbox.environment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds the bash script that runs one model command with persistent shell state (docs/design/workspace-sandbox.md
 * §9). One {@code run} per command, in {@code /workspace} (it exists while the sandbox does: a command that removes
 * the working root does not make the next exec fail); the script is two bash processes:
 *
 * <ul>
 * <li>the <b>outer</b> wrapper owns the exec's stdout/stderr (never redirected), the {@code flock} on the shell's lock
 * file, a watchdog that enforces the command timeout from the moment the lock is held, and the trailer it prints last:
 * {@code \n{nonce} exit=N out=BYTES err=BYTES cwd=0|1|2} on stderr, after removing the run files (only a run
 * that ends without a trailer — a timeout, a wrapper failure — leaves them to {@code SandboxShell});</li>
 * <li>the <b>inner</b> bash restores {@code cwd} and exported variables from the state file (a new shell starts in the
 * root), {@code eval}s the command, and on exit saves {@code cwd} plus the exported variables that differ from its
 * starting environment. Its stdout and stderr go to per-run files, so a backgrounded descendant ({@code npm run dev &})
 * holds a file, not the exec pipe, and the next command does not wait for it; {@code head -c} truncates inside the
 * sandbox.</li>
 * </ul>
 *
 * <p>
 * Every dynamic value is single-quoted ({@code '} as {@code '\''}), the model's command included: it is data that the
 * inner bash {@code eval}s, never part of the script's syntax, so a trailing comment, a heredoc or an unbalanced quote
 * is the command's own business. Whatever the command does to end its shell ({@code exit 3}, {@code set -e}) ends
 * only the inner bash; the outer still reports its output and exit code.
 *
 * <p>
 * The command reaches the inner bash through the run file {@code run-{id}.cmd}, never as an argument: the outer writes
 * it there with the {@code printf} builtin, or — for a command too large to embed in the exec at all
 * ({@link #INLINE_COMMAND_LIMIT}) — {@code SandboxShell} uploads it through the files API and the script carries no
 * copy. Linux limits one argument to 128 KiB (MAX_ARG_STRLEN), and the exec's own script is one. Per-command
 * variables and the working directory are always embedded; {@code SandboxShell} refuses them over
 * {@link #INLINE_ENVIRONMENT_LIMIT} as a bad argument rather than letting the exec fail as an unreachable sandbox.
 *
 * <p>
 * The inner bash keeps its own state in {@code readonly} variables named {@code __aimon_*}; <b>that prefix is
 * reserved</b>. Variables with it are neither saved nor restored. The save calls builtins and {@code command -p mv},
 * so a command's {@code PATH} (even unset) or its functions named like a tool do not break it; a command that
 * redefines {@code __aimon_save}, {@code builtin} or {@code command}, or resets the traps, can still defeat the save —
 * shell state is best-effort (§9). Under {@code set -v} the one-line EXIT trap is echoed to the command's stderr.
 *
 * <p>
 * The timeout or cancellation kill is best-effort: the watchdog — or, for a cancellation, the provider's kill — ends
 * the wrapper's process group, and a job the command put in a group of its own ({@code set -m}, {@code setsid})
 * outlives it until the sandbox goes. Killing by session or cgroup needs the exec server's help, which it does not
 * offer (docs/design/opensandbox-spike.md §3).
 *
 * <p>
 * A command that finishes just as its timeout expires can still be reported as timed out: when the watchdog's sleep
 * ends in the few instructions between the inner bash returning and the outer stopping the watchdog, the watchdog
 * writes {@code .timedout} and kills the group, the outer included, before the trailer. The inner EXIT trap has
 * already saved the state by then, so the report's "cd/export were not applied" may be wrong for that run; the window
 * is the outer's two builtins, and the command did run for its whole timeout.
 *
 * <p>
 * The script is bash 3.2 compatible and needs {@code flock}, {@code head}, {@code wc}, {@code sleep}, {@code kill},
 * {@code mv} and {@code rm}; a {@code /proc} is used when present (the owner's start time, for cross-node takeover)
 * and not required.
 */
final class ShellWrapper {

    /** Where shell state directories live. */
    static final String SHELL_ROOT = "/workspace/.aimon-shell";

    /** The exec's working directory: the mount the seed guarantees, which a command cannot remove. */
    static final String EXEC_DIRECTORY = "/workspace";

    /** Exit code of the outer wrapper when the in-sandbox lock stayed busy for the lock wait. */
    static final int LOCK_BUSY_EXIT = 75;

    /** Exit code of the outer wrapper when it could not create the state directory. */
    static final int SETUP_FAILED_EXIT = 70;

    /** Exit code of the outer wrapper when the command removed its run files, so its output is gone. */
    static final int OUTPUT_LOST_EXIT = 71;

    /**
     * The largest command, as quoted into the script, that the script embeds; a larger one is uploaded as the run
     * file. Well under MAX_ARG_STRLEN (128 KiB) with the rest of the script.
     */
    static final int INLINE_COMMAND_LIMIT = 64 * 1024;

    /**
     * The largest per-command environment plus working directory, as quoted into the script. They always travel in
     * the script, so with {@link #INLINE_COMMAND_LIMIT} this keeps the whole exec argument under MAX_ARG_STRLEN.
     */
    static final int INLINE_ENVIRONMENT_LIMIT = 32 * 1024;

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The inner bash. {@code $1} state directory, {@code $2} run-file prefix (the command is in {@code $2.cmd}),
     * {@code $3} {@code fg|bg}, {@code $4} root (where a new shell starts, and the fallback when the saved cwd is
     * gone), {@code $5} per-command working directory (may be empty), then {@code NAME=value} per-command variables.
     * A cwd fallback writes {@code 1} (ran in the root) or {@code 2} (the root is gone too: ran in {@code /workspace})
     * to {@code $2.cwd}. When the exec environment names a CA bundle in {@code SSL_CERT_FILE} — OpenSandbox's execd
     * does,
     * for the egress proxy that injects credentials (§12.1) — git is pointed at it too: a git built on GnuTLS
     * (Debian's)
     * does not read that variable and would fail verification. An explicit {@code GIT_SSL_CAINFO} wins, and the export
     * is part of the base environment, so it is not saved as shell state.
     */
    static final String INNER = """
            if [ -n "${SSL_CERT_FILE-}" ] && [ -z "${GIT_SSL_CAINFO+x}" ]; then
              export GIT_SSL_CAINFO="$SSL_CERT_FILE"
            fi
            __aimon_base=$(export -p)
            __aimon_dir=$1; __aimon_run=$2; __aimon_mode=$3; __aimon_root=$4; __aimon_wd=$5
            shift 5
            IFS= read -r -d '' __aimon_cmd < "$__aimon_run.cmd"
            __aimon_fallback() {
              if builtin cd -- "$__aimon_root" 2>/dev/null; then builtin printf 1 > "$__aimon_run.cwd"
              else builtin cd /workspace; builtin printf 2 > "$__aimon_run.cwd"; fi
            }
            if [ -r "$__aimon_dir/state" ]; then . "$__aimon_dir/state"
            else builtin cd -- "$__aimon_root" 2>/dev/null || __aimon_fallback; fi
            readonly __aimon_dir __aimon_run __aimon_base __aimon_mode __aimon_root __aimon_wd
            __aimon_nosave=0
            __aimon_save() {
              set +euxvT
              trap - DEBUG RETURN
              unset -f builtin command 2>/dev/null
              local IFS=$' \\t\\n'
              __aimon_t="$__aimon_dir/state.$$"
              {
                builtin printf 'cd -- %q 2>/dev/null || __aimon_fallback\\n' "$PWD"
                for __aimon_n in $(builtin compgen -e); do
                  case "$__aimon_n" in __aimon_*|PWD|OLDPWD|SHLVL|_) continue;; esac
                  __aimon_d=$(builtin declare -p "$__aimon_n" 2>/dev/null) || continue
                  case "
            $__aimon_base
            " in *"
            $__aimon_d
            "*) continue;; esac
                  builtin printf 'export %s=%q\\n' "$__aimon_n" "${!__aimon_n}"
                done
              } > "$__aimon_t" && builtin command -p mv -f "$__aimon_t" "$__aimon_dir/state" \\
                || builtin command -p rm -f "$__aimon_t"
            }
            trap '__aimon_nosave=1; exit 143' TERM
            if [ "$__aimon_mode" = fg ]; then
              trap '{ set +xv; trap - DEBUG RETURN; } 2>/dev/null; [ "$__aimon_nosave" = 1 ] || __aimon_save' EXIT
            fi
            if [ -n "$__aimon_wd" ] || [ $# -gt 0 ]; then
              ( if [ -n "$__aimon_wd" ]; then cd -- "$__aimon_wd" || exit 1; fi
                for __aimon_kv in "$@"; do export "$__aimon_kv"; done
                eval "$__aimon_cmd" )
            else
              eval "$__aimon_cmd"
            fi
            """;

    private ShellWrapper() {
    }

    /**
     * @param directoryName
     *            the shell key's hashed directory name
     * @return the shell's state directory
     */
    static String directory(String directoryName) {
        return SHELL_ROOT + "/" + directoryName;
    }

    /**
     * @param command
     *            the model's command
     * @return whether it is small enough to embed in the script; a larger one is uploaded as the run file
     */
    static boolean embeddable(String command) {
        return quote(command).getBytes(StandardCharsets.UTF_8).length <= INLINE_COMMAND_LIMIT;
    }

    /**
     * @param environment
     *            per-command variables
     * @param workingDirectory
     *            the per-command working directory, or {@code null}
     * @return how many bytes they take in the script, quoted
     */
    static long inlineSize(Map<String, String> environment, String workingDirectory) {
        long size = workingDirectory == null ? 0 : quote(workingDirectory).getBytes(StandardCharsets.UTF_8).length;
        for (Map.Entry<String, String> variable : environment.entrySet()) {
            // As appendInner writes it: a space, then the quoted NAME=value.
            size += 1 + quote(variable.getKey() + "=" + variable.getValue()).getBytes(StandardCharsets.UTF_8).length;
        }
        return size;
    }

    /** Everything one invocation of the wrapper needs. */
    static final class Invocation {
        private String command;
        private boolean commandUploaded;
        private String directory;
        private String runPrefix;
        private String root;
        private String nodeId;
        private String nonce;
        private Duration lockWait = Duration.ofSeconds(10);
        private Duration timeout;
        private long maxBytes;
        private boolean redirectErrorStream;
        private String stdinPath;
        private String workingDirectory;
        private Map<String, String> environment = Map.of();

        Invocation command(String value) {
            this.command = value;
            return this;
        }

        /** The command is already in the run file {@code {runPrefix}.cmd}: the script carries no copy. */
        Invocation commandUploaded(boolean value) {
            this.commandUploaded = value;
            return this;
        }

        Invocation directory(String value) {
            this.directory = value;
            return this;
        }

        Invocation runPrefix(String value) {
            this.runPrefix = value;
            return this;
        }

        Invocation root(String value) {
            this.root = value;
            return this;
        }

        Invocation nodeId(String value) {
            this.nodeId = value;
            return this;
        }

        Invocation nonce(String value) {
            this.nonce = value;
            return this;
        }

        Invocation lockWait(Duration value) {
            this.lockWait = value;
            return this;
        }

        /** The command timeout, or {@code null} for none: no watchdog, only the exec's own backstop. */
        Invocation timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        Invocation maxBytes(long value) {
            this.maxBytes = value;
            return this;
        }

        Invocation redirectErrorStream(boolean value) {
            this.redirectErrorStream = value;
            return this;
        }

        Invocation stdinPath(String value) {
            this.stdinPath = value;
            return this;
        }

        Invocation workingDirectory(String value) {
            this.workingDirectory = value;
            return this;
        }

        Invocation environment(Map<String, String> value) {
            this.environment = Objects.requireNonNull(value);
            return this;
        }
    }

    /**
     * The foreground script: lock, owner record, watchdog (unless there is no timeout), inner bash, trailer.
     *
     * @param in
     *            the invocation
     * @return the script
     */
    static String foreground(Invocation in) {
        final StringBuilder s = prologue(in);
        s.append("exec 9>\"$d/lock\"\n");
        s.append("flock -w ").append(Math.max(1, (in.lockWait.toMillis() + 999) / 1000)).append(" 9 || exit ")
                .append(LOCK_BUSY_EXIT).append('\n');
        s.append("s=-; [ -r \"/proc/$$/stat\" ] && s=$(awk '{print $22}' \"/proc/$$/stat\" 2>/dev/null)\n");
        s.append("printf '%s %s %s\\n' ").append(quote(in.nodeId)).append(" \"$$\" \"${s:--}\" > \"$d/owner\"\n");
        if (in.timeout != null) {
            s.append("( trap 'kill \"$w\" 2>/dev/null; exit 0' TERM\n");
            s.append("  sleep ").append(seconds(in.timeout)).append(" & w=$!\n");
            s.append("  wait \"$w\"\n");
            s.append("  : > \"$r.timedout\"\n");
            s.append("  kill -KILL 0 ) 9>&- </dev/null >/dev/null 2>&1 &\n");
            s.append("wd=$!\n");
        }
        appendInner(s, in, "fg", " 9>&-");
        if (in.timeout != null) {
            s.append("kill \"$wd\" 2>/dev/null\n");
            s.append("wait \"$wd\" 2>/dev/null\n");
        }
        return epilogue(s, in);
    }

    /**
     * The background script: no lock, no watchdog (the JVM's own timeout applies), no state save.
     *
     * @param in
     *            the invocation
     * @return the script
     */
    static String background(Invocation in) {
        final StringBuilder s = prologue(in);
        appendInner(s, in, "bg", "");
        return epilogue(s, in);
    }

    private static StringBuilder prologue(Invocation in) {
        final StringBuilder s = new StringBuilder();
        s.append("__aimon_inner=").append(quote(INNER)).append('\n');
        s.append("d=").append(quote(in.directory)).append('\n');
        s.append("r=").append(quote(in.runPrefix)).append('\n');
        s.append("mkdir -p \"$d\" || exit ").append(SETUP_FAILED_EXIT).append('\n');
        if (!in.commandUploaded) {
            // printf is a builtin: the command is written without passing through any argv.
            s.append("printf '%s' ").append(quote(in.command)).append(" > \"$r.cmd\" || exit ")
                    .append(SETUP_FAILED_EXIT).append('\n');
        }
        return s;
    }

    private static void appendInner(StringBuilder s, Invocation in, String mode, String closeLockFd) {
        s.append("/bin/bash -c \"$__aimon_inner\" aimon \"$d\" \"$r\" ").append(mode).append(' ').append(quote(in.root))
                .append(' ').append(quote(in.workingDirectory == null ? "" : in.workingDirectory));
        for (Map.Entry<String, String> variable : in.environment.entrySet()) {
            s.append(' ').append(quote(variable.getKey() + "=" + variable.getValue()));
        }
        s.append(closeLockFd);
        if (in.redirectErrorStream) {
            s.append(" >\"$r.out\" 2>&1");
        } else {
            s.append(" >\"$r.out\" 2>\"$r.err\"");
        }
        s.append(" <").append(in.stdinPath == null ? "/dev/null" : quote(in.stdinPath)).append('\n');
        s.append("rc=$?\n");
    }

    private static String epilogue(StringBuilder s, Invocation in) {
        // Without its run files the command's output is gone: say so, rather than report an empty success.
        s.append("[ -e \"$r.out\" ] || { printf 'the command removed %s, so its output is lost (exit %s)\\n' ")
                .append("\"$d\" \"$rc\" >&2; exit ").append(OUTPUT_LOST_EXIT).append("; }\n");
        s.append(": >>\"$r.err\"\n");
        s.append("c=0; [ -e \"$r.cwd\" ] && read -r c < \"$r.cwd\"\n");
        s.append("head -c ").append(in.maxBytes).append(" \"$r.out\"\n");
        s.append("head -c ").append(in.maxBytes).append(" \"$r.err\" >&2\n");
        s.append("o=$(wc -c < \"$r.out\"); e=$(wc -c < \"$r.err\")\n");
        // Nothing reads the run files once the trailer is out: remove them here, not by per-file calls from the JVM.
        s.append("rm -f \"$r.out\" \"$r.err\" \"$r.in\" \"$r.cmd\" \"$r.cwd\"")
                .append(in.timeout != null ? " \"$r.timedout\"" : "").append('\n');
        s.append("printf '\\n%s exit=%d out=%d err=%d cwd=%d\\n' ").append(quote(in.nonce))
                .append(" \"$rc\" \"$o\" \"$e\" \"$c\" >&2\n");
        return s.toString();
    }

    /**
     * @param names
     *            per-command variable names
     * @return the names that are not valid shell identifiers
     */
    static List<String> invalidNames(Iterable<String> names) {
        final List<String> invalid = new ArrayList<>();
        for (String name : names) {
            if (!ENV_NAME.matcher(name).matches()) {
                invalid.add(name);
            }
        }
        return invalid;
    }

    /** {@code '…'} with every {@code '} as {@code '\''}: total for any string without NUL. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String seconds(Duration timeout) {
        final long millis = Math.max(1, timeout.toMillis());
        return (millis / 1000) + "." + String.format(Locale.ROOT, "%03d", millis % 1000);
    }
}
