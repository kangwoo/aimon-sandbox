package at.aimon.sandbox.binding;

import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * Who owns a session (docs/design/workspace-sandbox.md §8.3). Core's session store records no owner, so the
 * application answers — the user who created a session is application data. With it, the owner of a main turn's
 * workspace is the session's owner rather than whoever calls first. Required when {@code require-principal} is on.
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
