package at.aimon.sandbox.environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * A model's command reaches the inner bash through the run file {@code run-{id}.cmd}, never as an argument: the outer
 * writes it there with the {@code printf} builtin, or — for a command too large to embed in the exec at all
 * ({@link #INLINE_COMMAND_LIMIT}) — {@code SandboxShell} uploads it through the files API and the script carries no
 * copy. Linux limits one argument to 128 KiB (MAX_ARG_STRLEN), and the exec's own script is one. Per-command
 * variables and the working directory are always embedded; {@code SandboxShell} refuses them over
 * {@link #INLINE_ENVIRONMENT_LIMIT} as a bad argument rather than letting the exec fail as an unreachable sandbox.
 *
 * <p>
 * A hook's or an isolated command ({@link #hook}, {@link #isolated}) is the <b>stream</b> family: it keeps nothing in
 * the agent-writable shell directory (kangwoo/aimon-sandbox#33). The command is the inner bash's sixth argument; a
 * small stdin is base64 in the script, piped through {@code /usr/bin/base64 -d}; the inner's stdout and stderr are the
 * exec's own, capped by {@code SandboxShell}; the trailer is {@code \n{nonce} exit=N cwd=0|1|2}; and the watchdog
 * writes no {@code .timedout} — {@code SandboxShell} decides a timeout by its own clock. Every such run gets a
 * <b>private directory</b> {@code /workspace/.aimon-shell/.run.{hex}} ({@link #PRIVATE_PREFIX}), created exclusively
 * with mode 700 and removed by the outer's epilogue and {@code EXIT} trap. It holds an uploaded stdin after its digest
 * check ({@code .stdin}), the inner's cwd flag ({@code r.cwd}) and, under {@code d/}, the files of a
 * {@code RunDirectory} — the only part a model's own command (foreground, background) gets too, when it carries one.
 * Run files come inline (base64, with their manifest) or, past {@link #INLINE_RUN_FILES_LIMIT}, from two uploads —
 * their manifest and one blob of their base64 — of which the script carries only the digests, so neither the script
 * nor the number of uploads grows with the number of files. Either way they are placed under noclobber, checked with
 * {@code sha256sum -c} against a manifest the script
 * vouches for, and counted. A digest or count mismatch exits {@link #RUN_FILES_TAMPERED_EXIT}; any other placement
 * failure {@link #SETUP_FAILED_EXIT}. Manifests are fed by the {@code printf} builtin through a pipe or read from the
 * private directory, never a here-document, whose temp file would need a writable {@code $TMPDIR}.
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
 * {@code mv} and {@code rm}, plus {@code cat}, {@code find}, {@code /usr/bin/base64} and {@code /usr/bin/sha256sum}
 * for a private directory; a {@code /proc} is used when present (the owner's start time, for cross-node takeover) and
 * not required.
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

    /** Exit code of the outer wrapper when a placed run file or uploaded stdin does not match its embedded digest. */
    static final int RUN_FILES_TAMPERED_EXIT = 73;

    /**
     * The largest run directory, as quoted base64 of all its files plus the quoted manifest, that the script embeds; a
     * larger one is uploaded as its manifest and one blob, and copied in.
     */
    static final int INLINE_RUN_FILES_LIMIT = 16 * 1024;

    /**
     * The largest whole script, in UTF-8 bytes: MAX_ARG_STRLEN (128 KiB) less a margin. The caps above sum to 112 KiB;
     * {@code SandboxShell} decides inline or upload against the assembled script, not a sum of caps.
     */
    static final int ARGUMENT_BUDGET = 120 * 1024;

    /** Where a run's private directory lives: the shell root, which file tools see read-only, plus a random suffix. */
    static final String PRIVATE_PREFIX = SHELL_ROOT + "/.run.";

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The inner bash. {@code $1} state directory, {@code $2} run-file prefix (the command is in {@code $2.cmd}),
     * {@code $3} {@code fg|bg|hook|iso}, {@code $4} root (where a new shell starts, and the fallback when the saved cwd
     * is gone), {@code $5} per-command working directory (may be empty), for {@code hook} and {@code iso} {@code $6}
     * the command itself, then {@code NAME=value} per-command variables. {@code hook} behaves as {@code bg}; it is its
     * own word only so the command comes from {@code $6}, and its {@code $2} is inside the private directory.
     * {@code iso} starts as a new shell would: it does not read the state file, so no cwd or export the session's
     * commands left behind reaches the command, and like {@code bg} it saves none. It shares the new shell's root
     * fallback on purpose: a missing root makes {@code __aimon_fallback} retry it once, then run in {@code /workspace}
     * with notice {@code 2}, which is as true for an isolated command as for a new shell. A cwd fallback writes
     * {@code 1} (ran in the root) or {@code 2} (the root is gone too: ran in {@code /workspace}) to {@code $2.cwd}.
     * When the exec environment names a CA bundle in {@code SSL_CERT_FILE} — OpenSandbox's execd does, for the egress
     * proxy that injects credentials (§12.1) — git is pointed at it too: a git built on GnuTLS (Debian's) does not read
     * that variable and would fail verification. An explicit {@code GIT_SSL_CAINFO} wins, and the export is part of the
     * base environment, so it is not saved as shell state.
     */
    static final String INNER = """
            if [ -n "${SSL_CERT_FILE-}" ] && [ -z "${GIT_SSL_CAINFO+x}" ]; then
              export GIT_SSL_CAINFO="$SSL_CERT_FILE"
            fi
            __aimon_base=$(export -p)
            __aimon_dir=$1; __aimon_run=$2; __aimon_mode=$3; __aimon_root=$4; __aimon_wd=$5
            if [ "$__aimon_mode" = iso ] || [ "$__aimon_mode" = hook ]; then __aimon_cmd=$6; shift 6
            else shift 5; IFS= read -r -d '' __aimon_cmd < "$__aimon_run.cmd"; fi
            __aimon_fallback() {
              if builtin cd -- "$__aimon_root" 2>/dev/null; then builtin printf 1 > "$__aimon_run.cwd"
              else builtin cd /workspace; builtin printf 2 > "$__aimon_run.cwd"; fi
            }
            if [ "$__aimon_mode" != iso ] && [ -r "$__aimon_dir/state" ]; then . "$__aimon_dir/state"
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
        private String privateDirectory;
        private String runVariable;
        private List<RunFile> runFiles;
        private boolean runFilesUploaded;
        private String stdinInline;
        private String stdinUploadedSha256;

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

        /**
         * The run's private directory ({@link #PRIVATE_PREFIX}{@code {hex}}): required for a hook's or an isolated
         * command, and for a model's command only with a run directory; {@code null} otherwise.
         */
        Invocation privateDirectory(String value) {
            this.privateDirectory = value;
            return this;
        }

        /**
         * A run directory to place under {@code {privateDirectory}/d} and export in {@code variable}.
         *
         * @param variable
         *            the variable that names the directory
         * @param files
         *            its files, in the order they are placed (a parent before its children)
         * @param uploaded
         *            the files are uploaded as {@code {runPrefix}.blob} ({@link #blob}) with their {@link #manifest}
         *            at {@code {runPrefix}.manifest}, not in the script, which carries only the two digests
         */
        Invocation runDirectory(String variable, List<RunFile> files, boolean uploaded) {
            this.runVariable = Objects.requireNonNull(variable);
            this.runFiles = List.copyOf(files);
            this.runFilesUploaded = uploaded;
            return this;
        }

        /** A hook's or an isolated command's stdin, base64 in the script; {@code null} for none. */
        Invocation stdinInline(String base64) {
            this.stdinInline = base64;
            return this;
        }

        /**
         * A hook's or an isolated command's stdin is at {@code {runPrefix}.in}, to be copied into the private directory
         * and checked against this digest; {@code null} for none.
         */
        Invocation stdinUploaded(String sha256) {
            this.stdinUploadedSha256 = sha256;
            return this;
        }
    }

    /** One file of a run directory: where it goes, its base64 (in the script or the uploaded blob), and its SHA-256. */
    static final class RunFile {
        private final String relPath;
        private final String base64;
        private final String sha256;

        /**
         * @param relPath
         *            the confined relative path, without a newline
         * @param base64
         *            the content as base64
         * @param sha256
         *            the content's SHA-256, lowercase hex
         */
        RunFile(String relPath, String base64, String sha256) {
            this.relPath = Objects.requireNonNull(relPath);
            this.base64 = Objects.requireNonNull(base64);
            this.sha256 = Objects.requireNonNull(sha256);
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
        final StringBuilder s = prologue(in, false);
        s.append("exec 9>\"$d/lock\"\n");
        s.append("flock -w ").append(Math.max(1, (in.lockWait.toMillis() + 999) / 1000)).append(" 9 || exit ")
                .append(LOCK_BUSY_EXIT).append('\n');
        s.append("s=-; [ -r \"/proc/$$/stat\" ] && s=$(awk '{print $22}' \"/proc/$$/stat\" 2>/dev/null)\n");
        s.append("printf '%s %s %s\\n' ").append(quote(in.nodeId)).append(" \"$$\" \"${s:--}\" > \"$d/owner\"\n");
        if (in.privateDirectory != null) {
            // Once the lock is held: the run's files exist for the run, not through the lock wait or a busy lock.
            appendPrivate(s, in);
        }
        if (in.timeout != null) {
            appendWatchdog(s, in, " 9>&-", true);
        }
        appendInner(s, in, "fg", " 9>&-");
        if (in.timeout != null) {
            appendWatchdogStop(s);
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
        final StringBuilder s = prologue(in, true);
        appendInner(s, in, "bg", "");
        return epilogue(s, in);
    }

    /**
     * The script of a hook's command: no lock, no state save — it reads the session's state as a background command
     * does — with the foreground's watchdog, so the command ends at its own timeout. The exec's backstop alone would
     * end it five seconds late: for a hook at core's default 30-second timeout or longer, the very moment core's outer
     * deadline for the hook fires. A stream script: nothing of the run lives in the shell directory.
     *
     * @param in
     *            the invocation
     * @return the script
     */
    static String hook(Invocation in) {
        return stream(in, "hook");
    }

    /**
     * The script of an isolated command ({@code ExecutionOptions.isIsolated()}, an operator's {@code hooks.json}
     * command): the hook's script, but the inner bash starts as a new shell would — in the root, with only the exec
     * environment and the per-command variables — instead of from the session's state file.
     *
     * @param in
     *            the invocation
     * @return the script
     */
    static String isolated(Invocation in) {
        return stream(in, "iso");
    }

    private static String stream(Invocation in, String mode) {
        Objects.requireNonNull(in.privateDirectory, "a stream script needs a private directory");
        final StringBuilder s = new StringBuilder();
        s.append("__aimon_inner=").append(quote(INNER)).append('\n');
        s.append("d=").append(quote(in.directory)).append('\n');
        s.append("r=").append(quote(in.runPrefix)).append('\n');
        // No mkdir of $d and no $r.cmd: nothing of this run goes under the shell directory but a transient upload.
        appendPrivate(s, in);
        if (in.timeout != null) {
            appendWatchdog(s, in, "", false);
        }
        appendInner(s, in, mode, "");
        if (in.timeout != null) {
            appendWatchdogStop(s);
        }
        s.append("c=0; [ -e \"$p/r.cwd\" ] && read -r c < \"$p/r.cwd\"\n");
        s.append("rm -rf -- \"$p\"\n");
        s.append("printf '\\n%s exit=%d cwd=%d\\n' ").append(quote(in.nonce)).append(" \"$rc\" \"$c\" >&2\n");
        return s.toString();
    }

    /**
     * Starts the watchdog: after the timeout it kills the wrapper's process group, writing {@code .timedout} first when
     * {@code marker} (a stream script's timeout is decided by the JVM's clock instead: a marker in a directory the
     * agent can write could be forged). {@code closeLockFd} keeps it from holding the shell's lock.
     */
    private static void appendWatchdog(StringBuilder s, Invocation in, String closeLockFd, boolean marker) {
        s.append("( trap 'kill \"$w\" 2>/dev/null; exit 0' TERM\n");
        s.append("  sleep ").append(seconds(in.timeout)).append(" & w=$!\n");
        s.append("  wait \"$w\"\n");
        if (marker) {
            s.append("  : > \"$r.timedout\"\n");
        }
        s.append("  kill -KILL 0 )").append(closeLockFd).append(" </dev/null >/dev/null 2>&1 &\n");
        s.append("wd=$!\n");
    }

    private static void appendWatchdogStop(StringBuilder s) {
        s.append("kill \"$wd\" 2>/dev/null\n");
        s.append("wait \"$wd\" 2>/dev/null\n");
    }

    private static StringBuilder prologue(Invocation in, boolean withPrivate) {
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
        if (withPrivate && in.privateDirectory != null) {
            appendPrivate(s, in);
        }
        return s;
    }

    /**
     * Creates the private directory {@code $p} — exclusively, mode 700, removed by the {@code EXIT} trap — and places
     * what goes in it: the run directory under {@code $p/d}, an uploaded stdin as {@code $p/.stdin}. Placement runs
     * under {@code umask 077} and noclobber, both undone before the inner bash starts, and before the watchdog, so it
     * does not count against the command's timeout.
     */
    private static void appendPrivate(StringBuilder s, Invocation in) {
        s.append("p=").append(quote(in.privateDirectory)).append('\n');
        // The shared root, as `mkdir -p "$d"` re-creates it for a model's command: one may have removed it.
        s.append("mkdir -p -- ").append(quote(SHELL_ROOT)).append(" || exit ").append(SETUP_FAILED_EXIT).append('\n');
        s.append("m=$(umask); umask 077\n");
        // No -p: an existing directory, planted or not, is a failure.
        s.append("mkdir -m 700 -- \"$p\" || exit ").append(SETUP_FAILED_EXIT).append('\n');
        s.append("trap 'rm -rf -- \"$p\"' EXIT\n");
        s.append("set -C\n");
        if (in.runFiles != null) {
            appendRunFiles(s, in);
        }
        if (in.stdinUploadedSha256 != null) {
            s.append("cat -- \"$r.in\" > \"$p/.stdin\" || exit ").append(SETUP_FAILED_EXIT).append('\n');
            s.append("rm -f -- \"$r.in\"\n");
            appendCheck(s, "\"$p\"", in.stdinUploadedSha256 + "  .stdin\n");
        }
        s.append("set +C\n");
        s.append("umask \"$m\"\n");
    }

    private static void appendRunFiles(StringBuilder s, Invocation in) {
        s.append("mkdir -m 700 -- \"$p/d\" || exit ").append(SETUP_FAILED_EXIT).append('\n');
        if (in.runFilesUploaded && !in.runFiles.isEmpty()) {
            appendUploadedRunFiles(s, in);
        } else {
            appendInlineRunFiles(s, in);
        }
        // The digests cover the listed files; the count catches one added beside them.
        s.append("l=$(cd -- \"$p/d\" && find . ! -type d) || exit ").append(SETUP_FAILED_EXIT).append('\n');
        s.append("n=0; [ -z \"$l\" ] || n=$(printf '%s\\n' \"$l\" | wc -l)\n");
        s.append("[ $n -eq ").append(in.runFiles.size()).append(" ] || exit ").append(RUN_FILES_TAMPERED_EXIT)
                .append('\n');
    }

    /**
     * Uploaded run files: two uploads whatever the number of files — the manifest, and one blob holding every file's
     * base64 on a line of its own, in the manifest's order ({@link #blob}) — so the uploads and the script stay the
     * same size whatever the number of files. Both are copied into {@code $p} and checked against the digests the
     * script carries before a byte of either is used; from then on the manifest's lines name the files and the blob's
     * lines are their content. The placed files are then checked against the manifest as inline ones are.
     */
    private static void appendUploadedRunFiles(StringBuilder s, Invocation in) {
        s.append("cat -- \"$r.manifest\" > \"$p/.manifest\" && cat -- \"$r.blob\" > \"$p/.blob\" || exit ")
                .append(SETUP_FAILED_EXIT).append('\n');
        s.append("rm -f -- \"$r.manifest\" \"$r.blob\"\n");
        appendCheck(s, "\"$p\"", sha256Hex(manifest(in.runFiles).getBytes(StandardCharsets.UTF_8)) + "  .manifest\n"
                + sha256Hex(blob(in.runFiles)) + "  .blob\n");
        s.append("exec 4<\"$p/.blob\" || exit ").append(SETUP_FAILED_EXIT).append('\n');
        // A line is 64 hex digits, two spaces and the path: the path starts at offset 66.
        s.append("while IFS= read -r l; do f=${l:66}\n");
        s.append("  { case $f in */*) mkdir -p -- \"$p/d/${f%/*}\";; esac &&\n");
        s.append("    IFS= read -r b <&4 && printf '%s' \"$b\" | /usr/bin/base64 -d > \"$p/d/$f\"; } \\\n");
        s.append("    || { printf 'cannot place %s\\n' \"$f\" >&2; exit ").append(SETUP_FAILED_EXIT).append("; }\n");
        s.append("done < \"$p/.manifest\"\n");
        s.append("exec 4<&-; b=; rm -f -- \"$p/.blob\"\n");
        appendCheck(s, "\"$p/d\"", null);
    }

    /**
     * @param files
     *            a run directory's files, with their base64
     * @return the upload that carries them: each file's base64 and a newline, in order
     */
    static byte[] blob(List<RunFile> files) {
        final StringBuilder blob = new StringBuilder();
        for (RunFile file : files) {
            blob.append(file.base64).append('\n');
        }
        return blob.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void appendInlineRunFiles(StringBuilder s, Invocation in) {
        for (RunFile file : in.runFiles) {
            final String target = "\"$p/d/\"" + quote(file.relPath);
            final int slash = file.relPath.lastIndexOf('/');
            if (slash > 0) {
                s.append("mkdir -p -- \"$p/d/\"").append(quote(file.relPath.substring(0, slash))).append(" && ");
            }
            s.append("printf '%s' ").append(quote(file.base64)).append(" | /usr/bin/base64 -d > ").append(target);
            s.append(" \\\n  || { printf 'cannot place %s\\n' ").append(quote(file.relPath)).append(" >&2; exit ")
                    .append(SETUP_FAILED_EXIT).append("; }\n");
        }
        if (!in.runFiles.isEmpty()) {
            // GNU sha256sum -c refuses an empty manifest; the count still holds an empty directory to empty.
            appendCheck(s, "\"$p/d\"", manifest(in.runFiles));
        }
    }

    /**
     * @param files
     *            a run directory's files
     * @return their {@code sha256sum -c} manifest: one {@code sha256  relPath} line each, in order
     */
    static String manifest(List<RunFile> files) {
        final StringBuilder manifest = new StringBuilder();
        for (RunFile file : files) {
            manifest.append(file.sha256).append("  ").append(file.relPath).append('\n');
        }
        return manifest.toString();
    }

    /**
     * Checks the files under {@code directory} against {@code manifest} ({@code sha256  relPath} lines). Status 1 —
     * a digest that does not match, or a listed file that is gone — exits {@link #RUN_FILES_TAMPERED_EXIT}; anything
     * else (a missing binary, a {@code cd} that fails) {@link #SETUP_FAILED_EXIT}, so an environment fault is never
     * reported as tampering. The explicit {@code -} is for the BSD {@code sha256sum} of a macOS test host, which
     * does not read a manifest from stdin without it. A {@code null} manifest reads the checked {@code $p/.manifest}.
     */
    private static void appendCheck(StringBuilder s, String directory, String manifest) {
        s.append("( cd -- ").append(directory).append(" || exit ").append(SETUP_FAILED_EXIT).append('\n');
        if (manifest != null) {
            s.append("  printf '%s' ").append(quote(manifest))
                    .append(" | PATH=/usr/bin:/bin /usr/bin/sha256sum -c --strict - >/dev/null 2>&1\n");
        } else {
            s.append("  PATH=/usr/bin:/bin /usr/bin/sha256sum -c --strict - <\"$p/.manifest\" >/dev/null 2>&1\n");
        }
        s.append("  s=$?; [ \"$s\" = 0 ] && exit 0; [ \"$s\" = 1 ] && exit ").append(RUN_FILES_TAMPERED_EXIT)
                .append("; exit ").append(SETUP_FAILED_EXIT).append(" ) || exit $?\n");
    }

    private static void appendInner(StringBuilder s, Invocation in, String mode, String closeLockFd) {
        final boolean stream = "hook".equals(mode) || "iso".equals(mode);
        if (stream && in.stdinInline != null) {
            // The decoder's complaint about a hook that never read its stdin is not the hook's stderr.
            s.append("printf '%s' ").append(quote(in.stdinInline)).append(" | /usr/bin/base64 -d 2>/dev/null | ");
        }
        s.append("/bin/bash -c \"$__aimon_inner\" aimon \"$d\" ").append(stream ? "\"$p/r\" " : "\"$r\" ").append(mode)
                .append(' ').append(quote(in.root)).append(' ')
                .append(quote(in.workingDirectory == null ? "" : in.workingDirectory));
        if (stream) {
            s.append(' ').append(quote(in.command));
        }
        for (Map.Entry<String, String> variable : in.environment.entrySet()) {
            s.append(' ').append(quote(variable.getKey() + "=" + variable.getValue()));
        }
        if (in.runVariable != null) {
            // Last, so it wins over the same name among the per-command variables: the inner exports them in order.
            s.append(' ').append(quote(in.runVariable)).append("\"=$p/d\"");
        }
        s.append(closeLockFd);
        if (stream) {
            if (in.redirectErrorStream) {
                s.append(" 2>&1");
            }
            if (in.stdinUploadedSha256 != null) {
                s.append(" <\"$p/.stdin\"");
            } else if (in.stdinInline == null) {
                s.append(" </dev/null");
            }
            s.append('\n');
        } else {
            if (in.redirectErrorStream) {
                s.append(" >\"$r.out\" 2>&1");
            } else {
                s.append(" >\"$r.out\" 2>\"$r.err\"");
            }
            s.append(" <").append(in.stdinPath == null ? "/dev/null" : quote(in.stdinPath)).append('\n');
        }
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
        if (in.privateDirectory != null) {
            s.append("rm -rf -- \"$p\"\n");
        }
        s.append("printf '\\n%s exit=%d out=%d err=%d cwd=%d\\n' ").append(quote(in.nonce))
                .append(" \"$rc\" \"$o\" \"$e\" \"$c\" >&2\n");
        return s.toString();
    }

    /**
     * @param script
     *            a wrapper script
     * @return its length as the exec argument, in UTF-8 bytes
     */
    static long scriptBytes(String script) {
        return script.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * @param content
     *            some bytes
     * @return their SHA-256, lowercase hex: the {@code sha256sum} format
     */
    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
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
