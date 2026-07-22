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

    @Test(timeOut = 10_000)
    void liveOsGroupLookupCompletesWellWithinTheTimeoutAndIsCached() {
        OsUserIdentity identity = new OsUserIdentity();
        long start = System.nanoTime();
        java.util.Set<String> first = identity.osGroups();
        long firstMillis = (System.nanoTime() - start) / 1_000_000;
        // The subprocess is time-boxed to 3s; a healthy machine returns near-instantly, and the
        // drain-thread rework guarantees the timeout is honored even if the child stalled.
        assertThat(firstMillis).isLessThan(4_000L);

        // Second call must be served from the cache (same instance), spawning no new process.
        java.util.Set<String> second = identity.osGroups();
        assertThat(second).isSameInstanceAs(first);
    }
}
