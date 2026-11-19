package de.kortty.testutil;

import org.testng.SkipException;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SymbolicLinks {

    private SymbolicLinks() {
    }

    public static void createOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException e) {
            throw new SkipException("Symbolic links are not available for this test", e);
        } catch (FileSystemException e) {
            // Windows reports a missing symlink privilege as a generic, localized filesystem error.
            if (link.getFileSystem().getSeparator().equals("\\")
                    && (e.getClass() == FileSystemException.class || e instanceof AccessDeniedException)) {
                throw new SkipException("The Windows filesystem or account cannot create symbolic links", e);
            }
            throw e;
        }
    }
}
