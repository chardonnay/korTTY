package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class AgentPromptQueueTest {

    /** The prompts that still wait for an answer; a test answers one by removing it. */
    private final Set<String> waiting = new HashSet<>();

    private AgentPromptQueue<String> queue() {
        return new AgentPromptQueue<>(waiting::contains);
    }

    private String register(AgentPromptQueue<String> queue, String prompt, AgentPromptQueue.Kind kind) {
        waiting.add(prompt);
        queue.open(prompt, kind);
        return prompt;
    }

    @Test
    void aCardWaitsBehindAnApprovalAndFollowsWhenTheApprovalCloses() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        register(queue, "card-c", AgentPromptQueue.Kind.CHOICE);

        assertThat(queue.shown()).isEqualTo("approval-a");

        waiting.remove("approval-a");
        assertThat(queue.close("approval-a")).isTrue();
        assertThat(queue.shown()).isEqualTo("card-c");
    }

    @Test
    void anApprovalTakesThePlaceOfAShownCardAndTheCardComesBack() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "card-c", AgentPromptQueue.Kind.CHOICE);
        assertThat(queue.shown()).isEqualTo("card-c");

        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        assertThat(queue.shown()).isEqualTo("approval-a");

        waiting.remove("approval-a");
        assertThat(queue.close("approval-a")).isTrue();
        assertThat(queue.shown()).isEqualTo("card-c");
    }

    @Test
    void approvalsComeBeforeCardsWhenTheScreenFrees() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        register(queue, "card-c", AgentPromptQueue.Kind.CHOICE);
        register(queue, "approval-b", AgentPromptQueue.Kind.APPROVAL);
        assertThat(queue.shown()).isEqualTo("approval-b");

        waiting.remove("approval-b");
        queue.close("approval-b");

        assertThat(queue.shown()).isEqualTo("approval-a");
    }

    @Test
    void aCardOpensAtOnceWhenNothingIsRegistered() {
        AgentPromptQueue<String> queue = queue();

        assertThat(queue.open("card-c", AgentPromptQueue.Kind.CHOICE)).isTrue();
        assertThat(queue.shown()).isEqualTo("card-c");
    }

    @Test
    void aSecondCardWaitsForTheFirstOne() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "card-c", AgentPromptQueue.Kind.CHOICE);
        waiting.add("card-d");

        assertThat(queue.open("card-d", AgentPromptQueue.Kind.CHOICE)).isFalse();
        assertThat(queue.shown()).isEqualTo("card-c");

        waiting.remove("card-c");
        assertThat(queue.close("card-c")).isTrue();
        assertThat(queue.shown()).isEqualTo("card-d");
    }

    @Test
    void aCardWaitsWhileAnAnsweredApprovalIsStillOnScreen() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        waiting.remove("approval-a");
        waiting.add("card-c");

        assertThat(queue.open("card-c", AgentPromptQueue.Kind.CHOICE)).isFalse();
        assertThat(queue.close("approval-a")).isTrue();
        assertThat(queue.shown()).isEqualTo("card-c");
    }

    @Test
    void anAnsweredCardIsNotShownWhenTheApprovalCloses() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        register(queue, "card-c", AgentPromptQueue.Kind.CHOICE);
        waiting.remove("card-c");

        assertThat(queue.close("approval-a")).isTrue();
        assertThat(queue.shown()).isNull();
        assertThat(queue.close("card-c")).isFalse();
    }

    @Test
    void closingAPromptThatIsNotOnScreenChangesNothing() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        register(queue, "card-c", AgentPromptQueue.Kind.CHOICE);

        waiting.remove("card-c");
        assertThat(queue.close("card-c")).isFalse();
        assertThat(queue.shown()).isEqualTo("approval-a");
    }

    @Test
    void aPasswordIsShownLikeAnApproval() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "card-c", AgentPromptQueue.Kind.CHOICE);
        register(queue, "password-p", AgentPromptQueue.Kind.PASSWORD);
        assertThat(queue.shown()).isEqualTo("password-p");

        waiting.remove("password-p");
        queue.close("password-p");

        assertThat(queue.shown()).isEqualTo("card-c");
    }

    @Test
    void aCardQueuedBehindAnApprovalRunsItsCallbackOnlyWhenItIsBroughtOnScreen() {
        AgentPromptQueue<String> queue = queue();
        List<String> shownCards = new ArrayList<>();
        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        waiting.add("card-c");

        assertThat(queue.open("card-c", AgentPromptQueue.Kind.CHOICE, () -> shownCards.add("card-c"))).isFalse();
        assertThat(shownCards).isEmpty();

        waiting.remove("approval-a");
        assertThat(queue.close("approval-a")).isTrue();
        assertThat(queue.shown()).isEqualTo("card-c");
        assertThat(shownCards).containsExactly("card-c");
    }

    @Test
    void aCardOpenedAtOnceRunsItsCallbackAtOnce() {
        AgentPromptQueue<String> queue = queue();
        List<String> shownCards = new ArrayList<>();

        assertThat(queue.open("card-c", AgentPromptQueue.Kind.CHOICE, () -> shownCards.add("card-c"))).isTrue();
        assertThat(shownCards).containsExactly("card-c");
    }

    @Test
    void aCardCoveredByAnApprovalAndBroughtBackRunsItsCallbackOnlyOnce() {
        AgentPromptQueue<String> queue = queue();
        List<String> shownCards = new ArrayList<>();
        waiting.add("card-c");
        assertThat(queue.open("card-c", AgentPromptQueue.Kind.CHOICE, () -> shownCards.add("card-c"))).isTrue();

        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        waiting.remove("approval-a");
        assertThat(queue.close("approval-a")).isTrue();

        assertThat(queue.shown()).isEqualTo("card-c");
        assertThat(shownCards).containsExactly("card-c");
    }

    @Test
    void aCardClosedBeforeItIsShownNeverRunsItsCallback() {
        AgentPromptQueue<String> queue = queue();
        List<String> shownCards = new ArrayList<>();
        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);
        waiting.add("card-c");
        queue.open("card-c", AgentPromptQueue.Kind.CHOICE, () -> shownCards.add("card-c"));

        waiting.remove("card-c");
        assertThat(queue.close("card-c")).isFalse();
        waiting.remove("approval-a");
        queue.close("approval-a");

        assertThat(queue.shown()).isNull();
        assertThat(shownCards).isEmpty();
    }

    @Test
    void theQueueIsEmptyAfterTheLastPromptCloses() {
        AgentPromptQueue<String> queue = queue();
        register(queue, "approval-a", AgentPromptQueue.Kind.APPROVAL);

        waiting.remove("approval-a");
        assertThat(queue.close("approval-a")).isTrue();
        assertThat(queue.shown()).isNull();
        assertThat(queue.open("card-c", AgentPromptQueue.Kind.CHOICE)).isTrue();
    }
}
