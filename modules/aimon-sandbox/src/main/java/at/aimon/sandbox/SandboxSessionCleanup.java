package at.aimon.sandbox;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionDeletedEvent;
import at.aimon.core.agent.session.SessionLifecycleListener;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;

/**
 * Drops this module's per-session state when core's session router deletes a session (kangwoo/aimon-core#253,
 * docs/design/workspace-sandbox-core-033.md §6.3): the session's options record, and its derived workspace
 * {@code ws:{sessionId}}, which is marked for removal at once and closed and deleted in the background. Get it from
 * {@link WorkspaceSandbox#sessionLifecycleListener()} and register it once — {@code SessionRouterBuilder
 * .lifecycleListener}, {@code SessionSpec.Builder.lifecycleListener}, or a Spring bean.
 *
 * <p>
 * Core calls it on the deleting node only, under the session's lease, at most once. Everything it drops lives in the
 * shared stores, so one call is enough and no other node needs to hear of it; what a lost call leaves behind is
 * removed by the janitor. It runs no provider call on the calling thread, and never throws.
 */
public final class SandboxSessionCleanup implements SessionLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(SandboxSessionCleanup.class);

    private final SandboxWorkspaceManager manager;

    SandboxSessionCleanup(SandboxWorkspaceManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public void onDeleted(SessionDeletedEvent event) {
        try {
            manager.sessionDeleted(event.getSessionId(), event.getOwner());
        } catch (RuntimeException e) {
            // Core contains a listener's failure too; the delete must not depend on that.
            log.warn("Cleaning up the sandbox state of deleted session {} failed; the janitor finishes it: {}",
                    event.getSessionId().value(), e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "SandboxSessionCleanup";
    }
}
