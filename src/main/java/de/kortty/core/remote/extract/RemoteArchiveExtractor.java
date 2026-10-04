package de.kortty.core.remote.extract;

import de.kortty.core.remote.RemoteCommandCancellation;
import de.kortty.core.remote.RemoteCommandException;
import de.kortty.core.remote.RemoteCommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Unpacks an archive on the server into a new folder next to it, without the data ever leaving the
 * server.
 *
 * <ol>
 *   <li>Checks with {@code command -v} that the format's tool is installed.</li>
 *   <li>Lists the members and refuses the archive, before anything is written, when it is password
 *       protected, when the listing cannot be read unambiguously, or when
 *       {@link ArchiveMemberValidator} finds a member that could land outside the folder.</li>
 *   <li>Extracts into a private {@code mktemp -d} staging folder next to the archive, without
 *       restoring owners.</li>
 *   <li>Lists every symlink in the staging folder ({@code find -P}) and refuses the result when one
 *       resolves outside it ({@link SymlinkEscapeCheck}).</li>
 *   <li>Renames the staging folder to the first free name of "archive", "archive (1)", … — a rename
 *       on the same file system, so the folder appears complete or not at all, and an existing
 *       folder is never written into.</li>
 * </ol>
 * A failure or cancel at any point after step 3 removes the staging folder.
 */
public final class RemoteArchiveExtractor {

    private static final Logger logger = LoggerFactory.getLogger(RemoteArchiveExtractor.class);

    /** How many "name (n)" folder names are tried. */
    static final int MAX_NAME_CANDIDATES = 100;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LIST_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration EXTRACT_TIMEOUT = Duration.ofHours(2);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofMinutes(2);
    private static final long LISTING_CAP = 32L * 1024 * 1024;

    /** What the extractor is doing, for the progress text. */
    public enum Phase {
        CHECKING,
        LISTING,
        EXTRACTING,
        VERIFYING,
        MOVING
    }

    /** A finished extraction: the new folder and how many members the archive listed. */
    public record Result(String folder, int members) {
    }

    private final RemoteCommandRunner runner;

