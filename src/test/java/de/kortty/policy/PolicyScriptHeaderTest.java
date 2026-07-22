package de.kortty.policy;

import de.kortty.core.SnippetManager;
import de.kortty.model.Snippet;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/** Policy-managed script headers: injection, immutability, strip-on-save, self-healing. */
class PolicyScriptHeaderTest {

    private static final String POLICY = """
        [meta]
        schema-version = 1

        [[rule]]
        [rule.snippets]
        allow-custom-script-headers = false

        [[script-header]]
        name = "ACME Header"
        content = "#!/usr/bin/env bash"
        """;

    private Path configDir;
    private Path policyFile;

    @BeforeMethod
    void activatePolicy() throws IOException {
        configDir = Files.createTempDirectory("kortty-snippet-policy");
        policyFile = Files.createTempFile("kortty-policy", ".toml");
        Files.writeString(policyFile, POLICY);
        System.clearProperty("jpackage.app-path");
        System.setProperty(PolicyLocator.OVERRIDE_PROPERTY, policyFile.toString());
        PolicyManager.initialize();
    }

    @AfterMethod
    void reset() throws IOException {
        System.clearProperty(PolicyLocator.OVERRIDE_PROPERTY);
        PolicyManager.resetForTests();
        Files.deleteIfExists(policyFile);
        try (var paths = Files.walk(configDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static List<Snippet> headers(SnippetManager manager) {
        return manager.getAllSnippets().stream()
            .filter(s -> SnippetManager.SCRIPT_HEADER_CATEGORY.equalsIgnoreCase(s.getCategory()))
            .toList();
    }

    @Test
    void injectsManagedHeadersAndRefusesMutation() throws Exception {
        SnippetManager manager = new SnippetManager(configDir);
        manager.load();

        List<Snippet> headers = headers(manager);
        assertThat(headers).hasSize(1);
        Snippet managed = headers.get(0);
        assertThat(managed.getName()).isEqualTo("ACME Header");
        assertThat(managed.isPolicyManaged()).isTrue();

        expectThrows(PolicyRestrictionException.class, () -> manager.updateSnippet(managed));
        expectThrows(PolicyRestrictionException.class, () -> manager.removeSnippet(managed));
    }

    @Test
    void refusesCreatingCustomScriptHeadersWhenForbidden() throws Exception {
        SnippetManager manager = new SnippetManager(configDir);
        manager.load();

        Snippet custom = new Snippet("My Header", "# mine", "bash");
        custom.setCategory(SnippetManager.SCRIPT_HEADER_CATEGORY);
        expectThrows(PolicyRestrictionException.class, () -> manager.addSnippet(custom));

        // Ordinary snippets outside the header category are unaffected.
        Snippet normal = new Snippet("Utility", "echo hi", "bash");
        manager.addSnippet(normal);
    }

    @Test
    void managedHeadersAreStrippedFromXmlAndReinjectedOnLoad() throws Exception {
        SnippetManager manager = new SnippetManager(configDir);
        manager.load();
        manager.save();

        String xml = Files.readString(configDir.resolve("snippets.xml"));
        assertThat(xml).doesNotContain("ACME Header");

        SnippetManager reloaded = new SnippetManager(configDir);
        reloaded.load();
        assertThat(headers(reloaded)).hasSize(1);
        assertThat(headers(reloaded).get(0).isPolicyManaged()).isTrue();
    }
}
