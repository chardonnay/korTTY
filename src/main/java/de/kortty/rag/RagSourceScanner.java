package de.kortty.rag;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Recursive, no-follow scanner with deterministic filtering and full preview diagnostics. */
public final class RagSourceScanner {
    public static final int DEFAULT_MAX_FILES = 5_000;
    public static final long DEFAULT_MAX_SOURCE_BYTES = 500L * 1024 * 1024;
    public static final long DEFAULT_MAX_EXTRACTED_CHARS = 100L * 1024 * 1024;

    public static final Set<String> STANDARD_EXCLUDED_DIRECTORIES = Set.of(
        ".git", ".hg", ".svn", ".gradle", ".idea", ".vscode", ".venv", "venv",
        "node_modules", "vendor", "build", "target", "dist", "out", "coverage", "__pycache__");

    private final RagSourceFormatRegistry formats;
    private final RagTextExtractor extractor;
    private final int maxFiles;
    private final long maxSourceBytes;
    private final long maxExtractedChars;

    public RagSourceScanner() {
        this(new RagSourceFormatRegistry(), new RagTextExtractor(), DEFAULT_MAX_FILES,
            DEFAULT_MAX_SOURCE_BYTES, DEFAULT_MAX_EXTRACTED_CHARS);
    }

    public RagSourceScanner(
        RagSourceFormatRegistry formats,
        RagTextExtractor extractor,
        int maxFiles,
        long maxSourceBytes,
        long maxExtractedChars
    ) {
        this.formats = formats;
        this.extractor = extractor;
        this.maxFiles = maxFiles;
        this.maxSourceBytes = maxSourceBytes;
        this.maxExtractedChars = maxExtractedChars;
    }

    public RagScanPreview preview(RagSource source, CancellationToken cancellation) {
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        ScanState state = new ScanState(source);
        token.throwIfCancelled();
        if (!Files.exists(source.path(), LinkOption.NOFOLLOW_LINKS)) {
            state.problem(source.path(), RagScanPreview.ProblemCode.SOURCE_MISSING,
                RagScanPreview.Severity.ERROR, "Source does not exist");
            return state.result();
        }
        if (Files.isSymbolicLink(source.path())) {
            state.problem(source.path(), RagScanPreview.ProblemCode.SYMBOLIC_LINK,
                RagScanPreview.Severity.ERROR, "Symbolic links are not followed");
            return state.result();
        }
        boolean directory = Files.isDirectory(source.path(), LinkOption.NOFOLLOW_LINKS);
        if ((source.type() == RagSourceType.DIRECTORY) != directory) {
            state.problem(source.path(), RagScanPreview.ProblemCode.TYPE_MISMATCH,
                RagScanPreview.Severity.ERROR, "Configured source type does not match the path");
            return state.result();
        }
        try {
            if (directory) {
                scanDirectory(state, token);
            } else {
                scanFile(state, source.path(), source.path().getFileName(), token, null);
            }
        } catch (IOException | SecurityException error) {
            state.problem(source.path(), RagScanPreview.ProblemCode.EXTRACTION_FAILED,
                RagScanPreview.Severity.ERROR, safeMessage(error));
        }
        return state.result();
    }

    /** Returns an existing source that is identical, contains, or is contained by the candidate. */
    public Optional<RagSource> findOverlap(RagSource candidate, Collection<RagSource> existing) {
        Path candidatePath = realOrAbsolute(candidate.path());
        if (existing == null) {
            return Optional.empty();
        }
        return existing.stream()
            .filter(other -> other != null && !other.id().equals(candidate.id()))
            .filter(other -> {
                Path otherPath = realOrAbsolute(other.path());
                if (candidatePath.equals(otherPath)) {
                    return true;
                }
                if (candidate.type() == RagSourceType.DIRECTORY && otherPath.startsWith(candidatePath)) {
                    return true;
                }
                return other.type() == RagSourceType.DIRECTORY && candidatePath.startsWith(otherPath);
            })
            .findFirst();
    }

