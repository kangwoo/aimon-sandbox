package at.aimon.sandbox.workspace;

/** A running activity heartbeat (docs/design/workspace-sandbox.md §5.3); closing it stops it. */
public interface Heartbeat extends AutoCloseable {

    @Override
    void close();
}
