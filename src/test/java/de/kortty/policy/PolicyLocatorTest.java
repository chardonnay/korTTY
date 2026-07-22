package de.kortty.policy;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * Tests the override/packaged-build interaction of the locator. The codeSource ladder itself is
 * exercised implicitly (in tests the code source is a classes directory without a policy sibling),
 * so {@link PolicyLocator#locate()} must come back empty unless the dev override points somewhere.
 */
class PolicyLocatorTest {

    private String previousOverride;
    private String previousJpackagePath;
    private Path tempPolicy;

    @BeforeMethod
    void rememberProperties() throws IOException {
        previousOverride = System.getProperty(PolicyLocator.OVERRIDE_PROPERTY);
        previousJpackagePath = System.getProperty("jpackage.app-path");
        tempPolicy = Files.createTempFile("kortty-policy", ".toml");
        Files.writeString(tempPolicy, "[meta]\nschema-version = 1\n");
    }

    @AfterMethod
    void restoreProperties() throws IOException {
        restore(PolicyLocator.OVERRIDE_PROPERTY, previousOverride);
        restore("jpackage.app-path", previousJpackagePath);
        Files.deleteIfExists(tempPolicy);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void devOverrideIsHonoredInUnpackagedLaunches() {
        System.clearProperty("jpackage.app-path");
        System.setProperty(PolicyLocator.OVERRIDE_PROPERTY, tempPolicy.toString());
        assertThat(PolicyLocator.locate()).hasValue(tempPolicy);
    }

    @Test
    void devOverrideIsIgnoredInPackagedBuilds() {
        System.setProperty("jpackage.app-path", "/Applications/korTTY.app/Contents/MacOS/korTTY");
        System.setProperty(PolicyLocator.OVERRIDE_PROPERTY, tempPolicy.toString());
        assertThat(PolicyLocator.locate()).isEmpty();
    }

    @Test
    void overridePointingToMissingFileYieldsEmpty() {
        System.clearProperty("jpackage.app-path");
        System.setProperty(PolicyLocator.OVERRIDE_PROPERTY, "/nonexistent/kortty-policy.toml");
        assertThat(PolicyLocator.locate()).isEmpty();
    }

    @Test
    void withoutOverrideAndWithoutInstalledFileYieldsEmpty() {
        System.clearProperty("jpackage.app-path");
        System.clearProperty(PolicyLocator.OVERRIDE_PROPERTY);
        assertThat(PolicyLocator.locate()).isEmpty();
    }

    @Test
    void writableCheckFlagsUserWritableFiles() {
        assertThat(PolicyLocator.isWritableByCurrentUser(tempPolicy)).isTrue();
    }
}
