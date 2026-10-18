package at.aimon.sandbox.binding;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;

/**
 * Who owns a session (docs/design/workspace-sandbox.md §8.3). With it, the owner of a main turn's workspace is the
 * session's owner rather than whoever calls first, and only that owner sets the session's options. Required, in some
 * form, when {@code require-principal} is on.
 *
 * <p>
 * Core 0.3.3 records the owner itself (kangwoo/aimon-core#253): {@link #recorded(SessionRecordStore)} reads it, and
 * {@code WorkspaceSandbox.Builder.sessionRecords(...)} wires that without a hand-written lookup. Core records the owner
 * only when the host sets {@code SubmitRequest.owner(...)} on the submit that provisions the session's record; a
 * session provisioned without one answers empty. A host whose older sessions have no recorded owner keeps its own
 * lookup as the fallback ({@link #orElse}) until those sessions are gone (docs/design/workspace-sandbox-core-033.md
 * §5.2).
 *
 * <p>
 * The owner is compared with the executing principal by type and id (§8.3): return the principal as the runtime
 * presents it. A lookup that answers {@code Principal.user(id)} for a session whose turns run as a GROUP or SERVICE
 * principal with that id makes every turn "not permitted" under {@code workspace-access: principal}.
 */
@FunctionalInterface
public interface SessionOwnerLookup {

    /**
     * @param sessionId
     *            the session
     * @return its owner, or empty when unknown (the binding is then rejected)
     */
    Optional<Principal> ownerOf(SessionId sessionId);

    /**
     * The owner core recorded for the session, read with {@code SessionRecordStore.loadOwner} (kangwoo/aimon-core#262):
     * the owner alone, not the record and its transcript. A store failure propagates.
     *
     * @param records
     *            core's session store
     * @return a lookup answering the recorded owner, empty for a session without one or without a record
     */
    static SessionOwnerLookup recorded(SessionRecordStore records) {
        Objects.requireNonNull(records, "records must not be null");
        return recorded(() -> records);
    }

    /**
     * {@link #recorded(SessionRecordStore)} with the store resolved at each call: for an assembly that builds core's
     * session store after the sandbox.
     *
     * @param records
     *            supplies core's session store
     * @return a lookup answering the recorded owner
     */
    static SessionOwnerLookup recorded(Supplier<? extends SessionRecordStore> records) {
        Objects.requireNonNull(records, "records must not be null");
        return sessionId -> Objects.requireNonNull(records.get(), "the session record store is not available yet")
                .loadOwner(sessionId);
    }

    /**
     * @param fallback
     *            asked only when this lookup answers empty
     * @return a lookup answering this one's owner, else the fallback's
     */
    default SessionOwnerLookup orElse(SessionOwnerLookup fallback) {
        Objects.requireNonNull(fallback, "fallback must not be null");
        return sessionId -> {
            final Optional<Principal> owner = ownerOf(sessionId);
            return owner.isPresent() ? owner : fallback.ownerOf(sessionId);
        };
    }
}
