package at.aimon.sandbox.environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Notices waiting for the next shell result of one environment (docs/design/workspace-sandbox.md §7). A file tool
 * cannot carry notices, so when a reset or a recreation is first observed by one, the notice is kept here and
 * reported with the next {@code Bash} result instead of being lost. Duplicates collapse.
 */
final class PendingNotices {

    private final Set<String> pending = new LinkedHashSet<>();

    synchronized void addAll(List<String> notices) {
        pending.addAll(notices);
    }

    synchronized void add(String notice) {
        pending.add(notice);
    }

    synchronized List<String> drain() {
        final List<String> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }
}
