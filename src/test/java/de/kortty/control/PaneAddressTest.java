package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.util.List;
import org.testng.annotations.Test;

class PaneAddressTest {

    private static final String PANE = "p1a2b3c4d";

    private static final String TAB = "t9f3a4c1e-2b77-4f0a-9a11-6c2d5f8e0b33";

    private static final String WINDOW = "w1";

    @Test
    void aBarePaneIdParsesAsAPane() throws Exception {
        PaneAddress address = PaneAddress.parse(PANE);
        assertThat(address.kind()).isEqualTo(PaneAddress.Kind.PANE);
        assertThat(address.paneId()).isEqualTo(PANE);
        assertThat(address.tabId()).isNull();
        assertThat(address.windowId()).isNull();
    }

    @Test
    void aQualifiedAddressParsesIntoItsThreeParts() throws Exception {
        PaneAddress address = PaneAddress.parse(WINDOW + ":" + TAB + ":" + PANE);
        assertThat(address.kind()).isEqualTo(PaneAddress.Kind.QUALIFIED);
        assertThat(address.windowId()).isEqualTo(WINDOW);
        assertThat(address.tabId()).isEqualTo(TAB);
        assertThat(address.paneId()).isEqualTo(PANE);
    }

    @Test
    void aBareTabIdParsesAsATab() throws Exception {
        PaneAddress address = PaneAddress.parse(TAB);
        assertThat(address.kind()).isEqualTo(PaneAddress.Kind.TAB);
        assertThat(address.tabId()).isEqualTo(TAB);
        assertThat(address.paneId()).isNull();
    }

    @Test
    void theFocusedAliasParsesAndIsCaseInsensitive() throws Exception {
        assertThat(PaneAddress.parse("@focused").kind()).isEqualTo(PaneAddress.Kind.FOCUSED);
        assertThat(PaneAddress.parse("  @FOCUSED  ").kind()).isEqualTo(PaneAddress.Kind.FOCUSED);
    }

    @Test
    void aBareWindowIdIsAmbiguousRatherThanAGuess() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> PaneAddress.parse(WINDOW));
        assertThat(failure.code()).isEqualTo(ControlErrorCode.AMBIGUOUS_PANE);
        assertThat(failure.data()).containsEntry("selector", WINDOW);
    }

    @Test
    void garbageIsASyntaxError() {
        for (String text : List.of("garbage", "", "   ", ":", "p", "t", "w",
                WINDOW + ":" + PANE, WINDOW + ":" + TAB + ":" + TAB, PANE + ":" + TAB + ":" + WINDOW)) {
            ControlApiException failure = expectThrows(ControlApiException.class,
                () -> PaneAddress.parse(text));
            assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
        }
    }

    @Test
    void nullIsASyntaxErrorRatherThanANullPointerException() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> PaneAddress.parse(null));
        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test
    void wireRoundTripsEveryShape() throws Exception {
        for (String text : List.of(PANE, TAB, WINDOW + ":" + TAB + ":" + PANE, "@focused")) {
            PaneAddress parsed = PaneAddress.parse(text);
            assertThat(parsed.wire()).isEqualTo(text);
            assertThat(PaneAddress.parse(parsed.wire())).isEqualTo(parsed);
        }
    }

    @Test
    void theFactoriesProduceTheSameShapesAsParsing() throws Exception {
        assertThat(PaneAddress.ofPaneId(PANE)).isEqualTo(PaneAddress.parse(PANE));
        assertThat(PaneAddress.focused()).isEqualTo(PaneAddress.parse("@focused"));
        assertThat(PaneAddress.focused().wire()).isEqualTo("@focused");
    }

    @Test
    void surroundingWhitespaceIsTolerated() throws Exception {
        assertThat(PaneAddress.parse("  " + PANE + "  ").paneId()).isEqualTo(PANE);
    }
}
