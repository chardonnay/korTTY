package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

class ControlErrorCodeTest {

    private static final Set<Integer> ALLOWED_EXITS = Set.of(1, 2, 3, 4);

    @Test
    void everyConstantHasADistinctJsonRpcCode() {
        Map<Integer, ControlErrorCode> seen = new HashMap<>();
        for (ControlErrorCode code : ControlErrorCode.values()) {
            ControlErrorCode previous = seen.put(code.jsonRpcCode(), code);
            assertWithMessage("%s and %s share the JSON-RPC code %s", previous, code, code.jsonRpcCode())
                .that(previous)
                .isNull();
        }
        assertThat(seen).hasSize(ControlErrorCode.values().length);
    }

    @Test
    void everyConstantHasACliExitTheCliCanReturn() {
        for (ControlErrorCode code : ControlErrorCode.values()) {
            assertWithMessage("cliExit of %s", code).that(ALLOWED_EXITS).contains(code.cliExit());
        }
    }

    @Test
    void wireIsTheLowerCaseName() {
        for (ControlErrorCode code : ControlErrorCode.values()) {
            assertWithMessage("wire of %s", code)
                .that(code.wire())
                .isEqualTo(code.name().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void forWireRoundTripsEveryConstant() {
        for (ControlErrorCode code : ControlErrorCode.values()) {
            assertWithMessage("forWire of %s", code)
                .that(ControlErrorCode.forWire(code.wire()))
                .hasValue(code);
        }
    }

    @Test
    void forWireRejectsUnknownAndNullSpellings() {
        assertThat(ControlErrorCode.forWire("no_such_code")).isEmpty();
        assertThat(ControlErrorCode.forWire("PANE_NOT_FOUND")).isEmpty();
        assertThat(ControlErrorCode.forWire(null)).isEmpty();
    }

    @Test
    void timeoutIsTheOnlyExitFourAndTheProtocolErrorsAreExitTwo() {
        assertThat(ControlErrorCode.TIMEOUT.cliExit()).isEqualTo(4);
        assertThat(ControlErrorCode.PARSE_ERROR.cliExit()).isEqualTo(2);
        assertThat(ControlErrorCode.UNAUTHORIZED.cliExit()).isEqualTo(3);
        assertThat(ControlErrorCode.PANE_NOT_FOUND.cliExit()).isEqualTo(1);
        for (ControlErrorCode code : ControlErrorCode.values()) {
            if (code.cliExit() == 4) {
                assertThat(code).isEqualTo(ControlErrorCode.TIMEOUT);
            }
        }
    }

    @Test
    void retryableIsSetExactlyWhereARetryCanHelp() {
        assertThat(ControlErrorCode.NOT_CONNECTED.retryable()).isTrue();
        assertThat(ControlErrorCode.TOO_MANY_CONNECTIONS.retryable()).isTrue();
        assertThat(ControlErrorCode.TIMEOUT.retryable()).isTrue();
        assertThat(ControlErrorCode.PANE_NOT_FOUND.retryable()).isFalse();
        assertThat(ControlErrorCode.UNAUTHORIZED.retryable()).isFalse();
        assertThat(ControlErrorCode.INVALID_PARAMS.retryable()).isFalse();
    }

    @Test
    void theWireErrorAlwaysCarriesCodeRetryableAndExit() {
        ControlApiError error = ControlApiError.of(ControlErrorCode.PANE_NOT_FOUND,
            "No pane p1a2b3c4d is open", Map.of("pane", "p1a2b3c4d"));
        assertThat(error.code()).isEqualTo(-32012);
        assertThat(error.wire()).isEqualTo("pane_not_found");
        assertThat(error.data()).containsEntry("code", "pane_not_found");
        assertThat(error.data()).containsEntry("retryable", false);
        assertThat(error.data()).containsEntry("exit", 1);
        assertThat(error.data()).containsEntry("pane", "p1a2b3c4d");
    }

    @Test
    void extraDataCanNeverOverwriteTheThreeInvariantFields() {
        ControlApiError error = ControlApiError.of(ControlErrorCode.BUSY, "slow down",
            Map.of("code", "lies", "exit", 99, "retryable", "maybe"));
        assertThat(error.data()).containsEntry("code", "busy");
        assertThat(error.data()).containsEntry("exit", 1);
        assertThat(error.data()).containsEntry("retryable", true);
    }
}
