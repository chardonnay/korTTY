package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillBuiltinBaseline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * State model and operations for AI skills delivered with KorTTY. Everything here is derived
 * on demand from the skill list, the stored baselines and the bundled catalog — nothing is
 * persisted, so the state can never go stale.
 */
public final class BuiltinAiSkillSupport {

    /** Lifecycle of a skill relative to the bundled delivery. */
    public enum BuiltinSkillState {
        /** User-created skill without delivery metadata. */
        USER,
        /** Built-in whose user copy still matches its baseline. */
        BUILTIN_UNMODIFIED,
        /** Built-in the user edited; the delivery has nothing newer than the baseline. */
        BUILTIN_MODIFIED,
        /** Built-in the user edited while the delivery ships a newer version. */
        BUILTIN_UPDATE_AVAILABLE,
        /** Built-in whose id is no longer part of the bundled delivery. */
        BUILTIN_ORPHANED
    }

    /** Derived status; {@code hidden} and {@code overridden} are orthogonal to the state. */
    public record AiSkillStatus(
        BuiltinSkillState state,
        boolean hidden,
        boolean overridden,
        List<String> overriddenByNames) {
    }

    private BuiltinAiSkillSupport() {
    }

    /**
     * Canonical fingerprint over the user-editable delivered fields (name, description, tags,
     * target, content). Deliberately excludes {@code enabled} and {@code hidden}: deactivating
     * or hiding a built-in must not count as a modification, otherwise it would block auto-updates.
     */
    public static String fingerprint(AiSkill skill) {
        if (skill == null) {
            return fingerprint(null, null, List.of(), null, null);
        }
        return fingerprint(skill.getName(), skill.getDescription(), skill.getTags(),
            skill.getTarget() != null ? skill.getTarget().name() : null, skill.getContent());
    }

    public static String fingerprint(AiSkillBuiltinBaseline baseline) {
        if (baseline == null) {
            return fingerprint(null, null, List.of(), null, null);
        }
        return fingerprint(baseline.getName(), baseline.getDescription(), baseline.getTags(),
            baseline.getTarget() != null ? baseline.getTarget().name() : null, baseline.getContent());
    }

    public static boolean isModified(AiSkill skill) {
        return skill != null
            && skill.isBuiltin()
            && skill.getBuiltinBaseline() != null
            && !fingerprint(skill).equals(fingerprint(skill.getBuiltinBaseline()));
    }

    /** Snapshot of a bundled delivery entry, used as the new baseline when adding or updating. */
    public static AiSkillBuiltinBaseline baselineOf(AiSkillMarkdownCodec.BundledAiSkill shipped) {
        AiSkillBuiltinBaseline baseline = new AiSkillBuiltinBaseline();
        baseline.setName(shipped.skill().getName());
        baseline.setDescription(shipped.skill().getDescription());
        baseline.setTags(shipped.skill().getTags());
        baseline.setTarget(shipped.skill().getTarget());
        baseline.setContent(shipped.skill().getContent());
        baseline.setVersion(shipped.version());
        return baseline;
    }

