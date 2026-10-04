package de.kortty.core;

import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Host-key exemptions of Connection Manager folders follow the folder, not its name: renaming a
 * folder moves its exemption and those of its subfolders, deleting it drops them, and a folder that
 * later takes a deleted or renamed folder's name starts with verification on.
 */
class HostKeyCheckGroupExemptionsTest {

    private static List<String> list(String... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    private static HostKeyCheckMode modeIn(String group, List<String> exempt) {
        ServerConnection connection = new ServerConnection("n", "h", 22, "u");
        connection.setGroup(group);
        return HostKeyCheckPolicy.resolve(connection, false, exempt);
    }

    @Test
    void renamingAFolderMovesItsExemptionAndThoseOfItsSubfolders() {
        List<String> after = HostKeyCheckGroupExemptions.renamed(
            list("Lab", "Lab/DB", "Other"), "Lab", "Test", list("Lab", "Lab/DB", "Lab/Web", "Other"));

        assertThat(after).containsExactly("Other", "Test", "Test/DB").inOrder();
        assertThat(modeIn("Test", after)).isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
        assertThat(modeIn("Test/DB", after)).isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
        assertWithMessage("a subfolder without an exemption keeps verification on under its new path")
            .that(modeIn("Test/Web", after)).isEqualTo(HostKeyCheckMode.STRICT);
        assertWithMessage("a new folder named like the old one does not inherit the exemption")
            .that(modeIn("Lab", after)).isEqualTo(HostKeyCheckMode.STRICT);
        assertThat(modeIn("Lab/DB", after)).isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void renamingANestedFolderMovesOnlyItsOwnSubtree() {
        List<String> after = HostKeyCheckGroupExemptions.renamed(
            list("Work/Lab/DB", "Work/Lab2", "Work"), "Work/Lab", "Work/Test",
            list("Work", "Work/Lab/DB", "Work/Lab2"));

        assertWithMessage("Lab2 is not below Lab, and the parent folder is not touched")
            .that(after).containsExactly("Work/Lab2", "Work", "Work/Test/DB").inOrder();
    }

    @Test
    void deletingAFolderDropsItsExemptionAndThoseOfItsSubfolders() {
        List<String> after = HostKeyCheckGroupExemptions.deleted(
            list("Lab", "Lab/DB", "Lab/DB/Replica", "Lab2", "Other"), "Lab");

        assertThat(after).containsExactly("Lab2", "Other").inOrder();
    }

    @Test
    void aNewFolderWithADeletedFoldersNameHasVerificationOn() {
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        settings.setHostKeyCheckDisabledForGroup("Lab", true);
        settings.setHostKeyCheckDisabledForGroup("Lab/DB", true);
        assertThat(modeIn("Lab", settings.getHostKeyCheckDisabledGroups())).isEqualTo(HostKeyCheckMode.ACCEPT_NEW);

        settings.setHostKeyCheckDisabledGroups(
            HostKeyCheckGroupExemptions.deleted(settings.getHostKeyCheckDisabledGroups(), "Lab"));

        assertThat(settings.isHostKeyCheckDisabledForGroup("Lab")).isFalse();
        assertThat(modeIn("Lab", settings.getHostKeyCheckDisabledGroups())).isEqualTo(HostKeyCheckMode.STRICT);
        assertThat(modeIn("Lab/DB", settings.getHostKeyCheckDisabledGroups())).isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void aFolderRenamedToALeftoverExemptionsNameKeepsVerificationOn() {
        // "Old" was deleted by an earlier version, which left its exemption behind.
        List<String> after = HostKeyCheckGroupExemptions.renamed(
            list("Old", "Old/DB"), "Prod", "Old", list("Prod", "Prod/DB"));

        assertThat(after).isEmpty();
        assertThat(modeIn("Old", after)).isEqualTo(HostKeyCheckMode.STRICT);
        assertThat(modeIn("Old/DB", after)).isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void mergingIntoAnExistingFolderKeepsVerificationOffOnlyIfBothHadItOff() {
        List<String> groups = list("Lab", "Test");

        assertThat(HostKeyCheckGroupExemptions.renamed(list("Lab", "Test"), "Lab", "Test", groups))
            .containsExactly("Test");
        assertWithMessage("the target's exemption does not reach the verified connections moved into it")
            .that(HostKeyCheckGroupExemptions.renamed(list("Test"), "Lab", "Test", groups)).isEmpty();
        assertWithMessage("the moved exemption does not reach the verified connections already in the target")
            .that(HostKeyCheckGroupExemptions.renamed(list("Lab"), "Lab", "Test", groups)).isEmpty();
        assertThat(HostKeyCheckGroupExemptions.renamed(list(), "Lab", "Test", groups)).isEmpty();
    }

    @Test
    void mergingDecidesEachSubfolderOnItsOwn() {
        List<String> groups = list("Lab/DB", "Lab/Web", "Lab/App", "Test/DB", "Test/Web", "Test/Mail");
        List<String> after = HostKeyCheckGroupExemptions.renamed(
            list("Lab/DB", "Test/DB", "Lab/Web", "Test/App", "Test/Mail"), "Lab", "Test", groups);

        assertWithMessage("DB was off in both, Web only in Lab, App only in a Test/App left over from a "
                + "deleted folder, and Mail is not touched by the rename")
            .that(after).containsExactly("Test/Mail", "Test/DB").inOrder();
        assertThat(modeIn("Test/Web", after)).isEqualTo(HostKeyCheckMode.STRICT);
        assertWithMessage("the verified Lab/App does not take the leftover exemption of its new path")
            .that(modeIn("Test/App", after)).isEqualTo(HostKeyCheckMode.STRICT);
        assertThat(modeIn("Test/Mail", after)).isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
    }

    @Test
    void anExemptionBelowTheFolderWithoutConnectionsIsDropped() {
        List<String> after = HostKeyCheckGroupExemptions.renamed(
            list("Lab/Gone", "Test/Kept"), "Lab", "Test", list("Lab", "Test/Kept"));

        assertWithMessage("Lab/Gone has no connections, so it neither moves nor stays behind")
            .that(after).containsExactly("Test/Kept");
    }

    @Test
    void pathsAreComparedAfterTrimmingTheirSegments() {
        assertThat(HostKeyCheckGroupExemptions.renamed(list(" Lab / DB "), "Lab", " Test ", list("Lab / DB")))
            .containsExactly("Test/DB");
        assertThat(HostKeyCheckGroupExemptions.deleted(list(" Lab ", "Lab /DB", "Other"), " Lab"))
            .containsExactly("Other");
    }

    @Test
    void untouchedEntriesKeepTheirStoredForm() {
        assertThat(HostKeyCheckGroupExemptions.renamed(list(" Other ", "Lab"), "Lab", "Test", list("Lab", "Other")))
            .containsExactly(" Other ", "Test").inOrder();
        assertThat(HostKeyCheckGroupExemptions.deleted(list(" Other "), "Lab")).containsExactly(" Other ");
    }

    @Test
    void renamingToNoGroupDropsTheExemption() {
        assertThat(HostKeyCheckGroupExemptions.renamed(list("Lab", "Lab/DB", "Other"), "Lab", "/", list("Lab")))
            .containsExactly("Other");
    }

    @Test
    void aRenameWithoutAChangeOrWithoutAFolderChangesNothing() {
        List<String> exempt = list("Lab", "Other");

        assertThat(HostKeyCheckGroupExemptions.renamed(exempt, "Lab", " Lab ", list("Lab"))).isEqualTo(exempt);
        assertThat(HostKeyCheckGroupExemptions.renamed(exempt, " ", "Test", list("Lab"))).isEqualTo(exempt);
        assertThat(HostKeyCheckGroupExemptions.renamed(exempt, null, "Test", null)).isEqualTo(exempt);
        assertThat(HostKeyCheckGroupExemptions.deleted(exempt, " / ")).isEqualTo(exempt);
        assertThat(HostKeyCheckGroupExemptions.deleted(exempt, null)).isEqualTo(exempt);
    }

    @Test
    void theStoredListIsNeverChangedInPlace() {
        List<String> exempt = list("Lab", "Lab/DB");

        HostKeyCheckGroupExemptions.renamed(exempt, "Lab", "Test", list("Lab", "Lab/DB"));
        HostKeyCheckGroupExemptions.deleted(exempt, "Lab");

        assertThat(exempt).containsExactly("Lab", "Lab/DB").inOrder();
    }

    @Test
    void noStoredExemptionsGiveNone() {
        assertThat(HostKeyCheckGroupExemptions.renamed(null, "Lab", "Test", list("Lab"))).isEmpty();
        assertThat(HostKeyCheckGroupExemptions.deleted(null, "Lab")).isEmpty();
        assertThat(HostKeyCheckGroupExemptions.renamed(list("Lab"), "Lab", "Test", null)).containsExactly("Test");
    }
}
