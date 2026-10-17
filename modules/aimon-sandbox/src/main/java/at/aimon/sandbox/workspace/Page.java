package at.aimon.sandbox.workspace;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One keyset page of a listing (docs/design/workspace-sandbox-management.md §5.4): the items, and the key to pass as
 * {@code after} for the next page — absent on the last one.
 *
 * @param <T>
 *            the item type
 * @param <K>
 *            the key type
 */
public final class Page<T, K> {

    private final List<T> items;
    private final K next;

    private Page(List<T> items, K next) {
        this.items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        this.next = next;
    }

    /**
     * @param items
     *            the items
     * @param next
     *            the key of the next page, or {@code null} on the last one
     * @param <T>
     *            the item type
     * @param <K>
     *            the key type
     * @return the page
     */
    public static <T, K> Page<T, K> of(List<T> items, K next) {
        return new Page<>(items, next);
    }

    /** @return the items, in key order */
    public List<T> items() {
        return items;
    }

    /** @return the {@code after} of the next page; empty on the last one */
    public Optional<K> next() {
        return Optional.ofNullable(next);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Page<?, ?> that && items.equals(that.items) && Objects.equals(next, that.next);
    }

    @Override
    public int hashCode() {
        return Objects.hash(items, next);
    }

    @Override
    public String toString() {
        return "Page{" + items.size() + " items" + (next != null ? ", next=" + next : "") + '}';
    }
}
