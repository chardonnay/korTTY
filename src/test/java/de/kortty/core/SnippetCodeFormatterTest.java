package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SnippetCodeFormatterTest {

    @Test
    void formatsJsonWithBuiltInFormatter() throws Exception {
        String formatted = SnippetCodeFormatter.formatOrThrow("{\"name\":\"demo\",\"items\":[1,2]}", "json");

        assertThat(formatted).isEqualTo("""
            {
              "name": "demo",
              "items": [
                1,
                2
              ]
            }""");
    }

    @Test
    void exposesLocalShellFormatterConfiguration() {
        CodeFormatterService.FormatterInfo formatter = CodeFormatterService.getFormatterInfo("bash");

        assertThat(formatter).isNotNull();
        assertThat(formatter.commandName()).isEqualTo("shfmt");
        assertThat(formatter.fileExtension()).isEqualTo(".sh");
        assertThat(formatter.providerType()).isAnyOf(
            CodeFormatterService.ProviderType.BUNDLED,
            CodeFormatterService.ProviderType.EXTERNAL_FALLBACK,
            CodeFormatterService.ProviderType.UNAVAILABLE);
    }

    @Test
    void reportsUnsupportedPlainText() {
        assertThat(SnippetCodeFormatter.isSupported("plain")).isFalse();
        assertThat(SnippetCodeFormatter.getFormatterInfo("plain")).isNull();
    }

    @Test
    void builtInFormatterIsAlwaysAvailable() {
        SnippetCodeFormatter.FormatterInfo formatter = SnippetCodeFormatter.getFormatterInfo("json");

        assertThat(formatter).isNotNull();
        assertThat(formatter.isExternal()).isFalse();
        assertThat(SnippetCodeFormatter.isFormatterAvailable(formatter)).isTrue();
        assertThat(formatter.displayName()).isEqualTo("integrated");
    }

    @Test
    void prefersBundledFormatterOverExternalFallback() throws Exception {
        // The stub shfmt binary is a /bin/sh script; Windows cannot execute it.
        if (isWindows()) {
            throw new SkipException("Bundled-formatter execution test uses a /bin/sh stub binary; not applicable on Windows.");
        }
        String previous = System.getProperty("kortty.formatters.dir");
        Path root = Files.createTempDirectory("kortty-formatters-test");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path shfmt = bin.resolve(isWindows() ? "shfmt.exe" : "shfmt");
        Files.writeString(shfmt, "#!/bin/sh\nsed 's/foo/bar/g'\n");
        shfmt.toFile().setExecutable(true, false);
        try {
            System.setProperty("kortty.formatters.dir", root.toString());

            CodeFormatterService.FormatterInfo formatter = CodeFormatterService.getFormatterInfo("bash");

            assertThat(formatter).isNotNull();
            assertThat(formatter.providerType()).isEqualTo(CodeFormatterService.ProviderType.BUNDLED);
            assertThat(formatter.displayName()).contains("bundled");
            assertThat(CodeFormatterService.formatOrThrow("echo foo\n", "bash")).isEqualTo("echo bar\n");
        } finally {
            if (previous == null) {
                System.clearProperty("kortty.formatters.dir");
            } else {
                System.setProperty("kortty.formatters.dir", previous);
            }
        }
    }

    @Test
    void javaFormatterIsBundledJarProvider() {
        CodeFormatterService.FormatterInfo formatter = CodeFormatterService.getFormatterInfo("java");

        assertThat(formatter).isNotNull();
        assertThat(formatter.providerType()).isEqualTo(CodeFormatterService.ProviderType.BUNDLED);
        assertThat(formatter.displayName()).contains("google-java-format");
    }

    @Test
    void detectsBundledWebFormattersWithoutNodeRuntime() {
        assertThat(WebFormatterResourceBundle.isBundled()).isTrue();

        CodeFormatterService.FormatterInfo javascript = CodeFormatterService.getFormatterInfo("javascript");
        CodeFormatterService.FormatterInfo sql = CodeFormatterService.getFormatterInfo("sql");

        assertThat(javascript.providerType()).isEqualTo(CodeFormatterService.ProviderType.BUNDLED);
        assertThat(javascript.displayName()).contains("Prettier");
        assertThat(javascript.commandLine()).isEmpty();
        assertThat(sql.providerType()).isEqualTo(CodeFormatterService.ProviderType.BUNDLED);
        assertThat(sql.displayName()).contains("sql-formatter");
        assertThat(sql.commandLine()).isEmpty();
    }

    @Test
    void exposesLineWidthSupportOnlyForFormattersWithNativeWidthOptions() {
        assertThat(CodeFormatterService.supportsLineWidth("javascript")).isTrue();
        assertThat(CodeFormatterService.supportsLineWidth("html")).isTrue();
        assertThat(CodeFormatterService.supportsLineWidth("python")).isTrue();
        assertThat(CodeFormatterService.supportsLineWidth("perl")).isTrue();
        assertThat(CodeFormatterService.supportsLineWidth("java")).isFalse();
        assertThat(CodeFormatterService.supportsLineWidth("json")).isFalse();
    }

    @Test
    void rejectsOutOfRangeLineWidthBeforeStartingWebFormatter() {
        CodeFormatterService.FormatterException failure = expectThrows(
            CodeFormatterService.FormatterException.class,
            () -> CodeFormatterService.formatOrThrow("const name = 'korTTY';\n", "javascript", 10));

        assertThat(failure).hasMessageThat().contains("between 20 and 240");
    }

    @Test
    void rejectsLineWidthForFormatterWithoutNativeWidthOption() {
        CodeFormatterService.FormatterException failure = expectThrows(
            CodeFormatterService.FormatterException.class,
            () -> CodeFormatterService.formatOrThrow("class Demo {}\n", "java", 80));

        assertThat(failure).hasMessageThat().contains("does not support");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
