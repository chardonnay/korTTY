package de.kortty.rag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Single allowlist used by file choosers, recursive scans and text extraction. */
public final class RagSourceFormatRegistry {
    public static final long DEFAULT_MAX_FILE_BYTES = 50L * 1024 * 1024;
    public static final long MAX_CONFIGURABLE_FILE_BYTES = 1024L * 1024 * 1024;
    /** Backward-compatible name for persisted/default text-source limits. */
    public static final long MAX_TEXT_BYTES = DEFAULT_MAX_FILE_BYTES;
    /** Backward-compatible name for persisted/default PDF-source limits. */
    public static final long MAX_PDF_BYTES = DEFAULT_MAX_FILE_BYTES;

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
        "txt", "md", "markdown", "adoc", "asciidoc", "rst", "pdf");
    private static final Set<String> DATA_EXTENSIONS = Set.of(
        "json", "jsonl", "yaml", "yml", "xml", "csv", "tsv", "properties", "ini", "cfg", "conf", "toml");
    private static final Set<String> CODE_EXTENSIONS = Set.of(
        "sh", "bash", "zsh", "fish", "bat", "cmd", "awk",
        "ps1", "psm1", "psd1",
        "py", "pyw", "pyi",
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts",
        "java", "kt", "kts", "groovy", "gradle", "gvy", "gy", "gsh",
        "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "sql",
        "c", "h", "cc", "cpp", "cxx", "hpp", "hxx", "hh", "inl", "cs",
        "go", "rs", "rb", "pl", "php", "swift", "scala", "sc", "lua");
    private static final Set<String> EXACT_FILE_NAMES = Set.of(
        "dockerfile", "makefile", "readme", "license");

    private final Map<String, Format> byExtension;
    private final Map<String, Format> byExactName;

    public RagSourceFormatRegistry() {
        Map<String, Format> extensions = new LinkedHashMap<>();
        for (String extension : DOCUMENT_EXTENSIONS) {
            boolean pdf = "pdf".equals(extension);
            extensions.put(extension, new Format(pdf ? "pdf" : "document", "." + extension,
                MAX_CONFIGURABLE_FILE_BYTES, pdf));
        }
        for (String extension : DATA_EXTENSIONS) {
            extensions.put(extension, new Format("data", "." + extension, MAX_CONFIGURABLE_FILE_BYTES, false));
        }
        for (String extension : CODE_EXTENSIONS) {
            extensions.put(extension, new Format("code", "." + extension, MAX_CONFIGURABLE_FILE_BYTES, false));
        }
        this.byExtension = Map.copyOf(extensions);

        Map<String, Format> exact = new LinkedHashMap<>();
        for (String fileName : EXACT_FILE_NAMES) {
            exact.put(fileName, new Format("code", fileName, MAX_CONFIGURABLE_FILE_BYTES, false));
        }
        this.byExactName = Map.copyOf(exact);
    }

    public Optional<Format> formatFor(Path path) {
        if (path == null || path.getFileName() == null) {
            return Optional.empty();
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        Format exact = byExactName.get(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byExtension.get(name.substring(dot + 1)));
    }

    public boolean isAllowed(Path path) {
        return formatFor(path).isPresent();
    }

    public List<String> allowedSuffixes() {
        List<String> result = new ArrayList<>();
        byExtension.keySet().stream().sorted().map(extension -> "." + extension).forEach(result::add);
        byExactName.keySet().stream().sorted().forEach(result::add);
        return List.copyOf(result);
    }

    public record Format(String category, String identifier, long maxBytes, boolean pdf) { }
}
