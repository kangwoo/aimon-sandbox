package at.aimon.sandbox.binding;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.UnannouncedReset;

/**
 * What a session's root binding read of its options record, for the environment's notice bookkeeping
 * (docs/design/workspace-sandbox-unannounced-reset.md §5.2): the record's version, the base of the environment's CAS
 * writes, and the unannounced reset it held. Set by {@link SessionOptionsBindingPolicy} from the record it reads
 * anyway; a fork's and a routine's binding have none.
 *
 * <p>
 * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
 * release.
 */
public final class SessionBookkeeping {

    private final SessionId session;
    private final OptionalLong optionsVersion;
    private final UnannouncedReset unannouncedReset;

    private SessionBookkeeping(SessionId session, OptionalLong optionsVersion, UnannouncedReset unannouncedReset) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.optionsVersion = Objects.requireNonNull(optionsVersion, "optionsVersion must not be null");
        this.unannouncedReset = unannouncedReset;
    }

    /**
     * @param session
     *            the session
     * @param record
     *            its options record as the policy read it, or empty
     * @return the bookkeeping
     */
    public static SessionBookkeeping of(SessionId session, Optional<SessionSandboxOptions> record) {
        return new SessionBookkeeping(session,
                record.map(r -> OptionalLong.of(r.version())).orElse(OptionalLong.empty()),
                record.flatMap(SessionSandboxOptions::unannouncedReset).orElse(null));
    }

    /** @return the session */
    public SessionId session() {
        return session;
    }

    /** @return the version of the options record the policy read; empty when there was none */
    public OptionalLong optionsVersion() {
        return optionsVersion;
    }

    /** @return the unannounced reset the record held */
    public Optional<UnannouncedReset> unannouncedReset() {
        return Optional.ofNullable(unannouncedReset);
    }

    @Override
    public String toString() {
        return "SessionBookkeeping{" + session.value() + ", version=" + optionsVersion
                + (unannouncedReset != null ? ", " + unannouncedReset : "") + '}';
    }
}
