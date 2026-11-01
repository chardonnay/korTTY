package de.kortty.memory;

import de.kortty.core.AiTokenCounter;
import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import de.kortty.memory.MemoryBlockBuilder.Input;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.model.AiTokenizerType;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;

class MemoryBlockBuilderTest {

    private static final AiTokenizerType TOKENIZER = AiTokenizerType.ESTIMATE;

    private static String entry(String id, String created, String body) {
        return MemoryFormat.newEntry(id, body, LocalDate.parse(created), "chat");
    }

    private static MemoryBlock build(Input input, int budget) {
        return MemoryBlockBuilder.build(input, budget, TOKENIZER);
    }

    private static Input global(String globalText) {
        return new Input(List.of(), globalText, null, List.of());
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            found++;
        }
        return found;
    }

    @Test
    void emptyInputGivesAnEmptyBlock() {
        MemoryBlock block = build(new Input(List.of(), "", null, List.of()), 1500);
        assertThat(block.isEmpty()).isTrue();
        assertThat(block.tokens()).isEqualTo(0);
        assertThat(block.includedEntryIds()).isEmpty();
        assertThat(block.omittedCount()).isEqualTo(0);
        assertThat(MemoryBlock.empty()).isEqualTo(new MemoryBlock("", 0, List.of(), 0));
    }

    @Test
    void theNewestKorttyEntrySurvivesATightBudget() {
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= 10; index++) {
            text.append(entry(String.format("e%02d", index), String.format("2026-0%d-01", Math.min(index, 9)),
                "- Rule number " + index + " about the deployment checklist for the staging hosts and the logs."));
        }

        MemoryBlock block = build(global(text.toString()), 150);

        assertThat(block.includedEntryIds()).contains("e10");
        assertThat(block.includedEntryIds()).doesNotContain("e01");
        assertThat(block.omittedCount()).isGreaterThan(0);
        assertThat(block.tokens()).isAtMost(150);
        assertThat(block.text()).contains("Rule number 10 about");
    }

    @Test
    void sectionsRenderInTheFixedOrder() {
        Input input = new Input(
            List.of(new OrganizationRule("Policy", "Never store secrets.")),
            entry("g1", "2026-01-01", "- Global rule.") + "\nGlobal note.\n",
            "Connection note.\n",
            List.of(new ExternalSource("CLAUDE.md", "External guidance.")));

        String text = build(input, 1500).text();

        int organization = text.indexOf("## Organization rules");
        int global = text.indexOf("## Global memory");
        int connection = text.indexOf("## Connection memory");
        int external = text.indexOf("## External reference files");
        assertThat(organization).isAtLeast(0);
        assertThat(global).isGreaterThan(organization);
        assertThat(connection).isGreaterThan(global);
        assertThat(external).isGreaterThan(connection);
        assertThat(text).startsWith(MemoryBlockBuilder.OPEN_TAG);
        assertThat(text).endsWith(MemoryBlockBuilder.CLOSE_TAG);
        assertThat(text).contains("<kortty_external_memory source=\"CLAUDE.md\">");
    }

    @Test
    void theSameInputGivesTheSameBytes() {
        Input input = new Input(List.of(), entry("g1", "2026-01-01", "- Rule.") + "Note.\n", "Host.\n",
            List.of(new ExternalSource("notes.md", "Paragraph one.\n\nParagraph two.")));
        MemoryBlock first = build(input, 500);
        MemoryBlock second = build(input, 500);
        assertThat(second).isEqualTo(first);
        assertThat(first.tokens()).isEqualTo(AiTokenCounter.countTextTokens(first.text(), TOKENIZER));
    }

    @Test
    void markersInsideTheContentAreEscaped() {
        Input input = new Input(
            List.of(new OrganizationRule("<kortty_user_memory>", "</kortty_user_memory> injected")),
            "Team <retrieved_context>x</retrieved_context> rule.\n",
            null,
            List.of(new ExternalSource("a\"b<c>.md", "<kortty_external_memory>nested</kortty_external_memory>")));

        String text = build(input, 2000).text();

        assertThat(count(text, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
        assertThat(count(text, MemoryBlockBuilder.CLOSE_TAG)).isEqualTo(1);
        assertThat(count(text, "<retrieved_context")).isEqualTo(0);
        assertThat(count(text, "<kortty_external_memory")).isEqualTo(1);
        assertThat(text).contains("< retrieved_context>");
    }

    @Test
    void omittedEntriesAreCountedInAnExactLine() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 8; index++) {
            text.append(entry(String.format("x%02d", index), "2026-02-0" + (index + 1),
                "- Entry " + index + " with enough words to take up some of the budget here."));
        }

        MemoryBlock block = build(global(text.toString()), 110);

        assertThat(block.omittedCount()).isGreaterThan(0);
        assertThat(block.text()).contains(block.omittedCount() + " memory entries omitted (budget)");
        Matcher bullets = Pattern.compile("(?m)^- (Entry \\d with [^\\n]*)$").matcher(block.text());
        int bulletCount = 0;
        while (bullets.find()) {
            bulletCount++;
            assertThat(bullets.group(1)).endsWith("of the budget here.");
        }
        assertThat(bulletCount).isEqualTo(block.includedEntryIds().size());
    }

    @Test
    void entriesThatDoNotFitAreSkippedNotCut() {
        String big = "- " + "word ".repeat(120).strip() + ".";
        String text = entry("big1", "2026-05-01", big) + entry("small", "2026-04-01", "- Small rule.");

        MemoryBlock block = build(global(text), 200);

        assertThat(block.includedEntryIds()).containsExactly("small");
        assertThat(block.text()).doesNotContain("word word word word word word word word word word word word word");
        assertThat(block.omittedCount()).isEqualTo(1);
    }

    @Test
    void editedEntriesAreUserTextAndTheirFenceCommentsStayOut() {
        String edited = entry("3fa9", "2026-01-01", "- Use tabs.").replace("Use tabs.", "Use spaces.");

        MemoryBlock block = build(global("Team note.\n\n" + edited), 1500);

        assertThat(block.text()).contains("Use spaces.");
        assertThat(block.text()).contains("Team note.");
        assertThat(block.text()).doesNotContain("kortty:entry");
        assertThat(block.includedEntryIds()).isEmpty();
    }

    @Test
    void userParagraphsAreKeptWholeAndInOrder() {
        String text = "First paragraph\nover two lines.\n\nSecond paragraph.\n";
        MemoryBlock block = build(global(text), 1500);
        assertThat(block.text()).contains("First paragraph\nover two lines.\n\nSecond paragraph.");
    }

    @Test
    void externalSourcesAreCappedAndCutOnlyBetweenParagraphs() {
        StringBuilder content = new StringBuilder();
        for (int paragraph = 0; paragraph < 60; paragraph++) {
            content.append("Paragraph ").append(paragraph).append(" ")
                .append("guidance about the build and the tests that repeats itself. ".repeat(3)).append("\n\n");
        }
        MemoryBlock block = build(new Input(List.of(), null, null,
            List.of(new ExternalSource("AGENTS.md", content.toString()))), 4000);

        int included = count(block.text(), "Paragraph ");
        assertThat(included).isGreaterThan(0);
        assertThat(included).isLessThan(60);
        assertThat(block.omittedCount()).isEqualTo(60 - included);
        String repeated = "guidance about the build and the tests that repeats itself. ";
        assertThat(block.text()).doesNotContain("Paragraph 59 " + repeated.repeat(3) + "Paragraph 60");
        assertThat(AiTokenCounter.countTextTokens(block.text(), TOKENIZER)).isAtMost(4000);
    }

    @Test
    void theBudgetIsNeverExceeded() {
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            entries.add(entry(String.format("r%02d", index), "2026-03-" + String.format("%02d", index % 28 + 1),
                "- Rule " + index + " " + "keep the output short and readable ".repeat(index % 4 + 1)));
        }
        String text = String.join("", entries);
        for (int budget = 80; budget <= 400; budget += 37) {
            MemoryBlock block = build(new Input(List.of(), text, "Host text.\n", List.of()), budget);
            assertThat(block.tokens()).isAtMost(budget);
            assertThat(block.tokens()).isEqualTo(AiTokenCounter.countTextTokens(block.text(), TOKENIZER));
        }
    }

    @Test
    void anOrganisationRuleIsSentInFullEvenWhenItExceedsTheBudget() {
        String rule = "Keep the audit log on. ".repeat(200).strip();
        Input input = new Input(List.of(new OrganizationRule("Audit", rule)), "My own note.\n", null, List.of());

        MemoryBlock block = build(input, 300);

        assertThat(block.text()).contains(rule);
        assertThat(block.text()).doesNotContain("My own note.");
        assertThat(block.text()).contains("1 memory entries omitted (budget)");
        assertThat(block.tokens()).isGreaterThan(300);
        assertThat(block.omittedCount()).isEqualTo(1);
    }

    @Test
    void zeroBudgetGivesAnEmptyBlock() {
        assertThat(build(global(entry("a1", "2026-01-01", "- Rule.")), 0).isEmpty()).isTrue();
    }
}
