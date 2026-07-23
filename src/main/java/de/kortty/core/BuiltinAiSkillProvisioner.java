package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillBuiltinBaseline;
import de.kortty.model.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Syncs the bundled AI skill catalog into the global settings at startup: adds newly delivered
 * skills, silently updates unmodified ones, leaves user-modified ones alone (the UI offers the
 * update), and heals corrupt or duplicated entries. Never removes anything.
 */
public final class BuiltinAiSkillProvisioner {

    private static final Logger logger = LoggerFactory.getLogger(BuiltinAiSkillProvisioner.class);

    public record Result(
        int added,
        int autoUpdated,
        int updatesAvailable,
        int healed,
        int orphaned,
        boolean settingsChanged) {

        static final Result NO_CHANGE = new Result(0, 0, 0, 0, 0, false);
    }

    private BuiltinAiSkillProvisioner() {
    }

    /** Runs the sync against the loaded settings and saves them only when something changed. */
    public static Result provision(GlobalSettingsManager manager) throws Exception {
        Result result = sync(manager.getSettings(), BuiltinAiSkillCatalog.load());
        if (result.settingsChanged()) {
            manager.save();
        }
        logger.info("Builtin AI skills provisioned: {} added, {} auto-updated, {} updates available, "
                + "{} healed, {} orphaned",
            result.added(), result.autoUpdated(), result.updatesAvailable(), result.healed(), result.orphaned());
        return result;
    }