    private void scanDirectory(ScanState state, CancellationToken token) throws IOException {
        Path root = state.source.path();
        Files.walkFileTree(root, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                token.throwIfCancelled();
                if (!directory.equals(root) && !state.source.recursive()) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(root) && shouldExcludeDirectory(directory)) {
                    state.problem(directory, RagScanPreview.ProblemCode.EXCLUDED,
                        RagScanPreview.Severity.INFO, "Directory excluded by the safe default filter");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(root)
                    && !state.gitIgnoreFilter.acceptDirectory(root.relativize(directory))) {
                    state.problem(directory, RagScanPreview.ProblemCode.EXCLUDED,
                        RagScanPreview.Severity.INFO, "Directory excluded by .gitignore");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                token.throwIfCancelled();
                Path relative = root.relativize(file);
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    state.problem(file, RagScanPreview.ProblemCode.SYMBOLIC_LINK,
                        RagScanPreview.Severity.INFO, "Symbolic link skipped");
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (isHiddenFile(file)) {
                    state.problem(file, RagScanPreview.ProblemCode.EXCLUDED,
                        RagScanPreview.Severity.INFO, "Hidden file excluded by the safe default filter");
                    return FileVisitResult.CONTINUE;
                }
                if (!state.pathFilter.accept(relative)) {
                    state.problem(file, RagScanPreview.ProblemCode.EXCLUDED,
                        RagScanPreview.Severity.INFO, "File excluded by include/exclude filters");
                    return FileVisitResult.CONTINUE;
                }
                if (!state.gitIgnoreFilter.accept(relative)) {
                    state.problem(file, RagScanPreview.ProblemCode.EXCLUDED,
                        RagScanPreview.Severity.INFO, "File excluded by .gitignore");
                    return FileVisitResult.CONTINUE;
                }
                Object key = attributes.fileKey();
                if (key != null && !state.fileKeys.add(key)) {
                    state.problem(file, RagScanPreview.ProblemCode.DUPLICATE_OR_OVERLAP,
                        RagScanPreview.Severity.INFO, "Duplicate filesystem entry skipped");
                    return FileVisitResult.CONTINUE;
                }
                scanFile(state, file, relative, token, attributes);
                return state.limitExceeded ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                state.problem(file, RagScanPreview.ProblemCode.NOT_READABLE,
                    RagScanPreview.Severity.WARNING, safeMessage(error));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                if (error != null) {
                    state.problem(directory, RagScanPreview.ProblemCode.NOT_READABLE,
                        RagScanPreview.Severity.WARNING, safeMessage(error));
                }
                return state.limitExceeded ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
    }

    private void scanFile(
        ScanState state,
        Path file,
        Path relative,
        CancellationToken token,
        BasicFileAttributes suppliedAttributes
    ) {
        token.throwIfCancelled();
        state.visitedFiles++;
        if (state.visitedFiles > maxFiles) {
            state.limit(file, "Source contains more than " + maxFiles + " files");
            return;
        }
        Optional<RagSourceFormatRegistry.Format> maybeFormat = formats.formatFor(file);
        if (maybeFormat.isEmpty()) {
            state.problem(file, RagScanPreview.ProblemCode.UNSUPPORTED_FORMAT,
                RagScanPreview.Severity.INFO, "Unsupported file format");
            return;
        }
        RagSourceFormatRegistry.Format format = maybeFormat.get();
        try {
            BasicFileAttributes before = suppliedAttributes != null ? suppliedAttributes
                : Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!Files.isReadable(file)) {
                state.problem(file, RagScanPreview.ProblemCode.NOT_READABLE,
                    RagScanPreview.Severity.WARNING, "File is not readable");
                return;
            }
            long effectiveMaxBytes = Math.min(format.maxBytes(), state.source.maxFileBytes());
            if (before.size() > effectiveMaxBytes) {
                state.problem(file, RagScanPreview.ProblemCode.TOO_LARGE,
                    RagScanPreview.Severity.WARNING, "File exceeds the " + effectiveMaxBytes + " byte limit");
                return;
            }
            if (state.acceptedBytes + before.size() > maxSourceBytes) {
                state.limit(file, "Source exceeds the " + maxSourceBytes + " byte limit");
                return;
            }
            String beforeHash = sha256(file);
            BasicFileAttributes afterInitialHash = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != afterInitialHash.size()
                || !before.lastModifiedTime().equals(afterInitialHash.lastModifiedTime())) {
                state.problem(file, RagScanPreview.ProblemCode.EXTRACTION_FAILED,
                    RagScanPreview.Severity.WARNING,
                    "File changed while its content hash was being calculated; retry the scan");
                return;
            }
            RagTextExtractor.ExtractedText extracted = extractor.extract(file, format);
            token.throwIfCancelled();
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                state.problem(file, RagScanPreview.ProblemCode.EXTRACTION_FAILED,
                    RagScanPreview.Severity.WARNING, "File changed while it was being read; retry the scan");
                return;
            }
            if (state.extractedChars + extracted.text().length() > maxExtractedChars) {
                state.limit(file, "Extracted text exceeds the " + maxExtractedChars + " character limit");
                return;
            }
            String hash = sha256(file);
            BasicFileAttributes afterHash = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != afterHash.size()
                || !before.lastModifiedTime().equals(afterHash.lastModifiedTime())) {
                state.problem(file, RagScanPreview.ProblemCode.EXTRACTION_FAILED,
                    RagScanPreview.Severity.WARNING,
                    "File changed while its content hash was being calculated; retry the scan");
                return;
            }
            if (!beforeHash.equals(hash)) {
                state.problem(file, RagScanPreview.ProblemCode.EXTRACTION_FAILED,
                    RagScanPreview.Severity.WARNING,
                    "File content changed while it was being extracted; retry the scan");
                return;
            }
            state.documents.add(new RagDocument(
                state.source.id(), file, normalizeRelative(relative), format.identifier(), before.size(),
                Instant.ofEpochMilli(before.lastModifiedTime().toMillis()), hash, extracted.text()));
            state.acceptedBytes += before.size();
            state.extractedChars += extracted.text().length();
        } catch (RagTextExtractor.ExtractionException error) {
            state.problem(file, error.code(), RagScanPreview.Severity.WARNING, error.getMessage());
        } catch (IOException | SecurityException error) {
            state.problem(file, RagScanPreview.ProblemCode.EXTRACTION_FAILED,
                RagScanPreview.Severity.WARNING, safeMessage(error));
        }
    }

    private static boolean shouldExcludeDirectory(Path directory) {
        Path fileName = directory.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        if (name.startsWith(".")) {
            return true;
        }
        return STANDARD_EXCLUDED_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean isHiddenFile(Path file) {
        Path name = file.getFileName();
        if (name != null && name.toString().startsWith(".")) {
            return true;
        }
        try {
            return Files.isHidden(file);
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    private static String normalizeRelative(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static Path realOrAbsolute(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException error) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName() : error.getMessage();
    }

    static List<String> loadGitIgnorePatterns(RagSource source) {
        if (!source.respectGitIgnore() || source.type() != RagSourceType.DIRECTORY) {
            return List.of();
        }
        Path ignore = source.path().resolve(".gitignore");
        try {
            if (!Files.isRegularFile(ignore, LinkOption.NOFOLLOW_LINKS) || Files.size(ignore) > 1024 * 1024) {
                return List.of();
            }
            List<String> patterns = new ArrayList<>();
            for (String raw : Files.readAllLines(ignore, java.nio.charset.StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                if (line.startsWith("/")) {
                    line = line.substring(1);
                } else if (!line.contains("/")) {
                    line = "**/" + line;
                }
                if (line.endsWith("/")) {
                    line += "**";
                }
                patterns.add(line);
            }
            return List.copyOf(patterns);
        } catch (IOException | SecurityException error) {
            return List.of();
        }
    }

    private static final class ScanState {
        private final RagSource source;
        private final PathFilter pathFilter;
        private final PathFilter gitIgnoreFilter;
        private final List<RagDocument> documents = new ArrayList<>();
        private final List<RagScanPreview.Problem> problems = new ArrayList<>();
        private final Set<Object> fileKeys = new HashSet<>();
        private long acceptedBytes;
        private long extractedChars;
        private int visitedFiles;
        private boolean limitExceeded;

        private ScanState(RagSource source) {
            this.source = source;
            this.pathFilter = new PathFilter(source.includePatterns(), source.excludePatterns());
            this.gitIgnoreFilter = new PathFilter(List.of(), loadGitIgnorePatterns(source));
        }

        private void problem(Path path, RagScanPreview.ProblemCode code,
                             RagScanPreview.Severity severity, String message) {
            problems.add(new RagScanPreview.Problem(path, code, severity, message));
        }

        private void limit(Path path, String message) {
            limitExceeded = true;
            problem(path, RagScanPreview.ProblemCode.LIMIT_EXCEEDED, RagScanPreview.Severity.ERROR, message);
        }

        private RagScanPreview result() {
            return new RagScanPreview(source, documents, problems, acceptedBytes, visitedFiles);
        }
    }

    /** Slash-normalized glob matcher; * stays within a segment while ** crosses directories. */
    static final class PathFilter {
        private final List<Pattern> includes;
        private final List<Pattern> excludes;

        PathFilter(List<String> includes, List<String> excludes) {
            this.includes = compile(includes);
            this.excludes = compile(excludes);
        }

        boolean accept(Path relative) {
            String value = normalizeRelative(relative);
            boolean included = includes.isEmpty() || includes.stream().anyMatch(pattern -> pattern.matcher(value).matches());
            return included && excludes.stream().noneMatch(pattern -> pattern.matcher(value).matches());
        }

        boolean acceptDirectory(Path relative) {
            String value = normalizeRelative(relative);
            return excludes.stream().noneMatch(pattern -> pattern.matcher(value).matches()
                || pattern.matcher(value + "/placeholder").matches());
        }

        private static List<Pattern> compile(List<String> globs) {
            if (globs == null) {
                return List.of();
            }
            return globs.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).map(PathFilter::globToRegex).map(Pattern::compile).toList();
        }

        private static String globToRegex(String glob) {
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char value = glob.charAt(i);
                if (value == '*') {
                    boolean recursive = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                    if (recursive) {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                            regex.append("(?:.*/)?");
                        } else {
                            regex.append(".*");
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                } else if (value == '?') {
                    regex.append("[^/]");
                } else if (".()[]{}+$^|".indexOf(value) >= 0) {
                    regex.append('\\').append(value);
                } else if (value == '\\') {
                    regex.append('/');
                } else {
                    regex.append(value);
                }
            }
            return regex.append('$').toString();
        }
    }
}
