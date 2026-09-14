package de.kortty.codingagent.desktop;

import java.nio.file.Path;

/**
 * Compares the POSIX paths these Linux-only rules deal in, on whatever host runs the tests.
 *
 * <p>The rules under test resolve desktop files, icons and PATH entries that only ever exist on
 * Linux, and the tests name them as POSIX literals. A {@link Path} does not render the same way
 * everywhere — Windows swaps the separator, and {@code toAbsolutePath()} prepends a drive — so
 * comparing the strings quietly asserts "and this test is running on a POSIX host" alongside the
 * behaviour it means to pin. That is why these tests failed on the Windows runner while the logic
 * they cover was correct.
 *
 * <p>Reducing both sides to the same canonical form compares what the rule actually decided, and
 * keeps the tests running everywhere rather than skipping them off Linux.
 */
final class LinuxPathsTestSupport {

    private LinuxPathsTestSupport() {
    }

    /** The absolute, normalised form of {@code path}; never touches the disk. */
    static Path canonical(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** The absolute, normalised form of a path written as a POSIX literal. */
    static Path canonical(String path) {
        return canonical(Path.of(path));
    }
}
