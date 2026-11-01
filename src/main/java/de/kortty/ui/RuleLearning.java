package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.memory.LearningState;
import de.kortty.memory.MemoryService;
import de.kortty.memory.RuleDetector;
import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleSource;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.policy.PolicyManager;
import javafx.application.Platform;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Starts the rule detection after a chat answer or an agent run, and the manual "Als Regel merken". The detection call runs
 * on a background thread, and only a found proposal is handed to the FX thread, so neither the answer nor the run waits for
 * it. Only the interactive surfaces call this: swarm and job runs never learn.
 */
final class RuleLearning {

    /** How long a detection may take before its card is dropped. The call itself is not interrupted. */
    static final Duration DETECTION_TIMEOUT = Duration.ofSeconds(90);

    private static final Logger logger = LoggerFactory.getLogger(RuleLearning.class);
    private static final ExecutorService BACKGROUND = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "kortty-rule-learning");
        thread.setDaemon(true);
        return thread;
    });

    private RuleLearning() {
    }

    /**
     * After the chat answer {@code runId} to the user's {@code message}; a proposal found is passed to {@code show} on the FX
     * thread. At most one card per {@code runId}.
     */
    static void afterChatAnswer(@Nullable AiProfile profile, String message, String runId,
                                @Nullable ServerConnection connection, Consumer<RuleProposal> show) {
        detect(profile, message, RuleSource.CHAT, runId, connection, show);
    }

    /**
     * After the agent run {@code runId} whose request was {@code prompt}; a proposal found is passed to {@code show} on the
     * FX thread. At most one card per {@code runId}.
     */
    static void afterAgentRun(@Nullable AiProfile profile, String prompt, String runId,
                              @Nullable ServerConnection connection, Consumer<RuleProposal> show) {
        detect(profile, prompt, RuleSource.AGENT, runId, connection, show);
    }

    /** Runs {@code task} on the background threads of the rule learning, for the file writes of the learning state. */
    static void runInBackground(Runnable task) {
        CompletableFuture.runAsync(task, BACKGROUND);
    }

    /** The proposal of a rule the user typed or selected, with the connection it was typed in when there is one. */
    static RuleProposal manualProposal(String text, @Nullable ServerConnection connection) {
        return new RuleProposal(text, RuleSource.MANUAL,
            connection == null ? null : connection.getId(),
            connection == null ? null : connection.getDisplayName(),
            connection == null ? null : connection.getHost(),
            null);
    }

    /** Whether a rule typed by the user has at least one place to go under the current settings and policy. */
    static boolean manualRuleAllowed() {
        return !SolutionChoiceRules.Setup.of(settingsManager()).targets(RuleProposal.of("", RuleSource.MANUAL)).isEmpty();
    }

    /** The settings, policy and memory state of this moment, read for one detection. */
    static RuleDetector.Settings currentSettings() {
        GlobalSettings settings = settingsManager() != null ? settingsManager().getSettings() : null;
        return RuleDetector.Settings.of(settings, PolicyManager.effective(), MemoryService.get(), BuiltinAiSkillCatalog.load());
    }

    /** Opens the "Als Regel merken" dialog for {@code text} over {@code owner}, unless the text is blank. */
    static void rememberManually(@Nullable Window owner, String text, @Nullable ServerConnection connection) {
        String rule = text == null ? "" : text.strip();
        if (rule.isEmpty()) {
            return;
        }
        RuleProposalDialog.show(owner, manualProposal(rule, connection));
    }

    private static void detect(@Nullable AiProfile profile, String message, RuleSource source, String runId,
                               @Nullable ServerConnection connection, Consumer<RuleProposal> show) {
        if (profile == null || message == null || message.isBlank() || runId == null || runId.isBlank()) {
            return;
        }
        RuleDetector.Origin origin = new RuleDetector.Origin(runId, source,
            connection == null ? null : connection.getId(),
            connection == null ? null : connection.getDisplayName(),
            connection == null ? null : connection.getHost());
        AiMemoryAssistant assistant = new AiMemoryAssistant(KorTTYApplication.getInstance());
        AtomicBoolean abandoned = new AtomicBoolean();
        CompletableFuture
            .supplyAsync(() -> new RuleDetector(LearningState.shared(), RuleLearning::currentSettings)
                .detect(message, origin,
                    abandonable((system, user) -> assistant.complete(profile, system, user), abandoned)), BACKGROUND)
            .orTimeout(DETECTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((proposal, failure) -> {
                if (failure != null) {
                    // The caller gave up: the detection still running must not claim a daily card no one is shown.
                    abandoned.set(true);
                    logger.debug("The rule detection gave no proposal", failure);
                    return;
                }
                proposal.ifPresent(found -> Platform.runLater(() -> show.accept(found)));
            });
    }

    /**
     * The assistant of a detection that may be abandoned after its timeout: once {@code abandoned} is set, no further model
     * call is made, and a reply that arrives late is refused before the detection can claim a card for it.
     */
    static RuleDetector.Assistant abandonable(RuleDetector.Assistant assistant, AtomicBoolean abandoned) {
        return (system, user) -> {
            if (abandoned.get()) {
                throw new CancellationException("The rule detection was abandoned");
            }
            String reply = assistant.complete(system, user);
            if (abandoned.get()) {
                throw new CancellationException("The rule detection was abandoned");
            }
            return reply;
        };
    }

    private static @Nullable GlobalSettingsManager settingsManager() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null ? application.getGlobalSettingsManager() : null;
    }
}
