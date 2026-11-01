package de.kortty.memory;

import de.kortty.core.AiSecretGuard;
import de.kortty.core.AiSkillRepository;
import de.kortty.core.AiSkillSecretCheck;
import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.RuleSaveException.Reason;
import de.kortty.memory.external.ExternalMemoryReader;
import de.kortty.memory.SavedRule.UndoOutcome;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiSkillOrigin;
import de.kortty.model.AiSkillTarget;
import de.kortty.policy.PolicyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Saves a rule the user confirmed into the memory of a scope, into a skill the user has, or into a new korTTY skill,
 * and removes it again within {@link #UNDO_WINDOW}.
 *
 * <p>Calling {@link #confirm} is the user's confirmation. The service issues the {@link MemoryConsent} for its one
 * write itself, and nothing is written before that call. Before any write, the text is checked on the calling thread:
 * the secret guard, the length of at most {@link #MAX_RULE_CHARACTERS} characters, and the target against the policy.
 * The memory is written on the background executor, and a skill is changed through the {@link AiSkillRepository},
 * which runs the change on its owner thread. A new skill and a learned rule never change the content of a skill the
 * user wrote.</p>
 *
 * <p>The returned future fails with a {@link RuleSaveException}, never with a wrapper.</p>
 */
public final class RuleProposalService {

    public static final Duration UNDO_WINDOW = Duration.ofSeconds(30);
    public static final int MAX_RULE_CHARACTERS = MemoryStore.MAX_ENTRY_CHARS;

    private static final Logger logger = LoggerFactory.getLogger(RuleProposalService.class);
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kortty-rule-learning");
        thread.setDaemon(true);
        return thread;
    });

    private final MemoryService memory;
    private final AiSkillRepository skills;
    private final Supplier<RuleTargetPolicy> policy;
    private final Clock clock;
    private final Executor background;

    public RuleProposalService(MemoryService memory, AiSkillRepository skills, Supplier<RuleTargetPolicy> policy,
                               Clock clock, Executor background) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.background = Objects.requireNonNull(background, "background");
    }

    /** The service of the running application: the default memory, the settings' skills and the current policy. */
    public static RuleProposalService forSettings(GlobalSettingsManager settings) {
        MemoryService memory = MemoryService.get();
        return new RuleProposalService(memory, settings.aiSkillRepository(),
            () -> RuleTargetPolicy.from(settings.getSettings(), PolicyManager.effective(), memory),
            Clock.systemDefaultZone(), BACKGROUND);
    }

    /**
     * Saves {@code finalText} as a rule into {@code finalTarget}, with the source of {@code proposal}.
     *
     * @return the saved rule with its undo, or a future that fails with a {@link RuleSaveException} when the rule was
     *         refused or could not be saved
     */
    public CompletableFuture<SavedRule> confirm(RuleProposal proposal, String finalText, RuleTarget finalTarget) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(finalTarget, "finalTarget");
        try {
            String text = checkedText(finalText);
            RuleTargetPolicy current = policy.get();
            requireAllowed(finalTarget, current);
            return switch (finalTarget) {
                case RuleTarget.Global global -> saveToMemory(MemoryScope.global(), text, proposal.source(), current);
                case RuleTarget.Connection connection -> saveToMemory(MemoryScope.connection(connection.connectionId()),
                    text, proposal.source(), current);
                case RuleTarget.Skill skill -> saveToSkill(skill.skillId(), text, proposal.source());
                case RuleTarget.NewSkill newSkill -> createSkill(newSkill, text, proposal.source());
                case RuleTarget.None none -> CompletableFuture.failedFuture(new RuleSaveException(Reason.NO_TARGET));
            };
        } catch (RuleSaveException refusal) {
            return CompletableFuture.failedFuture(refusal);
        }
    }

    /**
     * The text as it is saved: one line, without invisible characters, which could carry text the user does not see. The
     * secret check runs on that same text.
     */
    private static String checkedText(String finalText) {
        String text = ExternalMemoryReader.sanitize(finalText == null ? "" : finalText.strip()).text();
        String oneLine = text.replaceAll("\\s+", " ").strip();
        if (oneLine.isEmpty()) {
            throw new RuleSaveException(Reason.EMPTY);
        }
        AiSecretGuard.Result check = AiSecretGuard.check(text);
        if (!check.ok()) {
            throw secretRefusal(check.first().orElseThrow());
        }
        int length = oneLine.codePointCount(0, oneLine.length());
        if (length > MAX_RULE_CHARACTERS) {
            throw new RuleSaveException(Reason.TOO_LONG, length, MAX_RULE_CHARACTERS);
        }
        return oneLine;
    }

    private static void requireAllowed(RuleTarget target, RuleTargetPolicy current) {
        switch (target) {
            case RuleTarget.Global global -> requireMemory(current);
            case RuleTarget.Connection connection -> requireMemory(current);
            case RuleTarget.Skill skill -> requireExistingSkills(current);
            case RuleTarget.NewSkill newSkill -> requireSkillCreation(current);
            case RuleTarget.None none -> throw new RuleSaveException(Reason.NO_TARGET);
        }
    }

    private static void requireMemory(RuleTargetPolicy current) {
        if (!current.memoryWritable()) {
            throw new RuleSaveException(Reason.READ_ONLY);
        }
    }

    private static void requireExistingSkills(RuleTargetPolicy current) {
        if (!current.existingSkillsAllowed()) {
            throw new RuleSaveException(Reason.SKILL_CREATION_NOT_ALLOWED);
        }
    }

    private static void requireSkillCreation(RuleTargetPolicy current) {
        if (!current.skillCreationAllowed()) {
            throw new RuleSaveException(Reason.SKILL_CREATION_NOT_ALLOWED);
        }
    }

    private CompletableFuture<SavedRule> saveToMemory(MemoryScope scope, String text, RuleSource source,
                                                      RuleTargetPolicy current) {
        RuleTarget target = scope.isGlobal() ? RuleTarget.global() : RuleTarget.connection(scope.connectionId());
        CompletableFuture<SavedRule> done = new CompletableFuture<>();
        background.execute(() -> {
            try {
                MemoryStore store = memory.store();
                MemoryDocument before = MemoryFormat.parse(store.read(scope).text());
                if (before.entries().stream().anyMatch(entry -> entry.body().equals("- " + text))) {
                    done.completeExceptionally(new RuleSaveException(Reason.DUPLICATE));
                    return;
                }
                MemoryConsent consent = MemoryConsent.grant(scope, clock.instant());
                MemoryStore.Snapshot written = store.appendKorttyEntry(scope, text, source.value(), consent,
                    current.memoryBudgetTokens());
                String entryId = addedEntryId(before, MemoryFormat.parse(written.text()));
                done.complete(new SavedRule(target, text, clock.instant(), clock,
                    () -> undoMemory(scope, entryId)));
            } catch (MemoryWriteException refusal) {
                done.completeExceptionally(fromMemory(refusal));
            } catch (IOException | RuntimeException failure) {
                logger.warn("The rule could not be written to the memory of {}", scope.key(), failure);
                done.completeExceptionally(new RuleSaveException(Reason.FAILED));
            }
        });
        return done;
    }

    private CompletableFuture<SavedRule> saveToSkill(String skillId, String text, RuleSource source) {
        AiSkillLearnedRule rule = AiSkillLearnedRule.create(text, source.value());
        CompletableFuture<SavedRule> done = new CompletableFuture<>();
        skills.update(list -> {
            addLearnedRule(list, skillId, rule);
            return list;
        }, grantForSkills()).whenComplete((outcome, failure) -> {
            if (failure != null) {
                done.completeExceptionally(asRefusal(failure));
            } else if (!outcome.changed()) {
                logger.warn("The skill repository kept the skill {} unchanged: {}", skillId, outcome.refusals());
                done.completeExceptionally(new RuleSaveException(Reason.FAILED));
            } else {
                done.complete(new SavedRule(RuleTarget.skill(skillId), text, clock.instant(), clock,
                    () -> undoSkillRule(skillId, rule.getId(), rule.getSha256())));
            }
        });
        return done;
    }

    private CompletableFuture<SavedRule> createSkill(RuleTarget.NewSkill newSkill, String text, RuleSource source) {
        String topic = newSkill.topic();
        String name = newSkill.name();
        Set<String> builtinTopics = RuleTopic.builtinTopicsOf(BuiltinAiSkillCatalog.load());
        if (builtinTopics.contains(topic)) {
            return CompletableFuture.failedFuture(new RuleSaveException(Reason.SKILL_EXISTS));
        }
        AiSkillLearnedRule rule = AiSkillLearnedRule.create(text, source.value());
        AiSkill created = newKorttySkill(name, topic, rule, LocalDate.now(clock));
        CompletableFuture<SavedRule> done = new CompletableFuture<>();
        skills.update(list -> {
            if (list.stream().anyMatch(skill -> RuleRouter.hasTag(skill, topic) || name.equalsIgnoreCase(skill.getName()))) {
                throw new RuleSaveException(Reason.SKILL_EXISTS);
            }
            list.add(new AiSkill(created));
            return list;
        }, grantForSkills()).whenComplete((outcome, failure) -> {
            if (failure != null) {
                done.completeExceptionally(asRefusal(failure));
                return;
            }
            Optional<AiSkill> stored = outcome.stored().stream()
                .filter(skill -> created.getId().equals(skill.getId()))
                .findFirst();
            if (!outcome.changed() || stored.isEmpty()) {
                done.completeExceptionally(new RuleSaveException(Reason.FAILED));
                return;
            }
            done.complete(new SavedRule(RuleTarget.newSkill(topic, name), text, clock.instant(), clock,
                () -> undoNewSkill(created.getId(), rule.getId(), rule.getSha256(), stored.get())));
        });
        return done;
    }

    /** Adds {@code rule} to the skill, and refuses a rule the skill already holds. */
    private static void addLearnedRule(List<AiSkill> list, String skillId, AiSkillLearnedRule rule) {
        AiSkill skill = list.stream()
            .filter(candidate -> skillId.equals(candidate.getId()))
            .findFirst()
            .orElseThrow(() -> new RuleSaveException(Reason.SKILL_MISSING));
        boolean held = skill.getLearnedRules().stream()
            .anyMatch(existing -> rule.getSha256().equals(existing.getSha256()));
        if (held) {
            throw new RuleSaveException(Reason.DUPLICATE);
        }
        skill.getLearnedRules().add(new AiSkillLearnedRule(rule));
    }

    /** The korTTY skill for a topic: a short header written here, with the rule as its only learned rule. */
    private static AiSkill newKorttySkill(String name, String topic, AiSkillLearnedRule rule, LocalDate today) {
        String displayName = RuleTopic.displayName(topic);
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setDescription("Rules the user confirmed for " + displayName + ", saved by korTTY.");
        skill.setTags(List.of(topic));
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent("# " + name + "\n\nThe rules listed below are the ones the user confirmed for "
            + displayName + ". Follow them whenever this skill applies.");
        skill.setOrigin(AiSkillOrigin.KORTTY);
        skill.setCreatedAt(today.toString());
        skill.setOriginAcknowledged(false);
        skill.setLearnedRules(List.of(rule));
        return skill;
    }

    private CompletableFuture<UndoOutcome> undoMemory(MemoryScope scope, String entryId) {
        CompletableFuture<UndoOutcome> done = new CompletableFuture<>();
        if (entryId == null) {
            done.complete(UndoOutcome.NOT_FOUND);
            return done;
        }
        background.execute(() -> {
            try {
                MemoryStore store = memory.store();
                String before = store.read(scope).text();
                MemoryStore.Snapshot after = store.archiveKorttyEntries(scope, Set.of(entryId),
                    MemoryConsent.grant(scope, clock.instant()));
                // Archiving changes the file only when the entry is still an intact korTTY entry.
                done.complete(after.text().equals(before) ? UndoOutcome.NOT_FOUND : UndoOutcome.UNDONE);
            } catch (IOException | RuntimeException failure) {
                logger.warn("The rule could not be removed from the memory of {}", scope.key(), failure);
                done.complete(UndoOutcome.FAILED);
            }
        });
        return done;
    }

    /**
     * Removes the learned rule saved under {@code ruleId} while it still has the text that was saved. A rule the user
     * edited in the meantime keeps its id, so it is recognised by its digest; an edited rule stays and is reported as
     * not found.
     */
    private CompletableFuture<UndoOutcome> undoSkillRule(String skillId, String ruleId, String savedSha256) {
        AtomicBoolean removed = new AtomicBoolean();
        AtomicBoolean changed = new AtomicBoolean();
        return skills.update(list -> {
            list.stream()
                .filter(skill -> skillId.equals(skill.getId()))
                .findFirst()
                .ifPresent(skill -> {
                    removed.set(skill.getLearnedRules().removeIf(rule -> isSavedRule(rule, ruleId, savedSha256)));
                    changed.set(!removed.get() && skill.getLearnedRules().stream()
                        .anyMatch(rule -> rule != null && ruleId.equals(rule.getId())));
                });
            return list;
        }, grantForSkills()).handle((outcome, failure) -> failure != null
            ? UndoOutcome.FAILED
            : removed.get() ? UndoOutcome.UNDONE
            : changed.get() ? UndoOutcome.RULE_CHANGED_KEPT
            : UndoOutcome.NOT_FOUND);
    }

    private static boolean isSavedRule(AiSkillLearnedRule rule, String ruleId, String savedSha256) {
        return ruleId.equals(rule.getId()) && savedSha256.equals(rule.getSha256());
    }

    /**
     * Removes the new skill again while it is exactly as it was created; once the user changed it, only the rule goes.
     */
    private CompletableFuture<UndoOutcome> undoNewSkill(String skillId, String ruleId, String savedSha256,
                                                        AiSkill created) {
        AtomicReference<UndoOutcome> result = new AtomicReference<>(UndoOutcome.NOT_FOUND);
        return skills.update(list -> {
            Optional<AiSkill> stored = list.stream().filter(skill -> skillId.equals(skill.getId())).findFirst();
            if (stored.isPresent()) {
                if (AiSkillRepository.sameSkill(stored.get(), created)) {
                    list.removeIf(skill -> skillId.equals(skill.getId()));
                    result.set(UndoOutcome.UNDONE);
                } else if (stored.get().getLearnedRules().removeIf(rule -> isSavedRule(rule, ruleId, savedSha256))) {
                    result.set(UndoOutcome.RULE_REMOVED_SKILL_KEPT);
                }
            }
            return list;
        }, grantForSkills()).handle((outcome, failure) -> failure != null ? UndoOutcome.FAILED : result.get());
    }

    /**
     * A consent for one change of the skills. The skills are not memory files, so the global scope names them.
     */
    private MemoryConsent grantForSkills() {
        return MemoryConsent.grant(MemoryScope.global(), clock.instant());
    }

    /** The one entry id that the write added; {@code null} when the new file does not show exactly one new entry. */
    private static String addedEntryId(MemoryDocument before, MemoryDocument after) {
        Set<String> known = new HashSet<>();
        for (KorttyEntry entry : before.entries()) {
            known.add(entry.id());
        }
        List<String> added = after.entries().stream()
            .map(KorttyEntry::id)
            .filter(id -> !known.contains(id))
            .toList();
        return added.size() == 1 ? added.get(0) : null;
    }

    private static RuleSaveException secretRefusal(AiSecretGuard.Finding finding) {
        return new RuleSaveException(Reason.SECRET,
            AiSkillSecretCheck.message(new AiSkillSecretCheck.Finding(AiSkill.TextField.LEARNED_RULE, finding)));
    }

    private static RuleSaveException fromMemory(MemoryWriteException refusal) {
        if (refusal instanceof MemorySecretException secret) {
            return secretRefusal(secret.finding());
        }
        if (refusal instanceof MemoryReadOnlyException) {
            return new RuleSaveException(Reason.READ_ONLY);
        }
        if (refusal instanceof MemoryLimitException) {
            return new RuleSaveException(Reason.LIMIT);
        }
        if (refusal instanceof MemoryConflictException) {
            return new RuleSaveException(Reason.CONFLICT);
        }
        logger.warn("The memory refused a rule", refusal);
        return new RuleSaveException(Reason.FAILED);
    }

    /** The refusal behind a failed skill change, unwrapped; anything else is reported as a failed save. */
    private static RuleSaveException asRefusal(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
        if (cause instanceof RuleSaveException refusal) {
            return refusal;
        }
        logger.warn("The rule could not be saved to a skill", cause);
        return new RuleSaveException(Reason.FAILED);
    }
}
