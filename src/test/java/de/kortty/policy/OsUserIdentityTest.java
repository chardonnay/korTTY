package de.kortty.policy;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class OsUserIdentityTest {

    @Test
    void userNameIsTrimmedAndLowercased() {
        assertThat(new OsUserIdentity(" Alice ").userName()).isEqualTo("alice");
        assertThat(new OsUserIdentity(null).userName()).isEmpty();
    }

    @Test
    void parsesUnixGroupOutput() {
        assertThat(OsUserIdentity.parseUnixGroups("staff Wheel docker\n"))
            .containsExactly("staff", "wheel", "docker");
        assertThat(OsUserIdentity.parseUnixGroups("")).isEmpty();
    }

    @Test
    void parsesWindowsGroupCsvWithQualifiedAndBareNames() {
        String csv = """
            "ACME\\Domain Users","Group","S-1-5-21-1","Mandatory group"
            "BUILTIN\\Users","Alias","S-1-5-32-545","Mandatory group"
            "Everyone","Well-known group","S-1-1-0","Mandatory group"
            """;
        assertThat(OsUserIdentity.parseWindowsGroups(csv)).containsExactly(
            "acme\\domain users", "domain users",
            "builtin\\users", "users",
            "everyone");
    }

    @Test
    void windowsParserIgnoresGarbageLines() {
        assertThat(OsUserIdentity.parseWindowsGroups("GROUP INFORMATION\n-----\n\n")).isEmpty();
    }

    @Test
    void liveOsGroupLookupDoesNotThrow() {
        // Environment-dependent — only asserts the contract: non-null, all lowercase.
        OsUserIdentity identity = new OsUserIdentity();
        assertThat(identity.osGroups()).isNotNull();
        identity.osGroups().forEach(group ->
            assertThat(group).isEqualTo(group.toLowerCase(java.util.Locale.ROOT)));
    }
}
