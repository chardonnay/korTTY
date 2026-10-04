package de.kortty.ui.sftp;

import de.kortty.core.RemoteDirectoryChange.Source;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/** Where "Open SFTP here" starts: {@link SftpOpenTargetResolver}. */
class SftpOpenTargetResolverTest {

    private static final String HOME = "/home/alice";

    @Test
    void anAbsoluteTrackedDirectoryIsUsed() {
        SftpOpenTargetResolver.OpenTarget target = resolve(false, "/var/log", Source.OSC7, "/ignored", HOME, false);
        assertThat(target.startPath()).isEqualTo("/var/log");
        assertThat(target.reason()).isEqualTo(SftpOpenTargetResolver.Reason.TRACKED_DIRECTORY);
        assertThat(target.hintKey()).isNull();
    }

    @Test
    void aTildePathIsResolvedAgainstTheHome() {
        assertThat(resolve(false, "~/x", Source.TYPED_CD, null, HOME, false).startPath()).isEqualTo("/home/alice/x");
        assertThat(resolve(false, "~", Source.TYPED_CD, null, HOME, false).startPath()).isEqualTo(HOME);
    }

    @Test
    void aRelativePathIsResolvedAgainstTheHome() {
        assertThat(resolve(false, "projects/app", Source.TYPED_CD, null, HOME, false).startPath())
            .isEqualTo("/home/alice/projects/app");
    }

    @Test
    void withoutAKnownHomeTildePathsStayForTheTabToResolve() {
        assertThat(resolve(false, "~/x", Source.TYPED_CD, null, "~", false).startPath()).isEqualTo("~/x");
        SftpOpenTargetResolver.OpenTarget home = resolve(false, "~", null, null, null, false);
        assertThat(home.startPath()).isNull();
        assertThat(home.reason()).isEqualTo(SftpOpenTargetResolver.Reason.LOGIN_DIRECTORY);
    }

    @Test
    void aForeignVerdictGivesTheLoginDirectoryWithAHint() {
        SftpOpenTargetResolver.OpenTarget target = resolve(true, "/root", Source.OSC7, "/root", HOME, false);
        assertThat(target.startPath()).isNull();
        assertThat(target.reason()).isEqualTo(SftpOpenTargetResolver.Reason.FOREIGN_SESSION);
        assertThat(target.hintKey()).isEqualTo("sftp.openHere.hint.foreign");
    }

    @Test
    void aStartupCommandGivesTheLoginDirectoryWithAHint() {
        SftpOpenTargetResolver.OpenTarget target = resolve(false, "/srv/app", Source.OSC7, null, HOME, true);
        assertThat(target.startPath()).isNull();
        assertThat(target.reason()).isEqualTo(SftpOpenTargetResolver.Reason.STARTUP_COMMAND);
        assertThat(target.hintKey()).isEqualTo("sftp.openHere.hint.startupCommand");
    }

    @Test
    void aStaleTypedDirectoryStaysAbsolute() {
        // The tab lists it and falls back to the login folder when it is gone.
        SftpOpenTargetResolver.OpenTarget target = resolve(false, "/gone/away", Source.TYPED_CD, null, HOME, false);
        assertThat(target.startPath()).isEqualTo("/gone/away");
        assertThat(target.reason()).isEqualTo(SftpOpenTargetResolver.Reason.TRACKED_DIRECTORY);
    }

    @Test
    void anAbsolutePromptDirectoryWinsOverATypedCd() {
        SftpOpenTargetResolver.OpenTarget target = resolve(false, "/typed", Source.TYPED_CD, "/from/prompt", HOME, false);
        assertThat(target.startPath()).isEqualTo("/from/prompt");
        assertThat(target.reason()).isEqualTo(SftpOpenTargetResolver.Reason.PROMPT_DIRECTORY);
    }

    @Test
    void nothingKnownGivesTheLoginDirectory() {
        SftpOpenTargetResolver.OpenTarget target = resolve(false, null, null, "  ", null, false);
        assertThat(target.startPath()).isNull();
        assertThat(target.reason()).isEqualTo(SftpOpenTargetResolver.Reason.LOGIN_DIRECTORY);
        assertThat(target.hintKey()).isNull();
    }

    @Test
    void everyOpenHereMessageIsTranslated() throws IOException {
        List<String> keys = new ArrayList<>(List.of("menu.connections.sftpHere", "tab.contextMenu.sftpHere",
            "terminal.contextMenu.sftpHere", "sftp.openHere.fallback", "sftp.openHere.noTerminal",
            "sftp.borrowed.tooltip", "sftp.borrowed.ownLogin"));
        for (SftpOpenTargetResolver.Reason reason : SftpOpenTargetResolver.Reason.values()) {
            if (reason.hintKey() != null) {
                keys.add(reason.hintKey());
            }
        }
        for (String locale : List.of("", "_de", "_es", "_fr", "_hr", "_it", "_nl", "_pt")) {
            Properties bundle = new Properties();
            bundle.load(new StringReader(Files.readString(
                Path.of("src/main/resources/i18n/messages" + locale + ".properties"), StandardCharsets.UTF_8)));
            for (String key : keys) {
                assertWithMessage("messages%s: %s", locale, key).that(bundle.getProperty(key)).isNotEmpty();
                assertWithMessage("messages%s: %s uses single apostrophes", locale, key)
                    .that(bundle.getProperty(key)).doesNotContain("''");
            }
        }
    }

    private static SftpOpenTargetResolver.OpenTarget resolve(boolean foreign, String tracked, Source source,
            String prompt, String home, boolean startupCommand) {
        return SftpOpenTargetResolver.resolve(
            new SftpOpenTargetResolver.Inputs(foreign, tracked, source, prompt, home, startupCommand));
    }
}
