package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.testng.annotations.Test;

/**
 * Guards the {@code kortty-cli} launcher's packaging, because every way of getting it wrong fails
 * <strong>silently</strong>: a second {@code --add-launcher} line would produce a duplicate launcher,
 * a properties file under {@code --input} would be copied into the shipped app image, an inherited
 * {@code java-options} would print a JVM warning on the CLI's stderr at every start, and an inherited
 * shortcut hint would give a console tool a desktop entry.
 *
 * <p>These are text assertions on the build files rather than on a built image: building a native
 * package needs jpackage and an hour, and the mistakes worth catching are all visible in the source.
 */
class ControlApiPackagingContractTest {

    /** The repository root, found by walking up from the working directory. */
    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("build.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertWithMessage("build.gradle.kts was not found above the working directory")
            .that(candidate).isNotNull();
        return candidate;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test
    void exactlyOneAddLauncherLineDeclaresTheControlCli() throws Exception {
        String gradle = read("build.gradle.kts");
        assertWithMessage("one line in getJpackageBaseArgs reaches all six images and installers; a "
            + "second one would add the launcher twice")
            .that(occurrences(gradle, "\"--add-launcher\""))
            .isEqualTo(1);
        assertThat(gradle).contains("\"kortty-cli=\" + cliLauncherProperties.get().asFile.absolutePath");
    }

    @Test
    void theAddLauncherLineSitsInsideTheSharedBaseArgumentsAndNotInTheDmgTask() throws Exception {
        String gradle = read("build.gradle.kts");
        int baseArgs = gradle.indexOf("fun getJpackageBaseArgs(");
        int addLauncher = gradle.indexOf("\"--add-launcher\"");
        int afterBaseArgs = gradle.indexOf("\n// ==================== macOS", baseArgs);
        assertThat(baseArgs).isGreaterThan(0);
        assertWithMessage("jpackageDmg repackages the finished .app with --app-image and inherits the "
            + "launcher; giving it --add-launcher too would be an error")
            .that(addLauncher > baseArgs && addLauncher < afterBaseArgs)
            .isTrue();
    }

    @Test
    void everyJpackageExecTaskWaitsForTheGeneratedPropertiesFile() throws Exception {
        String gradle = read("build.gradle.kts");
        assertWithMessage("getJpackageBaseArgs is evaluated at configuration time but the file is only "
            + "needed at execution time, so each of the six Exec tasks must depend on the writer")
            .that(occurrences(gradle, "dependsOn(prepareCliLauncherProperties)"))
            .isEqualTo(occurrences(gradle, "dependsOn(prepareRuntimeImage)"));
    }

    @Test
    void thePropertiesFileIsWrittenOutsideTheJpackageInputDirectory() throws Exception {
        String gradle = read("build.gradle.kts");
        assertThat(gradle).contains("layout.buildDirectory.dir(\"jpackage-launchers\")");
        assertWithMessage("everything under --input is copied verbatim into the image's app/ directory")
            .that(gradle).doesNotContain("jpackageInput.map { it.file(\"kortty-cli.properties\") }");
    }

    @Test
    void thePropertiesFileCarriesTheKeysThatCannotBeInherited() throws Exception {
        String gradle = read("build.gradle.kts");
        assertThat(gradle).contains("main-class=de.kortty.cli.KorttyCli");
        assertThat(gradle).contains("description=korTTY control CLI");
        // java-options REPLACES the command line's rather than appending, so it must be spelled out.
        assertThat(gradle).contains("java-options=-XX:+UseSerialGC");
        // Absent keys are inherited from the GUI launcher.
        assertThat(gradle).contains("linux-shortcut=false");
        assertThat(gradle).contains("win-console=true");
        assertThat(gradle).contains("win-menu=false");
        assertThat(gradle).contains("win-shortcut=false");
        assertWithMessage("main-jar is deliberately inherited so the CLI sees the GUI's classpath")
            .that(gradle).doesNotContain("main-jar=");
    }

    @Test
    void theDmgPayloadComparisonIgnoresTheSecondMachO() throws Exception {
        String gradle = read("build.gradle.kts");
        assertWithMessage("jpackageDmg re-signs the app image it copies, and a second Mach-O in "
            + "Contents/MacOS is in the same position as the first")
            .that(gradle).contains("\"Contents/MacOS/kortty-cli\"");
    }

    @Test
    void theLauncherIsNotCalledKorttyBecauseThatNameIsAlreadyTaken() throws Exception {
        String gradle = read("build.gradle.kts");
        assertWithMessage("the GUI launcher is korTTY and /usr/bin/kortty is the pacman symlink; on "
            + "case-insensitive filesystems a launcher named kortty would collide")
            .that(gradle).doesNotContain("\"kortty=\"");
    }

    @Test
    void thePacmanPackageLinksTheControlCliWithoutDisturbingTheGuiSymlink() throws Exception {
        String pkgbuild = read("package/arch/PKGBUILD");
        assertThat(pkgbuild).contains("ln -s /usr/lib/kortty/bin/kortty-cli \"$pkgdir/usr/bin/kortty-cli\"");
        assertWithMessage("/usr/bin/kortty must keep pointing at the GUI launcher")
            .that(pkgbuild).contains("ln -s /usr/lib/kortty/bin/korTTY \"$pkgdir/usr/bin/kortty\"");

        String verifier = read("scripts/verify-pacman-package.sh");
        assertWithMessage("the assertion is optional by design — the packaging fixture carries only "
            + "the GUI launcher — so it checks the target, not the presence")
            .that(verifier).contains("/usr/lib/kortty/bin/kortty-cli");
    }

    @Test
    void theFlatpakManifestInstallsTheWrapperButKeepsTheGuiAsTheDefaultCommand() throws Exception {
        String manifest = read("package/flatpak/io.github.chardonnay.korTTY.yml");
        assertThat(manifest).contains("install -Dm755 kortty-cli-flatpak /app/bin/kortty-cli");
        assertThat(manifest).contains("path: kortty-cli-flatpak");
        assertWithMessage("command: is what `flatpak run` starts with no --command, and that stays the GUI")
            .that(manifest).contains("command: kortty");
        assertThat(manifest).doesNotContain("command: kortty-cli\n");

        String wrapper = read("package/flatpak/kortty-cli-flatpak");
        assertThat(wrapper).contains("exec /app/lib/kortty/bin/kortty-cli \"$@\"");
    }

    @Test
    void theReleaseWorkflowSmokeTestsTheLauncherOnEveryPlatform() throws Exception {
        String workflow = read(".github/workflows/build-release.yml");
        assertThat(workflow).contains("Contents/MacOS/kortty-cli");
        assertThat(workflow).contains("kortty-cli.exe");
        assertThat(workflow).contains("\"$app_dir/bin/kortty-cli\" --version");
        assertThat(workflow).contains("flatpak run --command=kortty-cli \"$APP_ID\" --version");
    }
}
