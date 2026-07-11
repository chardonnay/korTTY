package de.kortty.core;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

class WebFormatterResourceBundleTest {

    @Test
    void extractsCompleteFormatterHostToOneFileOrigin() throws Exception {
        assertThat(WebFormatterResourceBundle.isBundled()).isTrue();

        Path directory = WebFormatterResourceBundle.ensureExtracted();

        assertThat(directory.getFileName().toString()).startsWith("kortty-formatters-web-");
        for (String file : WebFormatterResourceBundle.FILES) {
            assertThat(Files.isRegularFile(directory.resolve(file))).isTrue();
        }
        assertThat(WebFormatterResourceBundle.hostUrl()).startsWith("file:");
    }

    @Test
    void hostUsesArgumentBridgeForAsyncPrettierAndSynchronousSql() throws Exception {
        String host = Files.readString(
            WebFormatterResourceBundle.ensureExtracted().resolve(WebFormatterResourceBundle.HOST_FILE),
            StandardCharsets.UTF_8);

        assertThat(host).contains("Promise.resolve(prettier.format(String(source), options))");
        assertThat(host).contains("const formatted = sqlFormatter.format(String(source));");
        assertThat(host).contains("bridge().onSuccess(String(requestId), String(formatted))");
        assertThat(host).contains("__kortty_test_never_complete__");
        assertThat(host).doesNotContain("eval(");
    }
}
