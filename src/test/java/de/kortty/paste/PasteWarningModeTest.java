package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.Arrays;
import java.util.List;
import org.testng.annotations.Test;

class PasteWarningModeTest {

    @Test
    void theStoredIdsStayStable() {
        assertThat(PasteWarningMode.OFF.id()).isEqualTo("off");
        assertThat(PasteWarningMode.UNLESS_BRACKETED.id()).isEqualTo("unless-bracketed");
        assertThat(PasteWarningMode.ALWAYS.id()).isEqualTo("always");
    }

    @Test
    void everyIdRoundTrips() {
        for (PasteWarningMode mode : PasteWarningMode.values()) {
            assertWithMessage(mode.name()).that(PasteWarningMode.fromId(mode.id())).isSameInstanceAs(mode);
            assertWithMessage(mode.name()).that(PasteWarningMode.parseId(mode.id())).isSameInstanceAs(mode);
        }
    }

    @Test
    void idsAreReadWithoutRegardToCaseOrSurroundingBlanks() {
        assertThat(PasteWarningMode.parseId(" ALWAYS ")).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(PasteWarningMode.parseId("Unless-Bracketed")).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(PasteWarningMode.parseId("\toff\n")).isEqualTo(PasteWarningMode.OFF);
    }

    @Test
    void anUnknownIdIsNullWhenParsedStrictly() {
        for (String unknown : Arrays.asList(null, "", "   ", "never", "unless_bracketed", "UNLESS BRACKETED", "on")) {
            assertWithMessage(String.valueOf(unknown)).that(PasteWarningMode.parseId(unknown)).isNull();
        }
    }

    @Test
    void anUnknownIdKeepsProtectionOn() {
        assertThat(PasteWarningMode.DEFAULT).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        for (String unknown : Arrays.asList(null, "", "   ", "never", "unless_bracketed", "on")) {
            assertWithMessage(String.valueOf(unknown)).that(PasteWarningMode.fromId(unknown))
                .isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        }
    }

    @Test
    void theModesAreOrderedFromTheLeastToTheMostRestrictive() {
        assertThat(List.of(PasteWarningMode.values()))
            .containsExactly(PasteWarningMode.OFF, PasteWarningMode.UNLESS_BRACKETED, PasteWarningMode.ALWAYS)
            .inOrder();
    }

    @Test
    void mostRestrictivePicksTheModeThatAsksMoreOften() {
        PasteWarningMode[] modes = PasteWarningMode.values();
        for (PasteWarningMode a : modes) {
            for (PasteWarningMode b : modes) {
                PasteWarningMode expected = a.ordinal() >= b.ordinal() ? a : b;
                assertWithMessage(a + " vs " + b).that(PasteWarningMode.mostRestrictive(a, b)).isEqualTo(expected);
                assertWithMessage(b + " vs " + a).that(PasteWarningMode.mostRestrictive(b, a)).isEqualTo(expected);
            }
        }
        assertThat(PasteWarningMode.mostRestrictive(PasteWarningMode.OFF, PasteWarningMode.ALWAYS))
            .isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(PasteWarningMode.mostRestrictive(PasteWarningMode.UNLESS_BRACKETED, PasteWarningMode.OFF))
            .isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
    }

    @Test
    void mostRestrictiveTreatsNullAsNoOpinion() {
        assertThat(PasteWarningMode.mostRestrictive(null, PasteWarningMode.OFF)).isEqualTo(PasteWarningMode.OFF);
        assertThat(PasteWarningMode.mostRestrictive(PasteWarningMode.ALWAYS, null)).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(PasteWarningMode.mostRestrictive(null, null)).isNull();
    }

    @Test
    void aFloorLeavesOnlyTheModesThatAskAtLeastAsOften() {
        assertThat(PasteWarningMode.atLeast(null)).containsExactlyElementsIn(PasteWarningMode.values()).inOrder();
        assertThat(PasteWarningMode.atLeast(PasteWarningMode.OFF))
            .containsExactlyElementsIn(PasteWarningMode.values()).inOrder();
        assertThat(PasteWarningMode.atLeast(PasteWarningMode.UNLESS_BRACKETED))
            .containsExactly(PasteWarningMode.UNLESS_BRACKETED, PasteWarningMode.ALWAYS).inOrder();
        assertWithMessage("an always floor leaves nothing to choose, so the dropdown is locked")
            .that(PasteWarningMode.atLeast(PasteWarningMode.ALWAYS)).containsExactly(PasteWarningMode.ALWAYS);
    }
}
