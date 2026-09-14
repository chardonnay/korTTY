package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import org.testng.annotations.Test;

/**
 * What a dashboard pane row writes after its number.
 *
 * <p>The rule exists because the row's job is to tell two panes of one tab apart, and the shortest
 * thing that does so is the working directory's last segment. The home directory is the one case
 * where that segment is not a directory the user thinks in — it is their account name — and an
 * account name is exactly what a detected agent's name looks like in the same position.
 */
class DashboardPaneLabelTest {

    @Test
    void theHomeDirectoryReadsAsATildeRatherThanTheAccountName() {
        assertWithMessage("a pane sitting in the home directory must not render the account name,"
                + " because 'Pane 1 · claude' is indistinguishable from a row naming a detected agent")
            .that(DashboardView.cwdLabel("/Users/claude", "/Users/claude"))
            .isEqualTo("~");
        assertThat(DashboardView.cwdLabel("/home/codex", "/home/codex")).isEqualTo("~");
        assertThat(DashboardView.cwdLabel("C:\\Users\\gemini", "C:\\Users\\gemini")).isEqualTo("~");
    }

    @Test
    void anyOtherDirectoryStillContributesItsLastSegment() {
        assertThat(DashboardView.cwdLabel("/Users/claude/proj/api", "/Users/claude")).isEqualTo("api");
        assertThat(DashboardView.cwdLabel("/srv/app", "/Users/claude")).isEqualTo("app");
        assertThat(DashboardView.cwdLabel("C:\\Users\\me\\proj", "C:\\Users\\me")).isEqualTo("proj");
    }

    @Test
    void aDirectoryNamedAfterAnAgentOutsideTheHomeIsStillItsOwnName() {
        assertWithMessage("only the home directory is abbreviated; a real directory called 'claude'"
                + " is the pane's actual location and must keep its name")
            .that(DashboardView.cwdLabel("/Users/me/src/claude", "/Users/me"))
            .isEqualTo("claude");
    }

    @Test
    void anUnknownDirectoryHasNoLabel() {
        assertThat(DashboardView.cwdLabel(null, "/Users/claude")).isNull();
        assertThat(DashboardView.cwdLabel("   ", "/Users/claude")).isNull();
    }

    @Test
    void aMissingHomeLeavesThePathAlone() {
        assertThat(DashboardView.cwdLabel("/Users/claude", null)).isEqualTo("claude");
        assertThat(DashboardView.cwdLabel("/Users/claude", "")).isEqualTo("claude");
    }
}
