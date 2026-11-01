package de.kortty.memory;

import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryDocument.Segment;
import de.kortty.memory.MemoryDocument.UserText;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class MemoryFormatTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    private static String entry(String id, String body) {
        return MemoryFormat.newEntry(id, body, DAY, "chat");
    }

    @Test
    void parseThenToTextRestoresTheOriginalBytes() {
        String text = "My own notes.\r\n\r\n" + entry("3fa9", "- Use `state` for static Perl variables.")
            + "\nTrailing user line without a newline";
        MemoryDocument document = MemoryFormat.parse(text);
        assertThat(document.toText()).isEqualTo(text);
        assertThat(document.segments()).hasSize(3);
        assertThat(document.segments().get(0)).isInstanceOf(UserText.class);
        assertThat(document.segments().get(1)).isInstanceOf(KorttyEntry.class);
    }

    @Test
    void emptyTextParsesToNoSegments() {
        assertThat(MemoryFormat.parse("").segments()).isEmpty();
        assertThat(MemoryFormat.parse(null).toText()).isEmpty();
    }

    @Test
    void newEntryIsOwnedAndParsesBackWithItsAttributes() {
        String fence = entry("3fa9", "- Prefer ssh -J over agent forwarding.");
        assertThat(MemoryFormat.isKorttyOwned(fence)).isTrue();
        KorttyEntry parsed = MemoryFormat.parse(fence).entries().get(0);
        assertThat(parsed.id()).isEqualTo("3fa9");
        assertThat(parsed.created()).isEqualTo("2026-11-02");
        assertThat(parsed.source()).isEqualTo("chat");
        assertThat(parsed.body()).isEqualTo("- Prefer ssh -J over agent forwarding.");
        assertThat(parsed.sha256()).isEqualTo(MemoryFormat.sha256(parsed.body()));
    }

    @Test
    void editedEntryBodyBecomesUserText() {
        String fence = entry("3fa9", "- Use tabs.");
        String edited = fence.replace("- Use tabs.", "- Use spaces.");
        assertThat(MemoryFormat.isKorttyOwned(edited)).isFalse();
        MemoryDocument document = MemoryFormat.parse(edited);
        assertThat(document.entries()).isEmpty();
        assertThat(document.segments()).hasSize(1);
        assertThat(document.toText()).isEqualTo(edited);
    }

    @Test
    void fenceWithAnotherOriginOrMissingCloseLineIsUserText() {
        String foreign = entry("3fa9", "- Rule.").replace("origin=korTTY", "origin=other");
        assertThat(MemoryFormat.isKorttyOwned(foreign)).isFalse();
        String unclosed = entry("3fa9", "- Rule.").replace("<!-- /kortty:entry -->\n", "");
        assertThat(MemoryFormat.parse(unclosed).entries()).isEmpty();
        assertThat(MemoryFormat.parse(unclosed).toText()).isEqualTo(unclosed);
    }

    @Test
    void fenceMarkersInsideABodyAreEscapedSoTheEntryStaysWhole() {
        String fence = entry("3fa9", "- Note: <!-- /kortty:entry --> is a marker.");
        List<KorttyEntry> entries = MemoryFormat.parse(fence).entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).body()).contains("< !-- /kortty:entry");
    }

    @Test
    void userSegmentsIgnoreTheFenceButKeepOneBlankLineBetweenTheParagraphsItSeparates() {
        String withEntry = "Team rule.\n\n" + entry("3fa9", "- Korttly rule.") + "\nPersonal note\n";
        assertThat(MemoryFormat.userSegments(withEntry)).isEqualTo("Team rule.\n\nPersonal note\n");
        assertThat(MemoryFormat.userSegments(withEntry))
            .isNotEqualTo(MemoryFormat.userSegments("Team rule.\nPersonal note"));
    }

    @Test
    void userSegmentsKeepABlankLineBetweenParagraphs() {
        assertThat(MemoryFormat.userSegments("Team rule A.\n\nTeam rule B.\n"))
            .isNotEqualTo(MemoryFormat.userSegments("Team rule A.\nTeam rule B.\n"));
    }

    @Test
    void userSegmentsKeepLineEndingsAndWhitespaceOnlyLines() {
        assertThat(MemoryFormat.userSegments("A.\r\nB.\r\n")).isNotEqualTo(MemoryFormat.userSegments("A.\nB.\n"));
        assertThat(MemoryFormat.userSegments("A.\n   \nB.\n")).isNotEqualTo(MemoryFormat.userSegments("A.\nB.\n"));
    }

    @Test
    void userSegmentsOfAFileWithoutFinalNewlineEqualsTheFileWithOne() {
        assertThat(MemoryFormat.userSegments("Last line")).isEqualTo(MemoryFormat.userSegments("Last line\n"));
    }

    @Test
    void trimBlankEdgesRemovesOnlyTheBlankLinesAtTheEnds() {
        assertThat(MemoryFormat.trimBlankEdges("\n\r\nA\n\nB\n\n")).isEqualTo("A\n\nB\n");
    }

    @Test
    void textForSecretCheckLeavesTheFenceBesidesItsHashForTheGuard() {
        String fence = entry("3fa9", "- Body line.");
        String checked = MemoryFormat.textForSecretCheck(fence);
        assertThat(checked).doesNotContainMatch("[0-9a-f]{64}");
        assertThat(checked).isEqualTo(fence.replaceAll(" sha256=[0-9a-f]{64}", ""));
        assertThat(checked.split("\n", -1)).hasLength(fence.split("\n", -1).length);
    }

    @Test
    void textForSecretCheckKeepsAFenceLookalikeAndItsSecretVisible() {
        String lookalike = "Passwort ist hunter2hunter2 <!-- kortty:entry -->\n";
        assertThat(MemoryFormat.textForSecretCheck(lookalike)).isEqualTo(lookalike);
        String foreignHeader = "<!-- kortty:entry id=3fa9 origin=korTTY password=hunter2 -->\n";
        assertThat(MemoryFormat.textForSecretCheck(foreignHeader)).isEqualTo(foreignHeader);
    }

    @Test
    void userSegmentsDetectEveryChangeOfUserContent() {
        String base = "Team rule.\n" + entry("3fa9", "- Korttly rule.") + "Personal note";
        assertThat(MemoryFormat.userSegments(base.replace("Personal note", "Personal memo")))
            .isNotEqualTo(MemoryFormat.userSegments(base));
    }

    @Test
    void escapeMarkersBreaksPromptTagsWhateverTheCase() {
        String escaped = MemoryFormat.escapeMarkers("a <kortty_user_memory> b </KORTTY_USER_MEMORY> c "
            + "<retrieved_context> d </retrieved_context> <kortty_model_preset> <kortty_external_memory source=x>");
        assertThat(escaped).doesNotContainMatch(
            "(?i)<\\/?(kortty_user_memory|retrieved_context|kortty_model_preset|kortty_external_memory)");
        assertThat(escaped).contains("< kortty_user_memory>");
        assertThat(escaped).contains("< /retrieved_context>");
    }

    @Test
    void withoutFenceMarkersKeepsTheEntryBodyButDropsTheComments() {
        String fence = entry("3fa9", "- Body line.");
        String stripped = MemoryFormat.withoutFenceMarkers(fence);
        assertThat(stripped).isEqualTo("- Body line.\n");
    }

    @Test
    void newEntryRejectsIdsThatTheFenceCannotCarry() {
        org.testng.Assert.assertThrows(IllegalArgumentException.class,
            () -> MemoryFormat.newEntry("bad id", "- x", DAY, "chat"));
        org.testng.Assert.assertThrows(IllegalArgumentException.class,
            () -> MemoryFormat.newEntry("3fa9", "- x", DAY, "chat source"));
    }

    @Test
    void sha256MatchesTheKnownDigestOfTheEmptyString() {
        assertThat(MemoryFormat.sha256("")).isEqualTo(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void segmentsAreSealedToUserTextAndEntries() {
        Segment user = new UserText("x");
        assertThat(user).isInstanceOf(UserText.class);
    }


    @Test
    void userParagraphsOnBothSidesOfAFenceKeepOneBlankLineBetweenThem() {
        assertThat(MemoryFormat.userSegments("Team rule A.\n\n" + entry("3fa9", "- Korttly rule.") + "Team rule B.\n"))
            .isEqualTo("Team rule A.\n\nTeam rule B.\n");
        assertThat(MemoryFormat.userSegments("Team rule.\n\n" + entry("3fa9", "- Korttly rule.")))
            .isEqualTo("Team rule.\n");
    }

    @Test
    void trimBlankLinesTrimsOnlyTheRequestedEdges() {
        assertThat(MemoryFormat.trimBlankLines("\n\nA\n\n", true, false)).isEqualTo("A\n\n");
        assertThat(MemoryFormat.trimBlankLines("\n\nA\n\n", false, true)).isEqualTo("\n\nA\n");
    }

    @Test
    void aKorttyFenceOnTheFirstLineAfterAByteOrderMarkStillCountsAsAnEntry() {
        String text = "\uFEFF" + entry("3fa9", "- Rule on line one.") + "Personal note\n";
        MemoryDocument document = MemoryFormat.parse(text);
        assertThat(document.entries()).hasSize(1);
        assertThat(document.entries().get(0).id()).isEqualTo("3fa9");
        assertThat(document.toText()).isEqualTo(text);
    }

    @Test
    void invisibleCharactersDoNotHideAKeywordFromTheSecretCheck() {
        assertThat(MemoryFormat.textForSecretCheck("pass\u200Bword=Sommer2026!\n")).contains("password=Sommer2026!");
        assertThat(MemoryFormat.textForSecretCheck("Passwort\u200B: Sommer2026!\n")).contains("Passwort: Sommer2026!");
    }

    @Test
    void aHashInAFenceHeaderStaysVisibleUnlessTheBodyMatchesIt() {
        String hash = "0123456789abcdef".repeat(4);
        String forged = "<!-- kortty:entry id=3fa9 origin=korTTY sha256=" + hash
            + " created=2026-11-02 source=chat -->\n- A body that does not match the hash.\n<!-- /kortty:entry -->\n";
        assertThat(MemoryFormat.textForSecretCheck(forged)).contains(hash);
        assertThat(MemoryFormat.parse(forged).entries()).isEmpty();
        String genuine = entry("3fa9", "- A body that matches the hash.");
        assertThat(MemoryFormat.textForSecretCheck(genuine)).doesNotContainMatch("[0-9a-f]{64}");
    }
}
