package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.codingagent.FakePaneAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class ControlVerbsSchemaTest {

    /**
     * The three verbs that genuinely cannot fail with a verb-specific error: they take no addressable
     * parameter, so only the universal codes (disabled, timeout, internal) can ever apply and those
     * are deliberately not repeated per method.
     */
    private static final Set<String> WITHOUT_SPECIFIC_ERRORS =
        Set.of("ping", "window.list", "pane.current");

    private ScheduledExecutorService timer;

    private ControlEventBus events;

    private MethodRegistry registry;

    @BeforeMethod
    void setUp() {
        timer = new ScheduledThreadPoolExecutor(1);
        events = new ControlEventBus(timer, () -> 1_000L);
        CodingAgentRegistry agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), () -> 1_000L);
        CodingAgentActions actions =
            new CodingAgentActions(agents, new FakePaneAccess(), (verb, pane, detail) -> { });
        registry = ControlVerbs.build(new FakeControlSurface(), UiDispatcher.DIRECT, agents, actions,
            events, (verb, pane, detail) -> { }, null, () -> 1_000L, "3.4.1", "test-instance");
    }

    @AfterMethod
    void tearDown() {
        events.closeAll();
        timer.shutdownNow();
    }

    @Test
    void everyRegisteredMethodHasASpecAndEverySpecIsRegistered() {
        List<String> specNames = new ArrayList<>();
        for (MethodSpec spec : registry.specs()) {
            specNames.add(spec.name());
            assertWithMessage("spec lookup for %s", spec.name())
                .that(registry.spec(spec.name())).isPresent();
        }
        ControlApiException unknown = expectThrows(ControlApiException.class,
            () -> registry.dispatch(session(), new ControlRequest(new JsonPrimitive(1), "no.such.verb",
                new JsonObject())));
        assertThat(unknown.code()).isEqualTo(ControlErrorCode.UNKNOWN_METHOD);
        assertThat(unknown.data().get("known")).isEqualTo(specNames);
    }

    @Test
    void everySpecDocumentsItsErrorsAndItsCliInvocation() {
        for (MethodSpec spec : registry.specs()) {
            assertWithMessage("cli of %s", spec.name()).that(spec.cli()).isNotNull();
            assertWithMessage("cli of %s", spec.name()).that(spec.cli().isBlank()).isFalse();
            assertWithMessage("summary of %s", spec.name()).that(spec.summary().isBlank()).isFalse();
            if (WITHOUT_SPECIFIC_ERRORS.contains(spec.name())) {
                assertWithMessage("errors of %s", spec.name()).that(spec.errors()).isEmpty();
            } else {
                assertWithMessage("errors of %s", spec.name()).that(spec.errors()).isNotEmpty();
            }
        }
    }

    @Test
    void theReservedListIsExactlyTheThreeTabVerbs() {
        List<String> names = new ArrayList<>();
        for (ReservedSpec spec : registry.reserved()) {
            names.add(spec.name());
            assertThat(spec.reason()).isEqualTo("not_implemented_in_this_version");
        }
        assertThat(names).containsExactly("tab.create", "tab.close", "tab.rename");
    }

    @Test
    void aReservedVerbIsUnsupportedRatherThanUnknown() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> registry.dispatch(session(), new ControlRequest(new JsonPrimitive(1), "tab.create",
                new JsonObject())));
        assertThat(failure.code()).isEqualTo(ControlErrorCode.UNSUPPORTED);
        assertThat(failure.data().get("reason")).isEqualTo("not_implemented_in_this_version");
    }

    @Test
    void theSchemaPublishesTheWholeKeyVocabulary() throws Exception {
        JsonObject document = registry
            .dispatch(session(), new ControlRequest(new JsonPrimitive(1), "api.schema", new JsonObject()))
            .getAsJsonObject();
        List<String> keys = new ArrayList<>();
        for (JsonElement key : document.getAsJsonArray("keys")) {
            keys.add(key.getAsString());
        }
        assertThat(keys).containsExactlyElementsIn(ControlKeyTable.knownKeys()).inOrder();
    }

    @Test
    void theSchemaDescribesExactlyTheRegisteredTable() throws Exception {
        JsonObject document = registry
            .dispatch(session(), new ControlRequest(new JsonPrimitive(1), "api.schema", new JsonObject()))
            .getAsJsonObject();
        JsonArray methods = document.getAsJsonArray("methods");
        List<String> published = new ArrayList<>();
        for (JsonElement method : methods) {
            published.add(method.getAsJsonObject().get("name").getAsString());
        }
        List<String> registered = new ArrayList<>();
        for (MethodSpec spec : registry.specs()) {
            registered.add(spec.name());
        }
        assertThat(published).containsExactlyElementsIn(registered).inOrder();
    }

    @Test
    void pingReportsTheRunningInstance() throws Exception {
        JsonObject result = registry
            .dispatch(session(), new ControlRequest(new JsonPrimitive(1), "ping", new JsonObject()))
            .getAsJsonObject();
        assertThat(result.get("pong").getAsBoolean()).isTrue();
        assertThat(result.get("instance").getAsString()).isEqualTo("test-instance");
        assertThat(result.get("protocol_version").getAsInt())
            .isEqualTo(ControlApiProtocol.PROTOCOL_VERSION);
    }

    private static ControlSession session() {
        return new ControlSession("c1", EndpointDescriptor.TRANSPORT_UNIX, true, "test", frame -> { });
    }
}
