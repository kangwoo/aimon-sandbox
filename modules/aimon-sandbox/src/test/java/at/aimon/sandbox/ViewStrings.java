package at.aimon.sandbox;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Walks a view's fields the way a reflective serializer would, so a test can assert what it never carries. */
public final class ViewStrings {

    private ViewStrings() {
    }

    /** Every string reachable from {@code root} through its fields, as a serializer would see them. */
    public static List<String> of(Object root) {
        final List<String> out = new ArrayList<>();
        walk(root, out, new IdentityHashMap<>());
        return out;
    }

    private static void walk(Object value, List<String> out, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, true) != null) {
            return;
        }
        if (value instanceof Enum<?> || value.getClass().getName().startsWith("java.")
                && !(value instanceof Collection<?>) && !(value instanceof Map<?, ?>)) {
            out.add(value.toString());
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> walk(item, out, seen));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                walk(k, out, seen);
                walk(v, out, seen);
            });
            return;
        }
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    walk(field.get(value), out, seen);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
