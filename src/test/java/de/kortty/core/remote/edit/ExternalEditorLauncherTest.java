package de.kortty.core.remote.edit;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * The external editor is started without a shell: the command template splits into argv with
 * quotes respected, {@code {file}} is one argument, Linux opens only text through xdg-open, and
 * hostile remote names become safe local names.
 */
class ExternalEditorLauncherTest {

    private static final Path FILE = Path.of("/tmp/kortty-remote-edit-x/my file.txt").toAbsolutePath();

    @Test
    void theTemplateSplitsLikeShellWordsWithoutExpandingAnything() {
        assertThat(ExternalEditorLauncher.parseTemplate("code --wait {file}"))
            .containsExactly("code", "--wait", "{file}").inOrder();
        assertThat(ExternalEditorLauncher.parseTemplate("\"/Applications/Sublime Text.app/bin/subl\"  -w {file}"))
            .containsExactly("/Applications/Sublime Text.app/bin/subl", "-w", "{file}").inOrder();
        assertThat(ExternalEditorLauncher.parseTemplate("'C:\\Program Files\\Notepad++\\notepad++.exe' -multiInst {file}"))
            .containsExactly("C:\\Program Files\\Notepad++\\notepad++.exe", "-multiInst", "{file}").inOrder();
        assertThat(ExternalEditorLauncher.parseTemplate("C:\\Tools\\vim.exe {file}"))
            .containsExactly("C:\\Tools\\vim.exe", "{file}").inOrder();
        assertThat(ExternalEditorLauncher.parseTemplate("ed \"say \\\"hi\\\"\" a\"b c\"d"))
            .containsExactly("ed", "say \"hi\"", "ab cd").inOrder();
        // Nothing a shell would interpret is special here.
        assertThat(ExternalEditorLauncher.parseTemplate("vi $HOME;rm -rf ~ `id` %PATH% | x"))
            .containsExactly("vi", "$HOME;rm", "-rf", "~", "`id`", "%PATH%", "|", "x").inOrder();
        assertThat(ExternalEditorLauncher.parseTemplate("  ")).isEmpty();
    }

    @Test
    void unclosedQuotesAreRefused() {
        expectThrows(IllegalArgumentException.class, () -> ExternalEditorLauncher.parseTemplate("code \"--wait {file}"));
        expectThrows(IllegalArgumentException.class, () -> ExternalEditorLauncher.parseTemplate("code '{file}"));
        assertThat(ExternalEditorLauncher.isValidTemplate("code \"x")).isFalse();
        assertThat(ExternalEditorLauncher.isValidTemplate("{file}")).isFalse();
        assertThat(ExternalEditorLauncher.isValidTemplate("")).isTrue();
        assertThat(ExternalEditorLauncher.isValidTemplate("code --wait {file}")).isTrue();
    }

    @Test
    void theFileIsOneArgumentEvenWithSpacesAndIsAppendedWithoutPlaceholder() {
        assertThat(ExternalEditorLauncher.commandFromTemplate("code --wait {file}", FILE))
            .containsExactly("code", "--wait", FILE.toString()).inOrder();
        assertThat(ExternalEditorLauncher.commandFromTemplate("emacsclient", FILE))
            .containsExactly("emacsclient", FILE.toString()).inOrder();
        assertThat(ExternalEditorLauncher.commandFromTemplate("idea --file={file}", FILE))
            .containsExactly("idea", "--file=" + FILE).inOrder();
    }

    @Test
    void aTemplateBeatsTheSystemDefaultOnEverySystem() {
        for (ExternalEditorLauncher.Os os : ExternalEditorLauncher.Os.values()) {
            ExternalEditorLauncher.Plan plan = ExternalEditorLauncher.plan(os, "kate {file}", FILE, null);
            assertThat(plan).isEqualTo(new ExternalEditorLauncher.Command(List.of("kate", FILE.toString())));
        }
    }

    @Test
    void theDefaultsOpenAsTextAndNeverThroughAShell() {
        assertThat(ExternalEditorLauncher.plan(ExternalEditorLauncher.Os.MAC, null, FILE, null))
            .isEqualTo(new ExternalEditorLauncher.Command(List.of("open", "-t", FILE.toString())));
        assertThat(ExternalEditorLauncher.plan(ExternalEditorLauncher.Os.WINDOWS, "", FILE, "text/plain"))
            .isEqualTo(new ExternalEditorLauncher.DesktopEdit(FILE));
        assertThat(ExternalEditorLauncher.plan(ExternalEditorLauncher.Os.LINUX, " ", FILE, "text/x-python"))
            .isEqualTo(new ExternalEditorLauncher.Command(List.of("xdg-open", FILE.toString())));
    }

    @Test
    void linuxRefusesNonTextTypesUntilACommandIsSet() {
        for (String type : new String[] {null, "application/x-shellscript", "application/x-executable",
                "application/x-desktop", "image/png"}) {
            assertThat(ExternalEditorLauncher.plan(ExternalEditorLauncher.Os.LINUX, null, FILE, type))
                .isInstanceOf(ExternalEditorLauncher.NeedsCommand.class);
        }
    }

    @Test
    void hostileRemoteNamesBecomeSafeLocalNames() {
        assertThat(RemoteEditNames.safeLocalName("a&b%PATH%.txt")).isEqualTo("a_b_PATH_.txt");
        assertThat(RemoteEditNames.safeLocalName("CON.txt")).isEqualTo("_CON.txt");
        assertThat(RemoteEditNames.safeLocalName("con")).isEqualTo("_con");
        assertThat(RemoteEditNames.safeLocalName("Com1.tar.gz")).isEqualTo("_Com1.tar.gz");
        assertThat(RemoteEditNames.safeLocalName("x.")).isEqualTo("x");
        assertThat(RemoteEditNames.safeLocalName("notes. . ")).isEqualTo("notes");
        assertThat(RemoteEditNames.safeLocalName("/etc/nginx/sites-enabled/my site.conf")).isEqualTo("my site.conf");
        assertThat(RemoteEditNames.safeLocalName("..")).isEqualTo("file");
        assertThat(RemoteEditNames.safeLocalName("")).isEqualTo("file");
        assertThat(RemoteEditNames.safeLocalName("$(reboot);`id`|x>y.sh")).isEqualTo("__reboot___id__x_y.sh");
        assertThat(RemoteEditNames.safeLocalName("dir\\..\\..\\evil.bat")).isEqualTo("dir_.._.._evil.bat");
        assertThat(RemoteEditNames.safeLocalName("Über ünïcode.md")).isEqualTo("_ber _n_code.md");
        String longName = "x".repeat(300) + ".yaml";
        String shortened = RemoteEditNames.safeLocalName(longName);
        assertThat(shortened.length()).isAtMost(RemoteEditNames.MAX_LENGTH);
        assertThat(shortened).endsWith(".yaml");
        for (String name : List.of("a%b", "%%", "100%.txt")) {
            assertThat(RemoteEditNames.safeLocalName(name)).doesNotContain("%");
        }
    }

    @Test
    void noShellIsEverUsed() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/de/kortty/core/remote/edit/ExternalEditorLauncher.java"), StandardCharsets.UTF_8);
        String code = source.substring(source.indexOf("public static void start("));
        assertThat(code).contains("new ProcessBuilder(command.argv())");
        assertThat(code).doesNotContain("\"cmd\"");
        assertThat(code).doesNotContain("\"sh\"");
        assertThat(code).doesNotContain("/bin/sh");
        assertThat(code).doesNotContain(".open(");
    }
}
