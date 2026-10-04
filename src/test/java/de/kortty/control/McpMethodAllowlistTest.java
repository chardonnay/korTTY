package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

/**
 * The MCP allowlist is fail-closed and complete: every verb the control API registers carries an
 * explicit decision, a verb nobody classified is refused, and the classification agrees with each
 * method's own {@code mutates} flag.
 */
class McpMethodAllowlistTest {

    /** D14: the read-only MCP surface. */
    private static final Set<String> READ = Set.of("auth", "ping", "api.schema", "window.list",
        "tab.list", "pane.list", "pane.current", "pane.get", "pane.read", "pane.wait_output",
        "agent.list", "agent.get");

    /** D14: the write verbs, behind the second switch. */
    private static final Set<String> WRITE = Set.of("pane.send_text", "pane.run", "pane.send_keys");

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
    void everyRegisteredMethodAndEveryReservedVerbIsClassifiedExplicitly() {
        for (MethodSpec spec : registry.specs()) {
            assertWithMessage("%s is registered but McpMethodAllowlist has no decision for it; classify"
                    + " it READ, WRITE or REFUSED on purpose (and mask its text for MCP clients)",
                    spec.name())
                .that(McpMethodAllowlist.isClassified(spec.name())).isTrue();
        }
        for (ReservedSpec reserved : registry.reserved()) {
            assertWithMessage("reserved %s", reserved.name())
                .that(McpMethodAllowlist.classify(reserved.name()))
                .isEqualTo(McpMethodAllowlist.Access.REFUSED);
        }
    }

    @Test
    void theReadAndWriteSetsAreExactlyTheDesignedSurface() {
        List<String> read = new ArrayList<>();
        List<String> write = new ArrayList<>();
        for (MethodSpec spec : registry.specs()) {
            switch (McpMethodAllowlist.classify(spec.name())) {
                case READ -> read.add(spec.name());
                case WRITE -> write.add(spec.name());
                case REFUSED -> { }
            }
        }
        assertThat(read).containsExactlyElementsIn(READ);
        assertThat(write).containsExactlyElementsIn(WRITE);
    }

    @Test
    void theClassificationAgreesWithEachMethodsMutatesFlag() {
        for (MethodSpec spec : registry.specs()) {
            McpMethodAllowlist.Access access = McpMethodAllowlist.classify(spec.name());
            if (access == McpMethodAllowlist.Access.READ) {
                assertWithMessage("%s is offered as a read but mutates", spec.name())
                    .that(spec.mutates()).isFalse();
            }
            if (access == McpMethodAllowlist.Access.WRITE) {
                assertWithMessage("%s is offered as a write but does not mutate", spec.name())
                    .that(spec.mutates()).isTrue();
            }
        }
    }

    @Test
    void aNameNobodyClassifiedIsRefusedEvenWithWriteToolsOn() {
        for (String name : List.of("pane.exec", "agent.send_text", "tab.create", "", "PANE.READ")) {
            assertWithMessage("%s", name).that(McpMethodAllowlist.allows(name, true)).isFalse();
        }
        assertThat(McpMethodAllowlist.allows(null, true)).isFalse();
        assertThat(McpMethodAllowlist.isClassified("pane.exec")).isFalse();
        ControlApiException refused = expectThrows(ControlApiException.class,
            () -> McpMethodAllowlist.check("pane.exec", new JsonObject(), true));
        assertThat(refused.code()).isEqualTo(ControlErrorCode.METHOD_NOT_ALLOWED_FOR_MCP);
        assertThat(refused.data()).containsEntry("reason", McpMethodAllowlist.REASON_NOT_EXPOSED);
    }

    @Test
    void writesNeedTheSecondSwitch() throws Exception {
        for (String write : WRITE) {
            assertThat(McpMethodAllowlist.allows(write, false)).isFalse();
            assertThat(McpMethodAllowlist.allows(write, true)).isTrue();
            ControlApiException refused = expectThrows(ControlApiException.class,
                () -> McpMethodAllowlist.check(write, new JsonObject(), false));
            assertThat(refused.data()).containsEntry("reason", McpMethodAllowlist.REASON_WRITE_TOOLS_OFF);
        }
        McpMethodAllowlist.check("pane.read", new JsonObject(), false);
    }

    @Test
    void theSchemaDocumentKeepsOnlyAllowedMethodsAndDropsReservedVerbs() {
        JsonObject document = ControlApiSchema.document(registry.specs(), registry.reserved(), "3.4.1");
        JsonObject readOnly = McpMethodAllowlist.filterSchema(document, false).getAsJsonObject();
        assertThat(names(readOnly.getAsJsonArray("methods"))).containsExactlyElementsIn(READ);
        assertThat(readOnly.getAsJsonArray("reserved")).isEmpty();
        assertWithMessage("the filter works on a copy; the shared document stays whole")
            .that(document.getAsJsonArray("methods").size()).isEqualTo(registry.specs().size());

        JsonObject readWrite = McpMethodAllowlist.filterSchema(document, true).getAsJsonObject();
        List<String> expected = new ArrayList<>(READ);
        expected.addAll(WRITE);
        assertThat(names(readWrite.getAsJsonArray("methods"))).containsExactlyElementsIn(expected);
    }

    private static List<String> names(JsonArray methods) {
        List<String> names = new ArrayList<>();
        for (JsonElement entry : methods) {
            names.add(entry.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }
}
