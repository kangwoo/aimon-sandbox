package at.aimon.sandbox.workspace;

/**
 * A running activity heartbeat (docs/design/workspace-sandbox.md §5.3); closing it stops it.
 *
 * <p>
 * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
 * any release.
 */
public interface Heartbeat extends AutoCloseable {

    @Override
    void close();
}
