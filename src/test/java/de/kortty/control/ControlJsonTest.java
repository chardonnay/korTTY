package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonObject;
import java.util.List;
import org.testng.annotations.Test;

class ControlJsonTest {

    private static JsonObject params(String json) throws Exception {
        return ControlJson.parseObjectStrict(json);
    }

    @Test
    void aWellFormedRequestLineParses() throws Exception {
        JsonObject object = params("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\",\"params\":{}}");
        assertThat(object.get("method").getAsString()).isEqualTo("ping");
        assertThat(object.get("id").getAsInt()).isEqualTo(7);
    }

    @Test
    void trailingContentAfterTheDocumentIsAParseError() {
        for (String line : List.of("{\"a\":1} {\"b\":2}", "{\"a\":1}]", "{\"a\":1}garbage")) {
            ControlApiException failure =
                expectThrows(ControlApiException.class, () -> params(line));
            assertWithMessage("parsing %s", line)
                .that(failure.code())
                .isEqualTo(ControlErrorCode.PARSE_ERROR);
        }
    }

    @Test
    void strictModeRejectsNanInfinityCommentsAndUnquotedNames() {
        for (String line : List.of("{\"n\":NaN}", "{\"n\":Infinity}", "{\"n\":-Infinity}",
                "{/* hi */\"a\":1}", "{\"a\":1} // trailing", "{a:1}", "{'a':1}", "{\"a\":1,}")) {
            ControlApiException failure =
                expectThrows(ControlApiException.class, () -> params(line));
            assertWithMessage("parsing %s", line)
                .that(failure.code())
                .isEqualTo(ControlErrorCode.PARSE_ERROR);
        }
    }

    @Test
    void malformedJsonIsAParseError() {
        for (String line : List.of("{", "not json", "\"just a string\"", "   ")) {
            ControlApiException failure =
                expectThrows(ControlApiException.class, () -> params(line));
            assertWithMessage("parsing %s", line)
                .that(failure.code())
                .isAnyOf(ControlErrorCode.PARSE_ERROR, ControlErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void aBareArrayIsRejectedBecauseBatchesAreNotSupported() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> params("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"));
        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_REQUEST);
    }