    /**
     * Ids of built-ins currently suppressed by a user skill on the same topic: an enabled user
     * skill with non-blank content whose tags intersect the built-in's delivery-owned topic keys
     * (case-insensitive exact match). User skills always take precedence; the state is fully
     * reversible because nothing is persisted.
     */
    public static Set<String> overriddenBuiltinIds(List<AiSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            return Set.of();
        }
        Set<String> userTags = new HashSet<>();
        for (AiSkill skill : skills) {
            if (skill == null || skill.isBuiltin() || !skill.isEnabled()
                || skill.getContent() == null || skill.getContent().isBlank()) {
                continue;
            }
            for (String tag : skill.getTags()) {
                userTags.add(tag.toLowerCase(Locale.ROOT));
            }
        }
        if (userTags.isEmpty()) {
            return Set.of();
        }
        Set<String> overridden = new HashSet<>();
        for (AiSkill skill : skills) {
            if (skill == null || !skill.isBuiltin()) {
                continue;
            }
            for (String topic : skill.getBuiltinTopics()) {
                if (userTags.contains(topic.toLowerCase(Locale.ROOT))) {
                    overridden.add(skill.getBuiltinId());
                    break;
                }
            }
        }
        return overridden;
    }

    /** All skills minus hidden built-ins minus overridden built-ins — the set AI prompts may use. */
    public static List<AiSkill> effectiveSkills(List<AiSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            return skills != null ? skills : List.of();
        }
        Set<String> overridden = overriddenBuiltinIds(skills);
        List<AiSkill> effective = new ArrayList<>();
        for (AiSkill skill : skills) {
            if (skill == null || skill.isHidden()) {
                continue;
            }
            if (skill.isBuiltin() && overridden.contains(skill.getBuiltinId())) {
                continue;
            }
            effective.add(skill);
        }
        return effective;
    }

    /** All skills minus hidden built-ins — the set pickers and assignment dialogs show. */
    public static List<AiSkill> visibleSkills(List<AiSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            return skills != null ? skills : List.of();
        }
        List<AiSkill> visible = new ArrayList<>();
        for (AiSkill skill : skills) {
            if (skill != null && !skill.isHidden()) {
                visible.add(skill);
            }
        }
        return visible;
    }

    public static AiSkillStatus statusOf(AiSkill skill, List<AiSkill> allSkills, BuiltinAiSkillCatalog catalog) {
        if (skill == null || !skill.isBuiltin()) {
            return new AiSkillStatus(BuiltinSkillState.USER, false, false, List.of());
        }
        boolean overridden = overriddenBuiltinIds(allSkills).contains(skill.getBuiltinId());
        List<String> overriddenByNames = overridden ? overridingSkillNames(skill, allSkills) : List.of();
        AiSkillMarkdownCodec.BundledAiSkill shipped =
            catalog != null ? catalog.byId(skill.getBuiltinId()).orElse(null) : null;
        BuiltinSkillState state;
        if (shipped == null) {
            state = BuiltinSkillState.BUILTIN_ORPHANED;
        } else if (!isModified(skill)) {
            state = BuiltinSkillState.BUILTIN_UNMODIFIED;
        } else {
            AiSkillBuiltinBaseline baseline = skill.getBuiltinBaseline();
            boolean newerShipped = baseline != null
                && !fingerprint(shipped.skill()).equals(fingerprint(baseline))
                && shipped.version() >= baseline.getVersion();
            state = newerShipped ? BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE : BuiltinSkillState.BUILTIN_MODIFIED;
        }
        return new AiSkillStatus(state, skill.isHidden(), overridden, overriddenByNames);
    }

    /**
     * Restores exactly the delivered version the user's edits are based on. Keeps id, enabled and
     * hidden. No-op for user skills or built-ins without a baseline.
     */
    public static boolean reset(AiSkill skill) {
        if (skill == null || !skill.isBuiltin() || skill.getBuiltinBaseline() == null) {
            return false;
        }
        AiSkillBuiltinBaseline baseline = skill.getBuiltinBaseline();
        skill.setName(baseline.getName());
        skill.setDescription(baseline.getDescription());
        skill.setTags(baseline.getTags());
        skill.setTarget(baseline.getTarget());
        skill.setContent(baseline.getContent());
        return true;
    }

    /**
     * Adopts the latest shipped version, replacing content and baseline — the skill becomes
     * {@link BuiltinSkillState#BUILTIN_UNMODIFIED}. Keeps id, enabled and hidden. No-op for user
     * skills and orphaned built-ins.
     */
    public static boolean replaceWithLatest(AiSkill skill, BuiltinAiSkillCatalog catalog) {
        if (skill == null || !skill.isBuiltin() || catalog == null) {
            return false;
        }
        AiSkillMarkdownCodec.BundledAiSkill shipped = catalog.byId(skill.getBuiltinId()).orElse(null);
        if (shipped == null) {
            return false;
        }
        skill.setName(shipped.skill().getName());
        skill.setDescription(shipped.skill().getDescription());
        skill.setTags(shipped.skill().getTags());
        skill.setTarget(shipped.skill().getTarget());
        skill.setContent(shipped.skill().getContent());
        skill.setBuiltinTopics(shipped.skill().getBuiltinTopics());
        skill.setBuiltinBaseline(baselineOf(shipped));
        return true;
    }

    /** Built-ins cannot be deleted (the provisioner would re-add them) — they are hidden instead. */
    public static boolean canHide(AiSkill skill) {
        return skill != null && skill.isBuiltin();
    }

    private static List<String> overridingSkillNames(AiSkill builtin, List<AiSkill> allSkills) {
        Set<String> topics = new HashSet<>();
        for (String topic : builtin.getBuiltinTopics()) {
            topics.add(topic.toLowerCase(Locale.ROOT));
        }
        List<String> names = new ArrayList<>();
        for (AiSkill skill : allSkills != null ? allSkills : List.<AiSkill>of()) {
            if (skill == null || skill.isBuiltin() || !skill.isEnabled()
                || skill.getContent() == null || skill.getContent().isBlank()) {
                continue;
            }
            boolean matches = skill.getTags().stream()
                .anyMatch(tag -> topics.contains(tag.toLowerCase(Locale.ROOT)));
            if (matches && skill.getName() != null && !skill.getName().isBlank()) {
                names.add(skill.getName());
            }
        }
        return names;
    }

    private static String fingerprint(
        String name, String description, List<String> tags, String target, String content) {

        List<String> canonicalTags = new ArrayList<>();
        for (String tag : tags != null ? tags : List.<String>of()) {
            if (tag != null && !tag.isBlank()) {
                canonicalTags.add(tag.trim().toLowerCase(Locale.ROOT));
            }
        }
        canonicalTags.sort(String::compareTo);
        String canonicalContent = (content != null ? content : "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .strip();
        String composed = (name != null ? name.trim() : "")
            + "\u0000" + (description != null ? description.trim() : "")
            + "\u0000" + String.join("\u001F", canonicalTags)
            + "\u0000" + (target != null ? target : de.kortty.model.AiSkillTarget.BOTH.name())
            + "\u0000" + canonicalContent;
        return sha256Hex(composed);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
