package de.kortty.core;

import de.kortty.core.agent.AgentOutboundContext;
import de.kortty.memory.MemoryBlock;
import de.kortty.model.AiAgentTriageMode;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import de.kortty.policy.EffectivePolicy;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * The complexity check and the solution card of an agent run: when the check runs, what a failing check does, and what
 * each answer of the card does to the request.
 */
class TerminalAgentTriageTest {

    private static final String SECRET = "hunter2-secret";
    private static final String MEMORY_TEXT = "Always answer in short sentences.";
    private static final Duration LONG = Duration.ofSeconds(30);

    @Test
    void aMalformedReplyRunsTheRequestAsAsked() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, null);
        TriageAi ai = new TriageAi("not json at all", null, 0);
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(LONG, LONG).offerSolutionChoice(
            null, ai, outbound(), request, probe(), null, AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(outcome).hasValue(request);
        assertThat(ui.proposals).isEmpty();
        assertThat(ai.userPrompts).hasSize(1);
        assertThat(ui.thinkingSummaries()).contains("The complexity check gave no usable answer, so the request runs as asked.");
    }

    @Test
    void aFailingCallRunsTheRequestAsAsked() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, null);
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(null, new IllegalStateException("provider down"), 0), outbound(), request, probe(), null,
            AiAgentTriageMode.ALWAYS, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(outcome).hasValue(request);
        assertThat(ui.proposals).isEmpty();
        assertThat(ui.thinkingSummaries()).contains("The complexity check failed, so the request runs as asked.");
    }

    @Test
    void aCallThatTheProviderStopsRunsTheRequestAsAsked() throws Exception {
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(null, new AiCancelledException("The AI request was stopped.", null), 0), outbound(),
            request, probe(), null, AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), new ChoiceUi(true, null), "run-1");

        assertThat(outcome).hasValue(request);
    }

    @Test
    void aCallThatTakesTooLongRunsTheRequestAsAsked() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, null);
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(Duration.ofMillis(200), LONG).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 10_000), outbound(), request, probe(), null,
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(outcome).hasValue(request);
        assertThat(ui.proposals).isEmpty();
    }

    @Test
    void aUiThatDoesNotSupportChoicesNeverMakesTheCall() throws Exception {
        ChoiceUi ui = new ChoiceUi(false, null);
        TriageAi ai = new TriageAi(complexReply(), null, 0);
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(LONG, LONG).offerSolutionChoice(
            null, ai, outbound(), request, probe(), null, AiAgentTriageMode.ALWAYS, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(outcome).hasValue(request);
        assertThat(ai.userPrompts).isEmpty();
    }

    @Test
    void neverModeAndAPolicyThatForbidsPlanningSkipTheCall() throws Exception {
        TriageAi ai = new TriageAi(complexReply(), null, 0);
        TerminalAgentModels.Request request = request("");

        assertThat(service(LONG, LONG).offerSolutionChoice(null, ai, outbound(), request, probe(), null,
            AiAgentTriageMode.NEVER, EffectivePolicy.unrestricted(), new ChoiceUi(true, null), "run-1")).hasValue(request);
        assertThat(service(LONG, LONG).offerSolutionChoice(null, ai, outbound(), request, probe(), null,
            AiAgentTriageMode.ALWAYS, EffectivePolicy.lockdown(), new ChoiceUi(true, null), "run-1")).hasValue(request);
        assertThat(ai.userPrompts).isEmpty();
    }

    @Test
    void aQuestionAndARunWithAnAcceptedPlanSkipTheCall() throws Exception {
        TriageAi ai = new TriageAi(complexReply(), null, 0);
        TerminalAgentModels.Request question = request("");
        TerminalAgentModels.Request withPlan = request("Accepted plan option: earlier");

        assertThat(TerminalAgentService.triageRequested(new ChoiceUi(true, null),
            queryOnly(question), EffectivePolicy.unrestricted(), AiAgentTriageMode.AUTO)).isFalse();
        assertThat(TerminalAgentService.triageRequested(new ChoiceUi(true, null),
            withPlan, EffectivePolicy.unrestricted(), AiAgentTriageMode.AUTO)).isFalse();
        assertThat(TerminalAgentService.triageRequested(new ChoiceUi(true, null),
            question, EffectivePolicy.unrestricted(), AiAgentTriageMode.AUTO)).isTrue();
        assertThat(ai.userPrompts).isEmpty();
    }

    @Test
    void autoRunsASimpleRequestWithoutAsking() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, null);
        TriageAi ai = new TriageAi(simpleReply(), null, 0);
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(LONG, LONG).offerSolutionChoice(
            null, ai, outbound(), request, probe(), null, AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(outcome).hasValue(request);
        assertThat(ai.userPrompts).hasSize(1);
        assertThat(ui.proposals).isEmpty();
    }

    @Test
    void alwaysOffersTheOptionsForASimpleRequest() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, TerminalAgentModels.SolutionChoice.asRequested());
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(simpleReply(), null, 0), outbound(), request, probe(), null,
            AiAgentTriageMode.ALWAYS, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(ui.proposals).hasSize(1);
        assertThat(ui.proposals.get(0).complex()).isFalse();
        assertThat(outcome).hasValue(request);
    }

    @Test
    void autoOffersTheOptionsForAComplexRequestAndAnOptionBecomesThePlan() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, TerminalAgentModels.SolutionChoice.option(1, "Keep the logs."));
        TerminalAgentModels.Request request = request("");

        TerminalAgentModels.Request chosen = service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 0), outbound(), request, probe(), "conn-1",
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1").orElseThrow();

        assertThat(ui.proposals).hasSize(1);
        TerminalAgentModels.TriageProposal proposal = ui.proposals.get(0);
        assertThat(proposal.connectionId()).isEqualTo("conn-1");
        assertThat(proposal.connectionName()).isEqualTo("Fedora44");
        assertThat(chosen.acceptedPlanContext()).contains("Accepted plan option: Blue-green");
        assertThat(chosen.acceptedPlanContext()).contains("Start a second unit first");
        assertThat(chosen.acceptedPlanContext()).contains("Additional instructions from the user:\nKeep the logs.");
        assertThat(chosen.userPrompt()).isEqualTo(request.userPrompt());
        assertThat(chosen.sessionId()).isEqualTo(request.sessionId());
    }

    @Test
    void anOwnIdeaBecomesThePlan() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, TerminalAgentModels.SolutionChoice.ownIdea("Use a rolling restart.", null));

        TerminalAgentModels.Request chosen = service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 0), outbound(), request(""), probe(), null,
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1").orElseThrow();

        assertThat(chosen.acceptedPlanContext()).contains("Accepted plan option: Own idea");
        assertThat(chosen.acceptedPlanContext()).contains("Summary: Use a rolling restart.");
    }

    @Test
    void theChosenPlanIsSentWithTheNextAgentPrompt() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, TerminalAgentModels.SolutionChoice.option(1, null));
        TerminalAgentModels.Request chosen = service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 0), outbound(), request(""), probe(), null,
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1").orElseThrow();

        TriageAi next = new TriageAi(doneReply(), null, 0);
        service(LONG, LONG).requestAgentDecision(next, chosen, probe(), List.of(), 1, false, ui, "run-1");

        assertThat(next.userPrompts).hasSize(1);
        assertThat(next.userPrompts.get(0)).contains("Accepted plan context:");
        assertThat(next.userPrompts.get(0)).contains("Accepted plan option: Blue-green");
    }

    @Test
    void cancelEndsTheRunBeforeItStarts() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, TerminalAgentModels.SolutionChoice.cancel());

        Optional<TerminalAgentModels.Request> outcome = service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 0), outbound(), request(""), probe(), null,
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(outcome).isEmpty();
        assertThat(ui.phases()).contains(TerminalAgentModels.Phase.CANCELLED);
    }

    @Test
    void anAnswerThatTakesTooLongRunsTheRequestAsAskedAndReleasesTheCard() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, null);
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(LONG, Duration.ofMillis(300)).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 0), outbound(), request, probe(), null,
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(outcome).hasValue(request);
        assertThat(ui.awaitInterrupted()).isTrue();
    }

    @Test
    void theAnswerTimeStartsWhenTheCardIsShownNotWhileItWaitsInAQueue() throws Exception {
        // The card sits behind another prompt for longer than the answer time; once shown, the user answers at once.
        ChoiceUi ui = new ChoiceUi(true, TerminalAgentModels.SolutionChoice.option(1, "Keep the logs."), 900);
        TerminalAgentModels.Request request = request("");

        TerminalAgentModels.Request chosen = service(LONG, Duration.ofMillis(300)).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 0), outbound(), request, probe(), null,
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1").orElseThrow();

        assertThat(chosen.acceptedPlanContext()).contains("Accepted plan option: Blue-green");
        assertThat(ui.proposals).hasSize(1);
    }

    @Test
    void cancellingTheRunWhileTheCardWaitsEndsTheRun() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, null);
        new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ui.cancelled = true;
        }).start();

        expectThrows(TerminalAgentService.AgentCancelledException.class, () -> service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 0), outbound(), request(""), probe(), null,
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1"));
        assertThat(ui.awaitInterrupted()).isTrue();
    }

    @Test
    void aCardThatFailsRunsTheRequestAsAsked() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, null);
        ui.choiceFailure = new IllegalStateException("no window");
        TerminalAgentModels.Request request = request("");

        Optional<TerminalAgentModels.Request> outcome = service(LONG, LONG).offerSolutionChoice(
            null, new TriageAi(complexReply(), null, 0), outbound(), request, probe(), null,
            AiAgentTriageMode.AUTO, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(outcome).hasValue(request);
    }

    @Test
    void theCheckCarriesTheMemoryAndMasksTheRunsSecrets() throws Exception {
        ChoiceUi ui = new ChoiceUi(true, TerminalAgentModels.SolutionChoice.asRequested());
        TriageAi ai = new TriageAi(simpleReply(), null, 0);
        AgentOutboundContext outbound = AgentOutboundContext.forRun(null,
            new MemoryBlock(MEMORY_TEXT, 12, List.of(), 0), null);
        outbound.addSecret(SECRET.toCharArray());
        TerminalAgentModels.Request request = new TerminalAgentModels.Request(
            "session-1", "profile-1", "Restart the service with password " + SECRET, "Fedora44", "",
            TerminalAgentExecutionTarget.CHAT_WINDOW, false, false, false, false, false, false);

        service(LONG, LONG).offerSolutionChoice(null, ai, outbound, request, probe(), null,
            AiAgentTriageMode.ALWAYS, EffectivePolicy.unrestricted(), ui, "run-1");

        assertThat(ai.systemPrompts.get(0)).contains(MEMORY_TEXT);
        assertThat(ai.userPrompts.get(0)).doesNotContain(SECRET);
    }

    private static TerminalAgentService service(Duration triageTimeout, Duration choiceTimeout) {
        return new TerminalAgentService(triageTimeout, choiceTimeout);
    }

    private static AgentOutboundContext outbound() {
        return AgentOutboundContext.forRun(null, MemoryBlock.empty(), null);
    }

    private static TerminalAgentModels.Request request(String acceptedPlanContext) {
        return new TerminalAgentModels.Request(
            "session-1", "profile-1", "Restart the nginx service", "Fedora44", acceptedPlanContext,
            TerminalAgentExecutionTarget.CHAT_WINDOW, false, false, false, false, false, false);
    }

    private static TerminalAgentModels.Request queryOnly(TerminalAgentModels.Request request) {
        return new TerminalAgentModels.Request(
            request.sessionId(), request.profileId(), request.userPrompt(), request.connectionDisplayName(),
            request.acceptedPlanContext(), request.executionTarget(), false, false, false, false, false, true);
    }

    private static TerminalAgentModels.ProbeSnapshot probe() {
        return new TerminalAgentModels.ProbeSnapshot(
            "Fedora Linux 44", "kernel", "aarch64", "bash", "daniel", "1000", "1000", List.of("wheel"),
            "/home/daniel", "/home/daniel", null, "", List.of("dnf"), List.of("systemctl"), false, true, false,
            false, "", "sudo_password");
    }

    private static String complexReply() {
        return reply("complex");
    }

    private static String simpleReply() {
        return reply("simple");
    }

    private static String reply(String complexity) {
        return """
            {"complexity": "%s", "reasons": ["Restarts a service"], "recommended": 1, "options": [
              {"title": "Restart in place", "summary": "Restart the unit", "feasibility": "High",
               "risks": ["Downtime"], "prerequisites": ["Sudo"], "steps": ["Restart"], "alternatives": ["Reload"]},
              {"title": "Blue-green", "summary": "Start a second unit first", "feasibility": "Medium",
               "risks": [], "prerequisites": [], "steps": ["Start", "Switch"], "alternatives": []},
              {"title": "By hand", "summary": "Edit the files manually", "feasibility": "Low",
               "risks": [], "prerequisites": [], "steps": ["Edit"], "alternatives": []}]}
            """.formatted(complexity);
    }

    private static String doneReply() {
        return "{\"status\":\"done\",\"summary\":\"Done\",\"userMessage\":\"Done.\",\"commands\":[],\"needsReprobe\":false}";
    }

    /** An AI service that answers with a fixed reply, or fails, after an optional delay; it records the prompts. */
    private static final class TriageAi extends OpenAiCompatibleAiService {
        private final String reply;
        private final RuntimeException failure;
        private final long delayMillis;
        private final List<String> systemPrompts = new CopyOnWriteArrayList<>();
        private final List<String> userPrompts = new CopyOnWriteArrayList<>();

        private TriageAi(String reply, RuntimeException failure, long delayMillis) {
            super("http://localhost", "test-model", "");
            this.reply = reply;
            this.failure = failure;
            this.delayMillis = delayMillis;
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("The call was stopped", e);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return new AiExecutionResult(reply, null);
        }
    }

    /** A UI that records the cards, the states and the activities; the card answers with {@code answer}, or waits. */
    private static final class ChoiceUi implements TerminalAgentService.RunUi {
        private final boolean supportsChoices;
        private final TerminalAgentModels.SolutionChoice answer;
        private final long queueMillis;
        private final List<TerminalAgentModels.TriageProposal> proposals = new CopyOnWriteArrayList<>();
        private final List<TerminalAgentModels.RunState> states = new CopyOnWriteArrayList<>();
        private final List<TerminalAgentModels.AgentActivity> activities = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        private volatile boolean interrupted;
        private volatile RuntimeException choiceFailure;

        private ChoiceUi(boolean supportsChoices, TerminalAgentModels.SolutionChoice answer) {
            this(supportsChoices, answer, 0);
        }

        /** A card that waits {@code queueMillis} before it is shown, as behind another prompt. */
        private ChoiceUi(boolean supportsChoices, TerminalAgentModels.SolutionChoice answer, long queueMillis) {
            this.supportsChoices = supportsChoices;
            this.answer = answer;
            this.queueMillis = queueMillis;
        }

        @Override
        public TerminalAgentModels.SolutionChoice requestSolutionChoice(
            TerminalAgentModels.TriageProposal proposal,
            Runnable shown) throws Exception {
            if (queueMillis == 0) {
                return TerminalAgentService.RunUi.super.requestSolutionChoice(proposal, shown);
            }
            proposals.add(proposal);
            Thread.sleep(queueMillis);
            shown.run();
            return answer;
        }

        @Override
        public void updateState(TerminalAgentModels.RunState state) {
            states.add(state);
        }

        @Override
        public void appendTranscript(String text) {
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest request) {
            return null;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void publishActivity(TerminalAgentModels.AgentActivity activity) {
            activities.add(activity);
        }

        @Override
        public boolean supportsChoices() {
            return supportsChoices;
        }

        @Override
        public TerminalAgentModels.SolutionChoice requestSolutionChoice(TerminalAgentModels.TriageProposal proposal)
            throws Exception {
            proposals.add(proposal);
            if (choiceFailure != null) {
                throw choiceFailure;
            }
            if (answer != null) {
                return answer;
            }
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
            return TerminalAgentModels.SolutionChoice.asRequested();
        }

        private List<String> thinkingSummaries() {
            List<String> summaries = new ArrayList<>();
            for (TerminalAgentModels.AgentActivity activity : activities) {
                if (activity.type() == TerminalAgentModels.AgentActivityType.THINKING) {
                    summaries.add(activity.summary());
                }
            }
            return summaries;
        }

        private List<TerminalAgentModels.Phase> phases() {
            return states.stream().map(TerminalAgentModels.RunState::phase).toList();
        }

        /** Whether the waiting card was interrupted, after a short wait for the helper thread to notice. */
        private boolean awaitInterrupted() throws InterruptedException {
            for (int attempt = 0; attempt < 40 && !interrupted; attempt++) {
                Thread.sleep(50);
            }
            return interrupted;
        }
    }
}
