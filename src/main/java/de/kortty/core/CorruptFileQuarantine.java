package de.kortty.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Moves an unreadable data file aside as {@code <name>.corrupt-<yyyyMMdd-HHmmss>} so the app can
 * continue with defaults without the next save overwriting the user's data. The original bytes are
 * never deleted: a manager that cannot parse its file renames it, keeps working, and the user can
 * repair or restore the copy later.
 */
public final class CorruptFileQuarantine {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private CorruptFileQuarantine() {
    }

    /**
     * Renames {@code file} to a sibling {@code .corrupt-<timestamp>} name (falling back to a copy
     * when the rename fails) and returns the backup's path.
     *
     * @throws IOException when neither a move nor a copy succeeded — the caller must then refuse
     *                     to write over the file
     */
    public static Path moveAside(Path file) throws IOException {
        Path backup = freeBackupName(file);
        try {
            Files.move(file, backup, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException moveFailure) {
            try {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException copyFailure) {
                copyFailure.addSuppressed(moveFailure);
                throw copyFailure;
            }
        }
        return backup;
    }

    private static Path freeBackupName(Path file) {
        String base = file.getFileName() + ".corrupt-" + LocalDateTime.now().format(TIMESTAMP);
        Path candidate = file.resolveSibling(base);
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = file.resolveSibling(base + "-" + i);
        }
        return candidate;
    }
}
