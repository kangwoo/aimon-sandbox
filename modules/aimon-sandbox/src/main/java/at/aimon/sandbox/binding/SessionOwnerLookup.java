package at.aimon.sandbox.binding;

import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * Who owns a session (docs/design/workspace-sandbox.md §8.3). Core's session store records no owner, so the
 * application answers — the user who created a session is application data. With it, the owner of a main turn's
 * workspace is the session's owner rather than whoever calls first. Required when {@code require-principal} is on.
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
}
