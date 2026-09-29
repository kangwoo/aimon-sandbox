package at.aimon.sandbox.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code aimon.at/*} labels and the idempotency key of a sandbox (docs/design/workspace-sandbox.md §6.3).
 *
 * <p>
 * Identifying values are never written in the clear: workspace ids ({@code ws:{sessionId}}) and keys contain
 * {@code :} and {@code /}, which label rules reject, truncating them would collide, and tenant ids are personal data
 * on shared infrastructure. {@link #h(String)} is the first 32 characters (160 bits) of the lower-case base32 of the
 * value's SHA-256; the originals stay in the workspace record.
 */
public final class SandboxLabels {

    /** Marks every sandbox this module created. */
    public static final String MANAGED = "aimon.at/managed";
    /** The deployment that owns the sandbox; reconciliation never looks past its own. */
    public static final String DEPLOYMENT = "aimon.at/deployment";
    /** {@code h(workspaceId)}. */
    public static final String WORKSPACE = "aimon.at/workspace";
    /** {@code h(key)}. */
    public static final String SANDBOX_KEY = "aimon.at/sandbox-key";
    /** The workspace's incarnation, as is. */
    public static final String INCARNATION = "aimon.at/incarnation";
    /** The slot name, as is. */
    public static final String SLOT = "aimon.at/slot";
    /** The slot generation. */
    public static final String GENERATION = "aimon.at/generation";
    /** {@code h(tenantId)}. */
    public static final String OWNER = "aimon.at/owner";

    /** The labels a returned sandbox must carry unchanged before it is used (§6.3). */
    public static final List<String> VERIFIED = List.of(WORKSPACE, SANDBOX_KEY, GENERATION, OWNER);

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final int HASH_CHARS = 32;

    private SandboxLabels() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * @param value
     *            the value to encode
     * @return the first 32 characters of the lower-case base32 of its SHA-256
     */
    public static String h(String value) {
        Objects.requireNonNull(value, "value must not be null");
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        final StringBuilder out = new StringBuilder(HASH_CHARS);
        int buffer = 0;
        int bits = 0;
        for (byte b : digest) {
            buffer = ((buffer << 8) | (b & 0xff)) & 0xffff;
            bits += 8;
            while (bits >= 5 && out.length() < HASH_CHARS) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * @param deployment
     *            the deployment name
     * @param workspaceId
     *            the workspace id
     * @param incarnation
     *            the workspace incarnation
     * @param slot
     *            the slot name
     * @param generation
     *            the slot generation
     * @return {@code "{deployment}/{workspaceId}/{incarnation}/{slot}/{generation}"}
     */
    public static String key(String deployment, String workspaceId, String incarnation, String slot, long generation) {
        return deployment + "/" + workspaceId + "/" + incarnation + "/" + slot + "/" + generation;
    }

    /**
     * The full label set of one sandbox.
     *
     * @param deployment
     *            the deployment name (already label-safe)
     * @param workspaceId
     *            the workspace id
     * @param incarnation
     *            the workspace incarnation
     * @param slot
     *            the slot name
     * @param generation
     *            the slot generation
     * @param tenantId
     *            the owner's tenant id
     * @return the labels
     */
    public static Map<String, String> labels(String deployment, String workspaceId, String incarnation, String slot,
            long generation, String tenantId) {
        final Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED, "true");
        labels.put(DEPLOYMENT, deployment);
        labels.put(WORKSPACE, h(workspaceId));
        labels.put(SANDBOX_KEY, h(key(deployment, workspaceId, incarnation, slot, generation)));
        labels.put(INCARNATION, incarnation);
        labels.put(SLOT, slot);
        labels.put(GENERATION, Long.toString(generation));
        labels.put(OWNER, h(tenantId));
        return Map.copyOf(labels);
    }

    /**
     * The filter that finds every sandbox of one workspace in one deployment.
     *
     * @param deployment
     *            the deployment name
     * @param workspaceId
     *            the workspace id
     * @return the labels to pass to {@link SandboxProvider#list}
     */
    public static Map<String, String> workspaceSelector(String deployment, String workspaceId) {
        return Map.of(MANAGED, "true", DEPLOYMENT, deployment, WORKSPACE, h(workspaceId));
    }

    /**
     * @param expected
     *            the labels the record implies
     * @param actual
     *            the labels the provider returned
     * @return the {@linkplain #VERIFIED verified} labels that differ, empty when the sandbox may be used
     */
    public static List<String> mismatches(Map<String, String> expected, Map<String, String> actual) {
        final List<String> mismatched = new ArrayList<>();
        for (String label : VERIFIED) {
            if (!Objects.equals(expected.get(label), actual.get(label))) {
                mismatched.add(label);
            }
        }
        return mismatched;
    }
}