    public RemoteArchiveExtractor(RemoteCommandRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** The format of a file name, or empty when it cannot be unpacked. */
    public static Optional<ArchiveKind> detect(String fileName) {
        return ArchiveKind.detect(fileName);
    }

    /** The tool this format needs when the server lacks it, or empty when it is installed. */
    public Optional<String> missingTool(ArchiveKind kind, RemoteCommandCancellation cancellation) throws IOException {
        RemoteCommandRunner.Result probe = runner.run(RemoteCommandRunner.Request.plain(ExtractCommandBuilder.toolProbe(kind))
            .timeout(PROBE_TIMEOUT).outputCap(4096).cancellation(cancellation));
        return probe.isSuccess() ? Optional.empty() : Optional.of(kind.tool());
    }

    /**
     * Extracts {@code archivePath} (absolute) into a new sibling folder.
     *
     * @param phases told about each phase as it starts; called on the calling thread
     * @throws ArchiveExtractException when the archive is refused or the extraction fails
     * @throws RemoteCommandException on cancel, timeout or a lost connection
     */
    public Result extract(String archivePath, RemoteCommandCancellation cancellation, Consumer<Phase> phases)
            throws IOException {
        Objects.requireNonNull(archivePath, "archivePath");
        Objects.requireNonNull(cancellation, "cancellation");
        Consumer<Phase> progress = phases != null ? phases : phase -> { };
        if (!archivePath.startsWith("/") || archivePath.endsWith("/")) {
            throw new IllegalArgumentException("The archive path must be an absolute file path.");
        }
        int slash = archivePath.lastIndexOf('/');
        String parent = slash == 0 ? "/" : archivePath.substring(0, slash);
        String fileName = archivePath.substring(slash + 1);
        ArchiveKind kind = ArchiveKind.detect(fileName)
            .orElseThrow(() -> new ArchiveExtractException(ArchiveExtractException.Reason.UNSUPPORTED_FORMAT, fileName));

        progress.accept(Phase.CHECKING);
        Optional<String> missing = missingTool(kind, cancellation);
        if (missing.isPresent()) {
            throw new ArchiveExtractException(ArchiveExtractException.Reason.MISSING_TOOL, missing.get());
        }
        boolean gnuTar = kind.isTar() && isGnuTar(cancellation);

        progress.accept(Phase.LISTING);
        ArchiveListingParser.Listing listing = list(kind, archivePath, gnuTar, cancellation);
        if (listing.encrypted()) {
            throw new ArchiveExtractException(ArchiveExtractException.Reason.PASSWORD_PROTECTED, fileName);
        }
        Optional<ArchiveMemberValidator.Violation> violation = ArchiveMemberValidator.check(listing.members());
        if (violation.isPresent()) {
            logger.warn("Refused to extract {}: member rejected ({})", fileName, violation.get().reason());
            throw new ArchiveExtractException(ArchiveExtractException.Reason.UNSAFE_MEMBER,
                violation.get().member(), violation.get().reason());
        }

        progress.accept(Phase.EXTRACTING);
        RemoteCommandRunner.Result made = runner.run(RemoteCommandRunner.Request.plain(
                ExtractCommandBuilder.makeStaging(parent))
            .timeout(PROBE_TIMEOUT).outputCap(8192).cancellation(cancellation));
        String staging = made.stdoutText().strip();
        if (!made.isSuccess() || !ExtractCommandBuilder.isStagingPath(parent, staging)) {
            throw new ArchiveExtractException(ArchiveExtractException.Reason.EXTRACT_FAILED, firstLine(made.stderr()));
        }
        boolean done = false;
        try {
            RemoteCommandRunner.Result extracted = runner.run(RemoteCommandRunner.Request.plain(
                    ExtractCommandBuilder.extract(kind, archivePath, staging))
                .timeout(EXTRACT_TIMEOUT).outputCap(1024 * 1024).cancellation(cancellation));
            if (!extractionSucceeded(kind, extracted.exitCode())) {
                throw new ArchiveExtractException(looksLikePassword(extracted)
                    ? ArchiveExtractException.Reason.PASSWORD_PROTECTED
                    : ArchiveExtractException.Reason.EXTRACT_FAILED, firstLine(extracted.stderr()));
            }

            progress.accept(Phase.VERIFYING);
            RemoteCommandRunner.Result scan = runner.run(RemoteCommandRunner.Request.plain(
                    ExtractCommandBuilder.linkScan(staging))
                .timeout(LIST_TIMEOUT).outputCap(LISTING_CAP).cancellation(cancellation));
            if (!scan.isSuccess() || scan.outputTruncated()) {
                throw new ArchiveExtractException(ArchiveExtractException.Reason.EXTRACT_FAILED, firstLine(scan.stderr()));
            }
            Map<String, String> links;
            try {
                links = parseLinkPairs(scan.stdout());
            } catch (IllegalArgumentException e) {
                throw new ArchiveExtractException(ArchiveExtractException.Reason.LISTING_UNREADABLE, "");
            }
            Optional<String> escape = SymlinkEscapeCheck.firstEscape(staging, links);
            if (escape.isPresent()) {
                logger.warn("Refused to extract {}: a symlink points outside the new folder", fileName);
                throw new ArchiveExtractException(ArchiveExtractException.Reason.UNSAFE_LINK, escape.get());
            }

            progress.accept(Phase.MOVING);
            RemoteCommandRunner.Result moved = runner.run(RemoteCommandRunner.Request.plain(
                    ExtractCommandBuilder.finish(staging, targetCandidates(parent, ArchiveKind.folderName(fileName))))
                .timeout(PROBE_TIMEOUT).outputCap(64 * 1024).cancellation(cancellation));
            if (moved.exitCode() == ExtractCommandBuilder.NO_FREE_NAME) {
                throw new ArchiveExtractException(ArchiveExtractException.Reason.NO_FREE_NAME,
                    ArchiveKind.folderName(fileName));
            }
            String folder = moved.stdoutText();
            if (!moved.isSuccess() || !folder.startsWith("/")) {
                throw new ArchiveExtractException(ArchiveExtractException.Reason.EXTRACT_FAILED, firstLine(moved.stderr()));
            }
            done = true;
            logger.info("Extracted {} ({} members, {}) into a new folder", fileName, listing.members().size(), kind);
            return new Result(folder, listing.members().size());
        } finally {
            if (!done) {
                removeStaging(staging);
            }
        }
    }

    private boolean isGnuTar(RemoteCommandCancellation cancellation) throws IOException {
        RemoteCommandRunner.Result version = runner.run(RemoteCommandRunner.Request.plain(ExtractCommandBuilder.gnuTarProbe())
            .timeout(PROBE_TIMEOUT).outputCap(4096).cancellation(cancellation));
        return version.stdoutText().contains("GNU tar");
    }

    private ArchiveListingParser.Listing list(ArchiveKind kind, String archive, boolean gnuTar,
                                              RemoteCommandCancellation cancellation) throws IOException {
        List<String> outputs = new ArrayList<>();
        for (String command : ExtractCommandBuilder.listCommands(kind, archive, gnuTar)) {
            RemoteCommandRunner.Result result = runner.run(RemoteCommandRunner.Request.plain(command)
                .timeout(LIST_TIMEOUT).outputCap(LISTING_CAP).cancellation(cancellation));
            if (result.outputTruncated()) {
                throw new ArchiveExtractException(ArchiveExtractException.Reason.LISTING_UNREADABLE, "");
            }
            // zipinfo exits 1 for warnings only; anything else is a failed listing
            boolean ok = result.isSuccess() || (kind == ArchiveKind.ZIP && result.exitCode() == 1);
            if (!ok) {
                throw new ArchiveExtractException(looksLikePassword(result)
                    ? ArchiveExtractException.Reason.PASSWORD_PROTECTED
                    : ArchiveExtractException.Reason.LISTING_UNREADABLE, firstLine(result.stderr()));
            }
            outputs.add(result.stdoutText());
        }
        try {
            return switch (kind) {
                case ZIP -> ArchiveListingParser.zip(outputs.get(0), outputs.get(1));
                case SEVEN_ZIP -> ArchiveListingParser.sevenZip(outputs.get(0));
                default -> gnuTar ? ArchiveListingParser.gnuTar(outputs.get(0))
                    : ArchiveListingParser.plainTar(outputs.get(0), outputs.get(1));
            };
        } catch (IllegalArgumentException e) {
            throw new ArchiveExtractException(ArchiveExtractException.Reason.LISTING_UNREADABLE, "");
        }
    }

    /** unzip and 7z exit 1 for warnings that still leave every file extracted. */
    private static boolean extractionSucceeded(ArchiveKind kind, int exitCode) {
        if (exitCode == 0) {
            return true;
        }
        return (kind == ArchiveKind.ZIP || kind == ArchiveKind.SEVEN_ZIP) && exitCode == 1;
    }

    private static boolean looksLikePassword(RemoteCommandRunner.Result result) {
        String text = (result.stderr() + "\n" + result.stdoutText()).toLowerCase(Locale.ROOT);
        return text.contains("password") || text.contains("encrypted");
    }

    /** Removes the staging folder with a fresh cancel switch, so it also runs after a cancel. */
    private void removeStaging(String staging) {
        try {
            runner.run(RemoteCommandRunner.Request.plain(ExtractCommandBuilder.cleanup(staging))
                .timeout(CLEANUP_TIMEOUT).outputCap(64 * 1024).cancellation(new RemoteCommandCancellation()));
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not remove the extraction staging folder: {}", e.getClass().getSimpleName());
        }
    }

    /** "name", "name (1)", … "name (99)" below {@code parent}. */
    static List<String> targetCandidates(String parent, String name) {
        String prefix = parent.endsWith("/") ? parent : parent + "/";
        List<String> candidates = new ArrayList<>(MAX_NAME_CANDIDATES);
        candidates.add(prefix + name);
        for (int i = 1; i < MAX_NAME_CANDIDATES; i++) {
            candidates.add(prefix + name + " (" + i + ")");
        }
        return candidates;
    }

    /** NUL-separated (path, target) pairs from {@link ExtractCommandBuilder#linkScan}. */
    static Map<String, String> parseLinkPairs(byte[] output) {
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < output.length; i++) {
            if (output[i] == 0) {
                fields.add(new String(output, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start != output.length || fields.size() % 2 != 0) {
            throw new IllegalArgumentException("Unreadable symlink listing.");
        }
        Map<String, String> links = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i += 2) {
            links.put(fields.get(i), fields.get(i + 1));
        }
        return links;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline >= 0 ? trimmed.substring(0, newline) : trimmed;
        return line.length() > 300 ? line.substring(0, 300) : line;
    }
}