    @Test
    void requireStringOnAMissingFieldIsInvalidParams() throws Exception {
        JsonObject object = params("{\"other\":1}");
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> ControlJson.requireString(object, "pane"));
        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(failure.data()).containsEntry("param", "pane");
    }

    @Test
    void requireStringOnAWrongTypeIsInvalidParams() throws Exception {
        JsonObject object = params("{\"pane\":42,\"other\":null,\"nested\":{}}");
        assertThat(expectThrows(ControlApiException.class,
            () -> ControlJson.requireString(object, "pane")).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(expectThrows(ControlApiException.class,
            () -> ControlJson.requireString(object, "nested")).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(expectThrows(ControlApiException.class,
            () -> ControlJson.requireString(object, "other")).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test
    void requireStringReadsAPresentString() throws Exception {
        assertThat(ControlJson.requireString(params("{\"pane\":\"p1a2b\"}"), "pane"))
            .isEqualTo("p1a2b");
    }

    @Test
    void optStringFallsBackForAnAbsentOrNullMember() throws Exception {
        JsonObject object = params("{\"mode\":null,\"other\":\"recent\"}");
        assertThat(ControlJson.optString(object, "mode", "visible")).isEqualTo("visible");
        assertThat(ControlJson.optString(object, "missing", "visible")).isEqualTo("visible");
        assertThat(ControlJson.optString(object, "other", "visible")).isEqualTo("recent");
    }

    @Test
    void optIntClampsTheFallbackIntoTheDeclaredRange() throws Exception {
        JsonObject empty = params("{}");
        assertThat(ControlJson.optInt(empty, "lines", 200, 1, 10_000)).isEqualTo(200);
        assertThat(ControlJson.optInt(empty, "lines", 50_000, 1, 10_000)).isEqualTo(10_000);
        assertThat(ControlJson.optInt(empty, "lines", -7, 1, 10_000)).isEqualTo(1);
    }

    @Test
    void optIntRejectsASuppliedValueOutsideTheRange() throws Exception {
        JsonObject object = params("{\"lines\":10001,\"zero\":0}");
        ControlApiException tooBig = expectThrows(ControlApiException.class,
            () -> ControlJson.optInt(object, "lines", 200, 1, 10_000));
        assertThat(tooBig.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(tooBig.data()).containsEntry("param", "lines");
        assertThat(tooBig.data()).containsEntry("max", 10_000L);
        ControlApiException tooSmall = expectThrows(ControlApiException.class,
            () -> ControlJson.optInt(object, "zero", 200, 1, 10_000));
        assertThat(tooSmall.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test
    void optIntRejectsANonNumberAndANonWholeNumber() throws Exception {
        JsonObject object = params("{\"lines\":\"200\",\"fraction\":1.5}");
        assertThat(expectThrows(ControlApiException.class,
            () -> ControlJson.optInt(object, "lines", 200, 1, 10_000)).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(expectThrows(ControlApiException.class,
            () -> ControlJson.optInt(object, "fraction", 200, 1, 10_000)).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test
    void optLongCarriesTheWaitTimeoutRange() throws Exception {
        JsonObject object = params("{\"timeout_ms\":45000}");
        assertThat(ControlJson.optLong(object, "timeout_ms",
            ControlApiProtocol.WAIT_DEFAULT_MILLIS, 0L, ControlApiProtocol.WAIT_HARD_CAP_MILLIS))
            .isEqualTo(45_000L);
        assertThat(ControlJson.optLong(params("{}"), "timeout_ms",
            ControlApiProtocol.WAIT_DEFAULT_MILLIS, 0L, ControlApiProtocol.WAIT_HARD_CAP_MILLIS))
            .isEqualTo(ControlApiProtocol.WAIT_DEFAULT_MILLIS);
    }

    @Test
    void optBoolFallsBackAndRejectsAWrongType() throws Exception {
        assertThat(ControlJson.optBool(params("{\"submit\":true}"), "submit", false)).isTrue();
        assertThat(ControlJson.optBool(params("{}"), "submit", false)).isFalse();
        assertThat(ControlJson.optBool(params("{\"submit\":null}"), "submit", true)).isTrue();
        assertThat(expectThrows(ControlApiException.class,
            () -> ControlJson.optBool(params("{\"submit\":\"yes\"}"), "submit", false)).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test
    void optStringListAcceptsAnArrayAStringAndNothing() throws Exception {
        assertThat(ControlJson.optStringList(params("{\"keys\":[\"ctrl+c\",\"enter\"]}"), "keys"))
            .containsExactly("ctrl+c", "enter").inOrder();
        assertThat(ControlJson.optStringList(params("{\"keys\":\"ctrl+c enter\"}"), "keys"))
            .containsExactly("ctrl+c enter");
        assertThat(ControlJson.optStringList(params("{}"), "keys")).isEmpty();
        assertThat(ControlJson.optStringList(params("{\"keys\":null}"), "keys")).isEmpty();
    }

    @Test
    void optStringListRejectsANonStringElement() throws Exception {
        assertThat(expectThrows(ControlApiException.class,
            () -> ControlJson.optStringList(params("{\"keys\":[1,2]}"), "keys")).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(expectThrows(ControlApiException.class,
            () -> ControlJson.optStringList(params("{\"keys\":{}}"), "keys")).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test
    void toTreeSerialisesTheValueRecordsInSnakeCase() {
        WriteResult result = new WriteResult("p1a2b", 28, false, true, List.of());
        String json = ControlJson.gson().toJson(ControlJson.toTree(result));
        assertThat(json).contains("\"pane_id\":\"p1a2b\"");
        assertThat(json).contains("\"bytes_written\":28");
        assertThat(json).doesNotContain("bytesWritten");
    }

    @Test
    void toTreeKeepsExplicitNullsSoAClientCanTellAbsentFromUnset() {
        AgentInfo agent = AgentInfo.undetected("p1a2b", "t9f3a", "w1");
        String json = ControlJson.gson().toJson(ControlJson.toTree(agent));
        assertThat(json).contains("\"detected\":false");
        assertThat(json).contains("\"kind\":null");
        assertThat(json).contains("\"state\":\"unknown\"");
    }
}