    static Result sync(GlobalSettings settings, BuiltinAiSkillCatalog catalog) {
        if (settings == null || catalog == null || catalog.isEmpty()) {
            // A missing or broken catalog must never mass-orphan or mass-re-add anything.
            logger.warn("Builtin AI skill catalog unavailable, skipping provisioning");
            return Result.NO_CHANGE;
        }
        List<AiSkill> skills = new ArrayList<>(settings.getAiSkills());
        boolean changed = false;
        int added = 0;
        int autoUpdated = 0;
        int updatesAvailable = 0;
        int healed = 0;

        // Phase 1: duplicate builtinIds (hand-edited XML, sync merges). The modified copy carries
        // user work and keeps the slot; the others are demoted to plain user skills — no data loss,
        // and delete becomes possible for them.
        Map<String, AiSkill> byBuiltinId = new LinkedHashMap<>();
        for (AiSkill skill : skills) {
            if (!skill.isBuiltin()) {
                continue;
            }
            AiSkill keeper = byBuiltinId.get(skill.getBuiltinId());
            if (keeper == null) {
                byBuiltinId.put(skill.getBuiltinId(), skill);
                continue;
            }
            AiSkill demoted = skill;
            if (!BuiltinAiSkillSupport.isModified(keeper) && BuiltinAiSkillSupport.isModified(skill)) {
                byBuiltinId.put(skill.getBuiltinId(), skill);
                demoted = keeper;
            }
            logger.warn("Duplicate builtin AI skill id {}, demoting one copy to a user skill",
                demoted.getBuiltinId());
            demoted.setBuiltinId(null);
            demoted.setBuiltinBaseline(null);
            demoted.setBuiltinTopics(List.of());
            demoted.setHidden(false);
            changed = true;
            healed++;
        }

        // Phase 2: walk the shipped catalog in index order.
        for (AiSkillMarkdownCodec.BundledAiSkill shipped : catalog.entries()) {
            String builtinId = shipped.skill().getBuiltinId();
            AiSkill existing = byBuiltinId.get(builtinId);

            if (existing == null) {
                // Case A: first run or newly delivered skill. When auto-detection is off, all
                // enabled applicable skills would be injected into every prompt — new built-ins
                // therefore arrive disabled and must be enabled by the user.
                AiSkill addedSkill = new AiSkill(shipped.skill());
                if (!settings.isAiSkillAutoDetectionEnabled()) {
                    addedSkill.setEnabled(false);
                }
                addedSkill.setBuiltinBaseline(BuiltinAiSkillSupport.baselineOf(shipped));
                skills.add(addedSkill);
                byBuiltinId.put(builtinId, addedSkill);
                changed = true;
                added++;
                continue;
            }

            // Case B: topic keys are delivery-owned metadata, refreshed even on modified skills.
            if (!equalIgnoreCaseAndOrder(existing.getBuiltinTopics(), shipped.skill().getBuiltinTopics())) {
                existing.setBuiltinTopics(shipped.skill().getBuiltinTopics());
                changed = true;
            }

            if (existing.getBuiltinBaseline() == null) {
                // Case C3: corrupt entry — adopt today's shipped version as baseline so
                // modification detection and reset work again.
                logger.warn("Builtin AI skill {} had no baseline, healing from shipped version", builtinId);
                existing.setBuiltinBaseline(BuiltinAiSkillSupport.baselineOf(shipped));
                changed = true;
                healed++;
            }

            AiSkillBuiltinBaseline baseline = existing.getBuiltinBaseline();

            if ((existing.getContent() == null || existing.getContent().isBlank())
                && baseline.getContent() != null && !baseline.getContent().isBlank()) {
                // Case C4: a built-in with blank content is useless (blank skills are never
                // injected) — this state comes from data damage (e.g. an editor race wiping the
                // text), not from a meaningful user edit. Restore the baseline it was based on.
                logger.warn("Builtin AI skill {} had blank content, restoring the baseline version", builtinId);
                BuiltinAiSkillSupport.reset(existing);
                changed = true;
                healed++;
            }

            String shippedFingerprint = BuiltinAiSkillSupport.fingerprint(shipped.skill());
            String baselineFingerprint = BuiltinAiSkillSupport.fingerprint(baseline);

            if (shippedFingerprint.equals(baselineFingerprint)) {
                // Case B2: nothing new shipped; keep version bookkeeping in sync.
                if (baseline.getVersion() != shipped.version()) {
                    baseline.setVersion(shipped.version());
                    changed = true;
                }
                continue;
            }

            if (shipped.version() < baseline.getVersion()) {
                // Case D: downgrade (older app run against newer settings, e.g. restored backup).
                logger.info("Builtin AI skill {} baseline is newer than the shipped version, leaving it", builtinId);
                continue;
            }

            if (BuiltinAiSkillSupport.isModified(existing)) {
                // Case E: user edits win; the UI derives "update available" from the catalog.
                updatesAvailable++;
                continue;
            }

            // Case F: unmodified and a newer delivery — silent auto-replace. Id, enabled and
            // hidden are preserved (connection assignments stay valid, user preferences stick).
            existing.setName(shipped.skill().getName());
            existing.setDescription(shipped.skill().getDescription());
            existing.setTags(shipped.skill().getTags());
            existing.setTarget(shipped.skill().getTarget());
            existing.setContent(shipped.skill().getContent());
            existing.setBuiltinBaseline(BuiltinAiSkillSupport.baselineOf(shipped));
            changed = true;
            autoUpdated++;
        }

        // Phase 3: orphans (builtinId no longer shipped) are counted but never touched — reset
        // still works from the stored baseline, and a returning delivery resumes updates.
        int orphaned = 0;
        for (AiSkill skill : skills) {
            if (skill.isBuiltin() && catalog.byId(skill.getBuiltinId()).isEmpty()) {
                orphaned++;
            }
        }

        if (changed) {
            settings.setAiSkills(skills);
        }
        return new Result(added, autoUpdated, updatesAvailable, healed, orphaned, changed);
    }

    private static boolean equalIgnoreCaseAndOrder(List<String> left, List<String> right) {
        java.util.Set<String> leftSet = new java.util.HashSet<>();
        for (String value : left != null ? left : List.<String>of()) {
            leftSet.add(value.toLowerCase(Locale.ROOT));
        }
        java.util.Set<String> rightSet = new java.util.HashSet<>();
        for (String value : right != null ? right : List.<String>of()) {
            rightSet.add(value.toLowerCase(Locale.ROOT));
        }
        return leftSet.equals(rightSet);
    }
}
