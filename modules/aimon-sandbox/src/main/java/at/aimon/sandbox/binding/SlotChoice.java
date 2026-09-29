package at.aimon.sandbox.binding;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The slot (and required profile) a fork runs in (docs/design/workspace-sandbox.md §8.1). Workspace, owner and root
 * are not part of it: the environment provider forces those from the parent, so a policy cannot send a fork elsewhere.
 */
public final class SlotChoice {

    /** The definition attribute naming a slot. */
    public static final String SLOT_ATTRIBUTE = "sandbox.slot";

    /** The definition attribute naming a required profile. */
    public static final String PROFILE_ATTRIBUTE = "sandbox.profile";

    private static final Pattern SLOT_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,30}$");

    private final String slot;
    private final String requiredProfile;

    private SlotChoice(String slot, String requiredProfile) {
        this.slot = requireSlotName(slot);
        this.requiredProfile = requiredProfile;
    }

    /**
     * @param slot
     *            the slot
     * @param requiredProfile
     *            the required profile, or {@code null}
     * @return the choice
     */
    public static SlotChoice of(String slot, String requiredProfile) {
        return new SlotChoice(slot, requiredProfile);
    }

    /**
     * The default fork rule (§8.2): {@code sandbox.slot} when the fork's definition sets it, else the parent's slot;
     * {@code sandbox.profile} when set, else — staying in the parent's slot — the parent's requirement.
     *
     * @param attributes
     *            the fork definition's attributes
     * @param parent
     *            the parent's binding
     * @return the choice
     */
    public static SlotChoice fromAttributes(Map<String, String> attributes, SandboxBinding parent) {
        final String slot = attributes.getOrDefault(SLOT_ATTRIBUTE, parent.slot());
        final String profile = attributes.get(PROFILE_ATTRIBUTE);
        if (profile != null) {
            return new SlotChoice(slot, profile);
        }
        return new SlotChoice(slot, slot.equals(parent.slot()) ? parent.requiredProfile().orElse(null) : null);
    }

    /**
     * @param slot
     *            a slot name
     * @return the name, when it matches {@code ^[a-z][a-z0-9-]{0,30}$} (§3.1)
     * @throws BindingRejectedException
     *             otherwise
     */
    static String requireSlotName(String slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (!SLOT_NAME.matcher(slot).matches()) {
            throw new BindingRejectedException(
                    "invalid sandbox slot name '" + slot + "': it must match " + SLOT_NAME.pattern());
        }
        return slot;
    }

    /** @return the slot */
    public String slot() {
        return slot;
    }

    /** @return the required profile */
    public Optional<String> requiredProfile() {
        return Optional.ofNullable(requiredProfile);
    }
}
