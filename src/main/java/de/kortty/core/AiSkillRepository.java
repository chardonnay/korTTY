package de.kortty.core;

import de.kortty.memory.MemoryConsent;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillBuiltinBaseline;
import de.kortty.model.AiSkillExternalSource;
import de.kortty.model.AiSkillLearnedRule;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The single write path for the user's AI skills. Every writer goes through here: the AI-skills editor
 * saves its edits with {@link #commit}, and korTTY's own background changes (a learned rule, a new
 * korTTY skill) go through {@link #update}, which needs the user's {@link MemoryConsent}.
 *
 * <p>Both writes merge with what is stored at the time of the write, so an edit never silently
 * overwrites a change made in the meantime: {@link #threeWayMerge} keeps the stored version of every
 * skill the editor did not change, and the union of learned rules for every skill it did. Changed
 * skills pass a validator first (by default: the text must be storable in {@code global-settings.xml} and hold no
 * possible secret, see {@link AiSkillSecretCheck}).
 *
 * <p>Writes and {@link #snapshot()} run on the owner thread, the JavaFX application thread in the app.
 * Listeners are notified on that thread after every stored change.
 */
public final class AiSkillRepository {

    /**
     * The result of a write.
     *
     * @param changed   whether the stored skills differ from what they were before the write
     * @param stored    copies of the skills as stored afterwards
     * @param refusals  one message per changed skill the validator refused; its stored version was kept
     */
    public record Outcome(boolean changed, List<AiSkill> stored, List<String> refusals) {
    }

    /** Where writes run: the JavaFX application thread in the app, the calling thread in tests. */
    interface OwnerThread {

        boolean isCurrent();

        /** Runs {@code task} now when already on the owner thread, otherwise later on it. */
        void execute(Runnable task);

        static OwnerThread javafxApplicationThread() {
            return new OwnerThread() {
                @Override
                public boolean isCurrent() {
                    return Platform.isFxApplicationThread();
                }

                @Override
                public void execute(Runnable task) {
                    if (isCurrent()) {
                        task.run();
                    } else {
                        Platform.runLater(task);
                    }
                }
            };
        }

        /** Every caller is the owner: writes run at once on the calling thread. */
        static OwnerThread callingThread() {
            return new OwnerThread() {
                @Override
                public boolean isCurrent() {
                    return true;
                }

                @Override
                public void execute(Runnable task) {
                    task.run();
                }
            };
        }
    }

    private final GlobalSettingsManager settings;
    private final OwnerThread ownerThread;
    private final Function<AiSkill, Optional<String>> validator;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Created by {@link GlobalSettingsManager#aiSkillRepository()}, which keeps one per settings file. */
    AiSkillRepository(GlobalSettingsManager settings) {
        this(settings, OwnerThread.javafxApplicationThread(), AiSkillRepository::storableOrSecretRefusal);
    }

    /**
     * A repository whose writes run on the calling thread, for tests and headless tools that have no JavaFX
     * application thread. The caller must be the only writer of {@code settings}.
     */
    public static AiSkillRepository onCallingThread(GlobalSettingsManager settings) {
        return new AiSkillRepository(settings, OwnerThread.callingThread(), AiSkillRepository::storableOrSecretRefusal);
    }

    AiSkillRepository(
        GlobalSettingsManager settings,
        OwnerThread ownerThread,
        Function<AiSkill, Optional<String>> validator) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Merges {@code edited} into the stored skills. {@code base} is what the editor loaded, {@code edited}
     * is what it now holds and {@code current} is what is stored. Persisting is the caller's job.
     */
    public Outcome commit(List<AiSkill> base, List<AiSkill> edited) {
        requireOwnerThread("commit");
        List<AiSkill> stored = copies(storedSkills());
        return store(threeWayMerge(base, edited, stored), stored, false);
    }

    /**
     * A change korTTY makes on its own initiative. {@code change} receives copies of the stored skills and
     * returns the new list; only skills it changed are validated, and a refused one keeps its stored
     * version. korTTY may add learned rules to a stored skill and create new skills, but it never changes
     * anything else of a stored skill: such a change is refused. A changed result is saved in the background
     * and reported to the listeners.
     *
     * @param consent the user's consent to this change; it covers one {@code update} and is used up by it
     */
    public CompletableFuture<Outcome> update(UnaryOperator<List<AiSkill>> change, MemoryConsent consent) {
        Objects.requireNonNull(consent, "korTTY changes AI skills only with the user's consent");
        Objects.requireNonNull(change, "change");
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        ownerThread.execute(() -> {
            try {
                if (!consent.consume()) {
                    throw new IllegalStateException("A consent covers one korTTY change; ask the user again.");
                }
                List<AiSkill> stored = copies(storedSkills());
                List<AiSkill> proposed = Objects.requireNonNull(change.apply(copies(stored)), "change returned no skills");
                Outcome outcome = store(proposed, stored, true);
                if (outcome.changed()) {
                    settings.scheduleSave();
                }
                done.complete(outcome);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /** Copies of the skills as stored now. */
    public List<AiSkill> snapshot() {
        requireOwnerThread("snapshot");
        return copies(storedSkills());
    }

    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Three-way merge by skill id. {@code base} is the last state the editor saw, {@code mine} what the
     * editor holds now and {@code current} what is stored. The result contains copies only.
     *
     * <ul>
     *   <li>A skill {@code mine} did not change keeps its current version; it is dropped when it was
     *       deleted meanwhile.</li>
     *   <li>A skill {@code mine} changed takes {@code mine}, except that a name, description, tag set, state,
     *       target, content or hidden flag {@code mine} did not change keeps its stored value. Every learned
     *       rule that was added meanwhile is kept. A rule {@code mine} deleted (present in {@code base}, absent
     *       from {@code mine}) stays deleted.</li>
     *   <li>A skill {@code mine} removed is removed, even if it changed meanwhile.</li>
     *   <li>A skill added meanwhile is kept and appended after {@code mine}'s order.</li>
     * </ul>
     */
    public static List<AiSkill> threeWayMerge(List<AiSkill> base, List<AiSkill> mine, List<AiSkill> current) {
        Map<String, AiSkill> baseById = byId(base);
        Map<String, AiSkill> currentById = byId(current);
        List<AiSkill> merged = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (AiSkill edited : nonNull(mine)) {
            if (edited == null || !placed.add(edited.getId())) {
                continue;
            }
            AiSkill original = baseById.get(edited.getId());
            AiSkill stored = currentById.get(edited.getId());
            if (original != null && sameSkill(original, edited)) {
                if (stored != null) {
                    merged.add(new AiSkill(stored));
                }
            } else {
                merged.add(mergeChanged(original, edited, stored));
            }
        }
        for (AiSkill stored : nonNull(current)) {
            if (stored != null && !baseById.containsKey(stored.getId()) && placed.add(stored.getId())) {
                merged.add(new AiSkill(stored));
            }
        }
        return merged;
    }

    /** Whether two skills are equal in every persisted field, learned rules included. */
    public static boolean sameSkill(AiSkill left, AiSkill right) {
        return persistedForm(left).equals(persistedForm(right));
    }

    /**
     * The skill as a list of its persisted values, nested for the built-in baseline, the external source and
     * the learned rules. Comparing these lists is the equality of stored skills; {@link AiSkill} itself
     * does not implement {@code equals}.
     */
    static List<Object> persistedForm(AiSkill skill) {
        return Arrays.asList(
            skill.getId(),
            skill.getName(),
            skill.getDescription(),
            skill.getTags(),
            skill.isEnabled(),
            skill.getTarget(),
            skill.getContent(),
            skill.getBuiltinId(),
            skill.isHidden(),
            skill.getBuiltinTopics(),
            baselineForm(skill.getBuiltinBaseline()),
            externalSourceForm(skill.getExternalSource()),
            skill.getOrigin(),
            skill.getCreatedAt(),
            skill.isOriginAcknowledged(),
            rulesForm(skill.getLearnedRules()));
    }

    private static List<Object> baselineForm(AiSkillBuiltinBaseline baseline) {
        return baseline == null
            ? null
            : Arrays.asList(
                baseline.getName(),
                baseline.getDescription(),
                baseline.getTags(),
                baseline.getTarget(),
                baseline.getContent(),
                baseline.getVersion());
    }

    private static List<Object> externalSourceForm(AiSkillExternalSource source) {
        return source == null
            ? null
            : Arrays.asList(
                source.getProviderId(),
                source.getReference(),
                source.getSourceUrl(),
                source.getRevision(),
                source.getContentSha256(),
                source.getImportedAt());
    }

    private static List<List<Object>> rulesForm(List<AiSkillLearnedRule> rules) {
        List<List<Object>> forms = new ArrayList<>();
        for (AiSkillLearnedRule rule : rules) {
            forms.add(Arrays.asList(
                rule.getId(),
                rule.getText(),
                rule.getCreatedAt(),
                rule.getSource(),
                rule.getSha256(),
                rule.isAcknowledged()));
        }
        return forms;
    }

    private static AiSkill mergeChanged(AiSkill original, AiSkill edited, AiSkill stored) {
        AiSkill merged = new AiSkill(edited);
        if (stored == null) {
            return merged;
        }
        if (original != null) {
            keepStoredUnlessEdited(original, edited, stored, merged);
        }
        Set<String> editedRuleIds = new HashSet<>();
        for (AiSkillLearnedRule rule : edited.getLearnedRules()) {
            editedRuleIds.add(rule.getId());
        }
        Set<String> originalRuleIds = new HashSet<>();
        if (original != null) {
            for (AiSkillLearnedRule rule : original.getLearnedRules()) {
                originalRuleIds.add(rule.getId());
            }
        }
        for (AiSkillLearnedRule rule : stored.getLearnedRules()) {
            if (!editedRuleIds.contains(rule.getId()) && !originalRuleIds.contains(rule.getId())) {
                merged.getLearnedRules().add(new AiSkillLearnedRule(rule));
            }
        }
        return merged;
    }

    /**
     * Gives {@code merged} the stored value of each text or switch field the editor did not change, so that a
     * change saved meanwhile by korTTY (a new description, content or state) survives the editor's save. A field
     * the editor changed keeps the editor's value.
     */
    private static void keepStoredUnlessEdited(AiSkill original, AiSkill edited, AiSkill stored, AiSkill merged) {
        keepStoredUnlessEdited(original, edited, stored, merged, AiSkill::getName, AiSkill::setName);
        keepStoredUnlessEdited(original, edited, stored, merged, AiSkill::getDescription, AiSkill::setDescription);
        keepStoredUnlessEdited(original, edited, stored, merged, AiSkill::getTags, AiSkill::setTags);
        keepStoredUnlessEdited(original, edited, stored, merged, AiSkill::isEnabled, AiSkill::setEnabled);
        keepStoredUnlessEdited(original, edited, stored, merged, AiSkill::getTarget, AiSkill::setTarget);
        keepStoredUnlessEdited(original, edited, stored, merged, AiSkill::getContent, AiSkill::setContent);
        keepStoredUnlessEdited(original, edited, stored, merged, AiSkill::isHidden, AiSkill::setHidden);
    }

    private static <T> void keepStoredUnlessEdited(AiSkill original, AiSkill edited, AiSkill stored, AiSkill merged,
                                                   Function<AiSkill, T> read, BiConsumer<AiSkill, T> write) {
        if (Objects.equals(read.apply(original), read.apply(edited))) {
            write.accept(merged, read.apply(stored));
        }
    }

    /**
     * Whether two skills are equal in every persisted field except their learned rules. A korTTY change may
     * differ from the stored skill in those rules only.
     */
    private static boolean sameApartFromRules(AiSkill stored, AiSkill proposed) {
        AiSkill withoutRules = new AiSkill(stored);
        withoutRules.setLearnedRules(List.of());
        AiSkill proposedWithoutRules = new AiSkill(proposed);
        proposedWithoutRules.setLearnedRules(List.of());
        return sameSkill(withoutRules, proposedWithoutRules);
    }

    /**
     * Validates the changed skills and stores the result; a refused skill keeps its stored version. With
     * {@code korttyChange}, a stored skill may only gain learned rules: any other change to it is refused.
     */
    private Outcome store(List<AiSkill> candidate, List<AiSkill> stored, boolean korttyChange) {
        Map<String, AiSkill> storedById = byId(stored);
        Set<String> acceptedIds = new HashSet<>();
        List<AiSkill> accepted = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        for (AiSkill skill : nonNull(candidate)) {
            if (skill == null || !acceptedIds.add(skill.getId())) {
                continue;
            }
            AiSkill previous = storedById.get(skill.getId());
            if (previous != null && sameSkill(previous, skill)) {
                accepted.add(skill);
                continue;
            }
            Optional<String> refusal = korttyChange && previous != null && !sameApartFromRules(previous, skill)
                ? Optional.of("korTTY may only add learned rules to the skill \"" + skill.getName() + "\".")
                : validator.apply(skill);
            if (refusal.isEmpty()) {
                accepted.add(skill);
                continue;
            }
            refusals.add(refusal.get());
            if (previous != null) {
                accepted.add(previous);
            }
        }
        boolean changed = !persistedForms(accepted).equals(persistedForms(stored));
        if (changed) {
            settings.getSettings().setAiSkills(accepted);
            for (Runnable listener : listeners) {
                listener.run();
            }
        }
        return new Outcome(changed, copies(storedSkills()), List.copyOf(refusals));
    }

    private static List<List<Object>> persistedForms(List<AiSkill> skills) {
        List<List<Object>> forms = new ArrayList<>();
        for (AiSkill skill : skills) {
            forms.add(persistedForm(skill));
        }
        return forms;
    }

    private List<AiSkill> storedSkills() {
        return settings.getSettings().getAiSkills();
    }

    private void requireOwnerThread(String operation) {
        if (!ownerThread.isCurrent()) {
            throw new IllegalStateException("AiSkillRepository." + operation + " must run on the owner thread");
        }
    }

    /**
     * The default validator: a skill is refused when the settings file cannot store one of its characters, or when
     * it holds a possible secret ({@link AiSkillSecretCheck}). Passwords never reach {@code global-settings.xml}.
     */
    static Optional<String> storableOrSecretRefusal(AiSkill skill) {
        Optional<String> storable = storableRefusal(skill);
        return storable.isPresent() ? storable : AiSkillSecretCheck.refusal(skill);
    }

    /** The first reason to refuse {@code skill}: a character the settings file cannot store. */
    static Optional<String> storableRefusal(AiSkill skill) {
        AiSkill.UnstorableText text = skill.firstUnstorableText();
        if (text == null) {
            return Optional.empty();
        }
        return Optional.of("Skill \"" + skill.getName() + "\" holds a character the settings file cannot store ("
            + text.codePointLabel() + ") in " + text.field());
    }

    private static List<AiSkill> copies(List<AiSkill> skills) {
        List<AiSkill> copies = new ArrayList<>();
        for (AiSkill skill : nonNull(skills)) {
            if (skill != null) {
                copies.add(new AiSkill(skill));
            }
        }
        return copies;
    }

    private static Map<String, AiSkill> byId(List<AiSkill> skills) {
        Map<String, AiSkill> byId = new LinkedHashMap<>();
        for (AiSkill skill : nonNull(skills)) {
            if (skill != null) {
                byId.putIfAbsent(skill.getId(), skill);
            }
        }
        return byId;
    }

    private static List<AiSkill> nonNull(List<AiSkill> skills) {
        return skills != null ? skills : List.of();
    }
}
