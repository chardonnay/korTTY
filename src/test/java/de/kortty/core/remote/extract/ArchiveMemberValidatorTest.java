package de.kortty.core.remote.extract;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

public class ArchiveMemberValidatorTest {

    private static Optional<ArchiveMemberValidator.Reason> name(String name) {
        return ArchiveMemberValidator.check(ArchiveMember.of(name));
    }

    private static Optional<ArchiveMemberValidator.Reason> symlink(String name, String target) {
        return ArchiveMemberValidator.check(new ArchiveMember(name, ArchiveMember.Type.SYMLINK, target));
    }

    private static Optional<ArchiveMemberValidator.Reason> hardlink(String name, String target) {
        return ArchiveMemberValidator.check(new ArchiveMember(name, ArchiveMember.Type.HARDLINK, target));
    }

    @Test
    public void traversalAndAbsoluteNamesAreRefused() {
        assertThat(name("../../etc/passwd")).hasValue(ArchiveMemberValidator.Reason.PARENT_TRAVERSAL);
        assertThat(name("a/../../b")).hasValue(ArchiveMemberValidator.Reason.PARENT_TRAVERSAL);
        assertThat(name("/abs")).hasValue(ArchiveMemberValidator.Reason.ABSOLUTE_PATH);
        assertThat(name("C:\\x")).hasValue(ArchiveMemberValidator.Reason.DRIVE_LETTER);
        assertThat(name("c:x")).hasValue(ArchiveMemberValidator.Reason.DRIVE_LETTER);
        assertThat(name("..\\..\\windows\\x")).hasValue(ArchiveMemberValidator.Reason.PARENT_TRAVERSAL);
        assertThat(name("\\\\server\\share")).hasValue(ArchiveMemberValidator.Reason.ABSOLUTE_PATH);
        assertThat(name("")).hasValue(ArchiveMemberValidator.Reason.EMPTY_NAME);
    }

    @Test
    public void controlCharactersAreRefused() {
        assertThat(name("a\nb")).hasValue(ArchiveMemberValidator.Reason.CONTROL_CHARACTER);
        assertThat(name("a\0b")).hasValue(ArchiveMemberValidator.Reason.CONTROL_CHARACTER);
        assertThat(name("a\rb")).hasValue(ArchiveMemberValidator.Reason.CONTROL_CHARACTER);
    }

    @Test
    public void validNestedPathsPass() {
        assertThat(name("a.txt")).isEmpty();
        assertThat(name("./dir/sub/file name with spaces.txt")).isEmpty();
        assertThat(name("dir/")).isEmpty();
        assertThat(name("./")).isEmpty();
        assertThat(name("a/b/../c")).isEmpty();
        assertThat(name("..hidden/..x")).isEmpty();
        assertThat(name("ünïcode/ファイル")).isEmpty();
        assertThat(ArchiveMemberValidator.check(List.of(ArchiveMember.of("a"), ArchiveMember.of("b/c")))).isEmpty();
    }

    @Test
    public void symlinksThatEscapeAreRefused() {
        assertThat(symlink("esc", "/etc")).hasValue(ArchiveMemberValidator.Reason.LINK_ESCAPE);
        assertThat(symlink("esc", "../outside")).hasValue(ArchiveMemberValidator.Reason.LINK_ESCAPE);
        assertThat(symlink("a/b/esc", "../../..")).hasValue(ArchiveMemberValidator.Reason.LINK_ESCAPE);
        assertThat(symlink("esc", "C:\\Windows")).hasValue(ArchiveMemberValidator.Reason.LINK_ESCAPE);
        assertThat(symlink("esc", "")).hasValue(ArchiveMemberValidator.Reason.LINK_ESCAPE);
    }

    @Test
    public void symlinksInsideTheArchivePass() {
        assertThat(symlink("a/b/link", "../c/file")).isEmpty();
        assertThat(symlink("link", "sub/file")).isEmpty();
        // a target the listing did not show is checked after extraction
        assertThat(symlink("link", null)).isEmpty();
    }

    @Test
    public void hardlinksMustPointAtASafeMember() {
        assertThat(hardlink("h", "/etc/shadow")).hasValue(ArchiveMemberValidator.Reason.LINK_ESCAPE);
        assertThat(hardlink("h", "../../etc/shadow")).hasValue(ArchiveMemberValidator.Reason.LINK_ESCAPE);
        assertThat(hardlink("h", null)).hasValue(ArchiveMemberValidator.Reason.LINK_ESCAPE);
        assertThat(hardlink("h", "dir/file")).isEmpty();
    }

    @Test
    public void specialEntriesAreRefused() {
        assertThat(ArchiveMemberValidator.check(new ArchiveMember("dev/null", ArchiveMember.Type.SPECIAL, null)))
            .hasValue(ArchiveMemberValidator.Reason.SPECIAL_ENTRY);
    }

    @Test
    public void theFirstViolationNamesItsMember() {
        Optional<ArchiveMemberValidator.Violation> violation = ArchiveMemberValidator.check(List.of(
            ArchiveMember.of("ok.txt"), ArchiveMember.of("../evil"), ArchiveMember.of("/abs")));
        assertThat(violation).hasValue(
            new ArchiveMemberValidator.Violation("../evil", ArchiveMemberValidator.Reason.PARENT_TRAVERSAL));
    }
}
