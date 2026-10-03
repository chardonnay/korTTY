package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.testng.annotations.Test;

/**
 * Cmd/Ctrl+Shift+H decides from the pane's real state — the set it shows — and never from a menu
 * item's check mark. From no set it brings back the set the pane showed last, else the one it
 * inherits, else the built-in errors set.
 */
class HighlightToggleTest {

    private static final String USER = "user-1";

    private static final Predicate<String> KNOWN =
        Set.of(HighlightBuiltinSets.ERRORS, HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.NETWORK_DEVICES, USER)
            ::contains;

    private static HighlightToggle.Choice toggle(String shown, String lastUsed, String inherited) {
        Optional<HighlightToggle.Choice> choice = HighlightToggle.toggle(true, shown, lastUsed, inherited, KNOWN);
        assertThat(choice.isPresent()).isTrue();
        return choice.get();
    }

    @Test
    void aPaneThatShowsASetIsSwitchedOffWithAnExplicitNone() {
        HighlightToggle.Choice choice = toggle(HighlightBuiltinSets.NETWORK, USER, HighlightBuiltinSets.ERRORS);

        assertThat(choice.paneOverride()).isEqualTo(TerminalHighlightService.NONE_ID);
        assertThat(choice.shownSetId()).isNull();
        assertThat(choice.on()).isFalse();
    }

    @Test
    void aSetInheritedFromTheDefaultIsAlsoSwitchedOffForThatPane() {
        // The pane has no choice of its own and shows the global default: off must beat the default.
        HighlightToggle.Choice choice = toggle(HighlightBuiltinSets.ERRORS, null, HighlightBuiltinSets.ERRORS);

        assertThat(choice.paneOverride()).isEqualTo(TerminalHighlightService.NONE_ID);
    }

    @Test
    void fromNoSetTheLastUsedSetComesBackFirst() {
        HighlightToggle.Choice choice = toggle(null, USER, HighlightBuiltinSets.NETWORK);

        assertThat(choice.paneOverride()).isEqualTo(USER);
        assertThat(choice.shownSetId()).isEqualTo(USER);
        assertThat(choice.on()).isTrue();
    }

    @Test
    void withoutALastUsedSetTheInheritedSetComesBackByClearingThePaneChoice() {
        HighlightToggle.Choice choice = toggle(null, null, HighlightBuiltinSets.NETWORK);

        assertThat(choice.paneOverride()).isNull();
        assertThat(choice.shownSetId()).isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void aLastUsedSetThatIsAlsoTheInheritedOneIsNotPinned() {
        HighlightToggle.Choice choice = toggle(null, HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.NETWORK);

        assertThat(choice.paneOverride()).isNull();
        assertThat(choice.shownSetId()).isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void withNothingKnownTheBuiltInErrorsSetIsPinned() {
        HighlightToggle.Choice choice = toggle(null, null, null);

        assertThat(choice.paneOverride()).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(choice.shownSetId()).isEqualTo(HighlightToggle.FALLBACK_SET_ID);
    }

    @Test
    void aDeletedLastUsedSetIsSkipped() {
        assertThat(toggle(null, "deleted-set", HighlightBuiltinSets.NETWORK).shownSetId())
            .isEqualTo(HighlightBuiltinSets.NETWORK);
        assertThat(toggle(null, "deleted-set", "also-deleted").shownSetId()).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(toggle(null, TerminalHighlightService.NONE_ID, null).shownSetId())
            .isEqualTo(HighlightBuiltinSets.ERRORS);
    }

    @Test
    void idsAreTrimmed() {
        HighlightToggle.Choice choice = toggle(null, "  " + USER + " ", null);

        assertThat(choice.paneOverride()).isEqualTo(USER);
    }

    @Test
    void withTheMasterSwitchOffTheToggleDoesNothing() {
        assertThat(HighlightToggle.toggle(false, null, USER, HighlightBuiltinSets.ERRORS, KNOWN).isPresent()).isFalse();
        assertThat(HighlightToggle.toggle(false, USER, USER, null, KNOWN).isPresent()).isFalse();
    }

    @Test
    void withNoSetKnownAtAllTheToggleDoesNothing() {
        assertThat(HighlightToggle.toggle(true, null, USER, HighlightBuiltinSets.NETWORK, id -> false).isPresent())
            .isFalse();
    }

    @Test
    void twoTogglesRestoreThePanesSet() {
        // On: pane shows the network set it picked. Off records nothing new; the caller remembers the
        // shown set, so the second toggle brings it back.
        HighlightToggle.Choice off = toggle(HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.NETWORK, null);
        HighlightToggle.Choice on = toggle(off.shownSetId(), HighlightBuiltinSets.NETWORK, null);

        assertThat(on.paneOverride()).isEqualTo(HighlightBuiltinSets.NETWORK);
        assertThat(on.shownSetId()).isEqualTo(HighlightBuiltinSets.NETWORK);
    }
}
