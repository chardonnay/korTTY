package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.codingagent.CodingAgentActionException;
import java.util.EnumMap;
import java.util.Map;
import org.testng.annotations.Test;

class ControlApiExceptionMappingTest {

    private static final String FILE_TO_UPDATE =
        "src/main/java/de/kortty/control/ControlApiException.java";

    private static final Map<CodingAgentActionException.Code, ControlErrorCode> EXPECTED = expected();

    private static Map<CodingAgentActionException.Code, ControlErrorCode> expected() {
        Map<CodingAgentActionException.Code, ControlErrorCode> map =
            new EnumMap<>(CodingAgentActionException.Code.class);
        map.put(CodingAgentActionException.Code.PANE_NOT_FOUND, ControlErrorCode.PANE_NOT_FOUND);
        map.put(CodingAgentActionException.Code.AGENT_BLOCKED, ControlErrorCode.AGENT_BLOCKED);
        map.put(CodingAgentActionException.Code.NOT_CONNECTED, ControlErrorCode.NOT_CONNECTED);
        map.put(CodingAgentActionException.Code.WRITE_FAILED, ControlErrorCode.WRITE_FAILED);
        map.put(CodingAgentActionException.Code.HOST_SHORTCUT_CONFLICT,
            ControlErrorCode.HOST_SHORTCUT_CONFLICT);
        map.put(CodingAgentActionException.Code.EMPTY_INPUT, ControlErrorCode.EMPTY_INPUT);
        return map;
    }

    @Test
    void everyStage2CodeMapsToItsIntendedControlCode() {
        for (CodingAgentActionException.Code code : CodingAgentActionException.Code.values()) {
            ControlApiException translated =
                ControlApiException.from(new CodingAgentActionException(code, "boom"));
            assertWithMessage("mapping of %s", code)
                .that(translated.code())
                .isEqualTo(EXPECTED.get(code));
        }
    }

    @Test
    void theMappingCoversTheWholeStage2Vocabulary() {
        assertWithMessage("de.kortty.codingagent added a CodingAgentActionException.Code;"
                + " add the matching arm to the exhaustive switch in " + FILE_TO_UPDATE
                + " and a row to this test")
            .that(CodingAgentActionException.Code.values().length)
            .isEqualTo(6);
        assertThat(EXPECTED.keySet()).hasSize(CodingAgentActionException.Code.values().length);
    }

    @Test
    void theOriginalFailureIsKeptAsTheCause() {
        CodingAgentActionException cause = new CodingAgentActionException(
            CodingAgentActionException.Code.AGENT_BLOCKED, "Claude Code is waiting for an answer");
        ControlApiException translated = ControlApiException.from(cause);
        assertThat(translated.getMessage()).isEqualTo("Claude Code is waiting for an answer");
        assertThat(translated.getCause()).isSameInstanceAs(cause);
        assertThat(translated.data()).isEmpty();
    }

    @Test
    void theTranslatedFailureSerialisesWithTheInvariantDataFields() {
        ControlApiException translated = ControlApiException.from(new CodingAgentActionException(
            CodingAgentActionException.Code.NOT_CONNECTED, "the pane is disconnected"));
        ControlApiError wire = translated.toWire();
        assertThat(wire.wire()).isEqualTo("not_connected");
        assertThat(wire.code()).isEqualTo(-32020);
        assertThat(wire.data()).containsEntry("retryable", true);
        assertThat(wire.data()).containsEntry("exit", 1);
    }

    @Test
    void theWireCodeSpellingAgreesWithStage2() {
        for (CodingAgentActionException.Code code : CodingAgentActionException.Code.values()) {
            CodingAgentActionException cause = new CodingAgentActionException(code, "boom");
            assertWithMessage("wire spelling of %s", code)
                .that(ControlApiException.from(cause).code().wire())
                .isEqualTo(cause.wireCode());
        }
    }
}
