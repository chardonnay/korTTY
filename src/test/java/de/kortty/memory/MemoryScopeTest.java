package de.kortty.memory;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class MemoryScopeTest {

    @Test
    void globalScopeUsesTheGlobalFileAndKey() {
        MemoryScope global = MemoryScope.global();
        assertThat(global.isGlobal()).isTrue();
        assertThat(global.relativePath()).isEqualTo("MEMORY.md");
        assertThat(global.key()).isEqualTo("global");
    }

    @Test
    void connectionScopeUsesAConnectionsFile() {
        MemoryScope scope = MemoryScope.connection("3fa9c1e2-0000-4000-8000-000000000001");
        assertThat(scope.isGlobal()).isFalse();
        assertThat(scope.relativePath()).isEqualTo("connections/3fa9c1e2-0000-4000-8000-000000000001.md");
        assertThat(scope.key()).isEqualTo("connection-3fa9c1e2-0000-4000-8000-000000000001");
    }

    @Test
    void idsWithPathCharactersCannotEscapeTheConnectionsDirectory() {
        MemoryScope scope = MemoryScope.connection("../../etc/passwd");
        assertThat(scope.relativePath()).startsWith("connections/");
        assertThat(scope.relativePath()).doesNotContain("..");
        assertThat(scope.relativePath()).doesNotContain("/etc");
    }

    @Test
    void idsThatNeededChangesGetDistinctFiles() {
        assertThat(MemoryScope.connection("a b").relativePath())
            .isNotEqualTo(MemoryScope.connection("a_b").relativePath());
    }

    @Test
    void blankConnectionIdIsRejected() {
        org.testng.Assert.assertThrows(IllegalArgumentException.class, () -> MemoryScope.connection("  "));
    }
}
