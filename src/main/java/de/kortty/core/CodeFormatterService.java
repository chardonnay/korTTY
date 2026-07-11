package de.kortty.core;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Shared code formatter service for snippet and file editors.
 */
public final class CodeFormatterService {

    public static final String GOOGLE_JAVA_FORMAT_VERSION = "1.35.0";
    public static final String SHFMT_VERSION = "3.13.1";
    public static final String PRETTIER_VERSION = "3.6.2";
    public static final String SQL_FORMATTER_VERSION = "15.7.3";
    public static final String PERL_TIDY_VERSION = "20260204";

    private static final int JSON_INDENT = 2;
    private static final int DEFAULT_INDENT = 4;
    private static final int FORMATTER_TIMEOUT_SECONDS = 15;
    private static final int MIN_LINE_WIDTH = 20;
    private static final int MAX_LINE_WIDTH = 240;
    private static final String PATH_SEPARATOR_REGEX = java.util.regex.Pattern.quote(File.pathSeparator);

    public enum ProviderType {
        BUILT_IN,
        BUNDLED,
        EXTERNAL_FALLBACK,
        UNAVAILABLE
    }

    public enum ExecutionMode {
        STDIN,
        FILE_APPEND
    }

    public record FormatterInfo(
        String language,
        String formatterId,
        String displayName,
        ProviderType providerType,
        ExecutionMode executionMode,
        List<String> commandLine,
        String commandName,
        String installHint,
        String fileExtension,
        boolean available,
        String unavailableReason) {

        public FormatterInfo {
            language = normalizeLanguage(language);
            commandLine = commandLine == null ? List.of() : List.copyOf(commandLine);
        }

        public boolean isSupported() {
            return providerType != ProviderType.UNAVAILABLE || formatterId != null;
        }

        public boolean isBundledOrBuiltIn() {
            return providerType == ProviderType.BUILT_IN || providerType == ProviderType.BUNDLED;
        }
    }

    public static final class FormatterException extends Exception {
        public FormatterException(String message) {
            super(message);
        }

        public FormatterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static boolean isSupported(String language) {
        return getFormatterInfo(language) != null;
    }

    public static boolean isFormatterAvailable(FormatterInfo info) {
        return info != null && info.available();
    }

    public static boolean supportsLineWidth(String language) {
        return supportsLineWidth(getFormatterInfo(language));
    }

    public static FormatterInfo getFormatterInfo(String language) {
        String lang = normalizeLanguage(language);
        return switch (lang) {
            case "json" -> builtIn(lang, "json", ".json");
            case "xml" -> builtIn(lang, "xml", ".xml");
            case "yaml", "yml" -> builtIn(lang, "yaml", ".yml");
            case "toml" -> builtIn(lang, "toml", ".toml");
            case "ini", "properties" -> builtIn(lang, "ini", ".properties");
            case "groovy" -> builtIn(lang, "groovy", ".groovy");
            case "bash", "shell", "sh", "zsh" -> shellFormatter(lang);
            case "html" -> prettierFormatter(lang, "html", ".html");
            case "css" -> prettierFormatter(lang, "css", ".css");
            case "javascript", "typescript" -> prettierFormatter(lang, "typescript", ".js");
            case "java" -> javaFormatter(lang);
            case "perl", "pl" -> perlFormatter(lang);
            case "python" -> externalOnly(lang, "black", List.of("black", "-q", "-"), ExecutionMode.STDIN,
                "Install black for developer fallback: pip install black  /  brew install black", ".py");
            case "ruby" -> externalOnly(lang, "rubocop", List.of("rubocop", "-a", "--stderr", "--stdin"), ExecutionMode.FILE_APPEND,
                "Install RuboCop for developer fallback: gem install rubocop", ".rb");
            case "go" -> externalOnly(lang, "gofmt", List.of("gofmt"), ExecutionMode.STDIN,
                "Install Go for developer fallback; gofmt is included with Go.", ".go");
            case "rust" -> externalOnly(lang, "rustfmt", List.of("rustfmt"), ExecutionMode.STDIN,
                "Install rustfmt for developer fallback: rustup component add rustfmt", ".rs");
            case "sql" -> sqlFormatter(lang);
            case "terraform" -> externalOnly(lang, "terraform", List.of("terraform", "fmt"), ExecutionMode.FILE_APPEND,
                "Install Terraform for developer fallback: https://developer.hashicorp.com/terraform/install", ".tf");
            default -> null;
        };
    }

    public static String format(String text, String language) {
        return format(text, language, null);
    }

    public static String format(String text, String language, Integer maxLineLength) {
        try {
            return formatOrThrow(text, language, maxLineLength);
        } catch (FormatterException e) {
            return null;
        }
    }

    public static String formatOrThrow(String text, String language) throws FormatterException {
        return formatOrThrow(text, language, null);
    }

    public static String formatOrThrow(String text, String language, Integer maxLineLength) throws FormatterException {
        if (text == null || text.isBlank()) {
            return null;
        }
        FormatterInfo info = getFormatterInfo(language);
        if (info == null) {
            return null;
        }
        Integer safeLineWidth = normalizeLineWidth(maxLineLength);
        if (safeLineWidth != null && !supportsLineWidth(info)) {
            throw new FormatterException("Formatter does not support a configurable line width for " + info.language());
        }
        if (!info.available()) {
            throw new FormatterException(info.unavailableReason() != null
                ? info.unavailableReason()
                : "Formatter unavailable for " + info.language());
        }
        if (info.providerType() == ProviderType.BUILT_IN) {
            return formatBuiltIn(text, info.language());
        }
        if ("google-java-format".equals(info.formatterId()) && info.providerType() == ProviderType.BUNDLED) {
            return formatJava(text);
        }
        if (isBundledWebFormatter(info)) {
            return WebFormatterBackend.format(
                info.formatterId(),
                webParser(info.language()),
                text,
                safeLineWidth,
                FORMATTER_TIMEOUT_SECONDS);
        }
        List<String> commandLine = commandLineWithLineWidth(info, safeLineWidth);
        if (info.executionMode() == ExecutionMode.STDIN) {
            return runStdinFormatter(commandLine, text);
        }
        if (info.executionMode() == ExecutionMode.FILE_APPEND) {
            return runFileFormatter(commandLine, text, info.fileExtension());
        }
        throw new FormatterException("No formatter execution configured for " + info.displayName());
    }

    private static boolean supportsLineWidth(FormatterInfo info) {
        if (info == null || info.formatterId() == null) {
            return false;
        }
        return switch (info.formatterId()) {
            case "prettier", "black", "perltidy" -> true;
            default -> false;
        };
    }

    private static Integer normalizeLineWidth(Integer maxLineLength) throws FormatterException {
        if (maxLineLength == null) {
            return null;
        }
        if (maxLineLength < MIN_LINE_WIDTH || maxLineLength > MAX_LINE_WIDTH) {
            throw new FormatterException(
                "Line width must be between " + MIN_LINE_WIDTH + " and " + MAX_LINE_WIDTH + " characters");
        }
        return maxLineLength;
    }

    private static List<String> commandLineWithLineWidth(FormatterInfo info, Integer maxLineLength) {
        List<String> commandLine = new ArrayList<>(info.commandLine());
        if (maxLineLength == null) {
            return commandLine;
        }
        switch (info.formatterId()) {
            case "prettier" -> {
                commandLine.add("--print-width");
                commandLine.add(Integer.toString(maxLineLength));
            }
            case "black" -> {
                int insertAt = !commandLine.isEmpty() && "-".equals(commandLine.get(commandLine.size() - 1))
                    ? commandLine.size() - 1
                    : commandLine.size();
                commandLine.add(insertAt, "--line-length");
                commandLine.add(insertAt + 1, Integer.toString(maxLineLength));
            }
            case "perltidy" -> commandLine.add("-l=" + maxLineLength);
            default -> {
                // The caller guards unsupported formatter ids before this point.
            }
        }
        return commandLine;
    }

    private static FormatterInfo builtIn(String language, String formatterId, String fileExtension) {
        return new FormatterInfo(
            language,
            formatterId,
            "integrated",
            ProviderType.BUILT_IN,
            null,
            List.of(),
            null,
            null,
            fileExtension,
            true,
            null);
    }

    private static FormatterInfo javaFormatter(String language) {
        if (isGoogleJavaFormatAvailable()) {
            return new FormatterInfo(
                language,
                "google-java-format",
                "google-java-format " + GOOGLE_JAVA_FORMAT_VERSION + " (bundled)",
                ProviderType.BUNDLED,
                null,
                List.of(),
                "google-java-format",
                null,
                ".java",
                true,
                null);
        }
        FormatterInfo external = externalCandidate(language, "google-java-format",
            List.of("google-java-format", "-"), ExecutionMode.STDIN,
            "Install google-java-format for developer fallback.", ".java");
        if (external.available()) {
            return external;
        }
        return unavailable(language, "google-java-format", ".java",
            "Bundled google-java-format is unavailable and optional external fallback was not found in PATH.");
    }

    private static FormatterInfo shellFormatter(String language) {
        Optional<Path> bundled = findBundledExecutable("shfmt", executableName("shfmt"));
        if (bundled.isPresent()) {
            return bundledProcess(language, "shfmt", "shfmt " + SHFMT_VERSION + " (bundled)",
                List.of(bundled.get().toString()), ExecutionMode.STDIN, ".sh");
        }
        FormatterInfo external = externalCandidate(language, "shfmt", List.of("shfmt"), ExecutionMode.STDIN,
            "Install shfmt for developer fallback: brew install shfmt  /  go install mvdan.cc/sh/v3/cmd/shfmt@latest", ".sh");
        if (external.available()) {
            return external;
        }
        return unavailable(language, "shfmt", ".sh",
            "Bundled shfmt is missing from this KorTTY build and optional external fallback was not found in PATH.");
    }

    private static FormatterInfo prettierFormatter(String language, String parser, String fileExtension) {
        if (WebFormatterBackend.isBundledAvailable()) {
            return bundledWebFormatter(
                language,
                "prettier",
                "Prettier " + PRETTIER_VERSION + " (bundled)",
                fileExtension);
        }
        FormatterInfo external = externalCandidate(language, "prettier", List.of("prettier", "--parser", parser),
            ExecutionMode.STDIN, "Install Prettier for developer fallback: npm install -g prettier", fileExtension);
        if (external.available()) {
            return external;
        }
        return unavailable(language, "prettier", fileExtension,
            "Bundled Prettier runtime is missing from this KorTTY build and optional external fallback was not found in PATH.");
    }

    private static FormatterInfo perlFormatter(String language) {
        Optional<Path> perl = findCommandOnPath("perl");
        Optional<Path> perltidyBin = findBundledFile("perltidy", Path.of("bin", "perltidy"));
        Optional<Path> perltidyLib = findBundledFile("perltidy", Path.of("lib"));
        if (perl.isPresent() && perltidyBin.isPresent() && perltidyLib.isPresent()) {
            return bundledProcess(language, "perltidy", "Perl::Tidy " + PERL_TIDY_VERSION + " (bundled)",
                List.of(perl.get().toString(), "-I", perltidyLib.get().toString(), perltidyBin.get().toString(), "-st"),
                ExecutionMode.STDIN, ".pl");
        }
        FormatterInfo external = externalCandidate(language, "perltidy", List.of("perltidy", "-st"), ExecutionMode.STDIN,
            "Install perltidy for developer fallback: brew install perltidy  /  cpan Perl::Tidy", ".pl");
        if (external.available()) {
            return external;
        }
        String reason = "Bundled Perl::Tidy is missing from this KorTTY build";
        if (perltidyBin.isPresent() && perltidyLib.isPresent() && perl.isEmpty()) {
            reason = "Bundled Perl::Tidy is available, but no local perl runtime was found in PATH";
        }
        return unavailable(language, "perltidy", ".pl", reason + " and optional external fallback was not found in PATH.");
    }

    private static FormatterInfo sqlFormatter(String language) {
        if (WebFormatterBackend.isBundledAvailable()) {
            return bundledWebFormatter(
                language,
                "sql-formatter",
                "sql-formatter " + SQL_FORMATTER_VERSION + " (bundled)",
                ".sql");
        }
        FormatterInfo external = externalCandidate(language, "sql-formatter", List.of("sql-formatter"), ExecutionMode.STDIN,
            "Install sql-formatter for developer fallback: npm install -g sql-formatter", ".sql");
        if (external.available()) {
            return external;
        }
        return unavailable(language, "sql-formatter", ".sql",
            "Bundled sql-formatter runtime is missing from this KorTTY build and optional external fallback was not found in PATH.");
    }

    private static FormatterInfo externalOnly(
        String language,
        String commandName,
        List<String> commandLine,
        ExecutionMode mode,
        String installHint,
        String fileExtension) {

        FormatterInfo external = externalCandidate(language, commandName, commandLine, mode, installHint, fileExtension);
        if (external.available()) {
            return external;
        }
        return unavailable(language, commandName, fileExtension,
            "No bundled formatter is configured for " + language + " yet and optional external fallback '" + commandName + "' was not found in PATH.");
    }

    private static FormatterInfo externalCandidate(
        String language,
        String commandName,
        List<String> commandLine,
        ExecutionMode mode,
        String installHint,
        String fileExtension) {

        Optional<Path> command = findCommandOnPath(commandName);
        if (command.isEmpty()) {
            return unavailable(language, commandName, fileExtension,
                "Optional external formatter was not found in PATH: " + commandName);
        }
        List<String> resolved = new ArrayList<>(commandLine);
        resolved.set(0, command.get().toString());
        return new FormatterInfo(
            language,
            commandName,
            commandName + " (external fallback)",
            ProviderType.EXTERNAL_FALLBACK,
            mode,
            resolved,
            commandName,
            installHint,
            fileExtension,
            true,
            null);
    }

    private static FormatterInfo bundledProcess(
        String language,
        String formatterId,
        String displayName,
        List<String> commandLine,
        ExecutionMode mode,
        String fileExtension) {

        return new FormatterInfo(
            language,
            formatterId,
            displayName,
            ProviderType.BUNDLED,
            mode,
            commandLine,
            formatterId,
            null,
            fileExtension,
            true,
            null);
    }

    private static FormatterInfo bundledWebFormatter(
        String language,
        String formatterId,
        String displayName,
        String fileExtension) {

        return new FormatterInfo(
            language,
            formatterId,
            displayName,
            ProviderType.BUNDLED,
            null,
            List.of(),
            formatterId,
            null,
            fileExtension,
            true,
            null);
    }

    private static boolean isBundledWebFormatter(FormatterInfo info) {
        if (info.providerType() != ProviderType.BUNDLED) {
            return false;
        }
        return "prettier".equals(info.formatterId()) || "sql-formatter".equals(info.formatterId());
    }

    private static String webParser(String language) {
        return switch (normalizeLanguage(language)) {
            case "html" -> "html";
            case "css" -> "css";
            case "javascript", "typescript" -> "typescript";
            default -> null;
        };
    }

    private static FormatterInfo unavailable(String language, String formatterId, String fileExtension, String reason) {
        return new FormatterInfo(
            language,
            formatterId,
            formatterId,
            ProviderType.UNAVAILABLE,
            null,
            List.of(),
            formatterId,
            null,
            fileExtension,
            false,
            reason);
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "plain";
        }
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String formatBuiltIn(String text, String language) {
        String lang = normalizeLanguage(language);
        return switch (lang) {
            case "json" -> formatJson(text);
            case "xml" -> formatXml(text, lang);
            case "yaml", "yml" -> formatYaml(text);
            case "toml" -> formatToml(text);
            case "ini", "properties" -> formatIni(text);
            case "groovy" -> normalizeIndent(text, DEFAULT_INDENT);
            default -> null;
        };
    }

    private static String formatJson(String text) {
        try {
            return prettyPrintJson(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String prettyPrintJson(String input) {
        StringBuilder out = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (inString) {
                if (escaped) {
                    out.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    out.append(c);
                    escaped = true;
                } else if (c == stringChar) {
                    out.append(c);
                    inString = false;
                } else {
                    out.append(c);
                }
                i++;
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    stringChar = c;
                    out.append(c);
                    i++;
                }
                case '{', '[' -> {
                    out.append(c);
                    indent += JSON_INDENT;
                    i++;
                    if (i < input.length() && !isSpace(input.charAt(i))) {
                        out.append('\n');
                        out.append(" ".repeat(indent));
                    }
                }
                case '}', ']' -> {
                    indent -= JSON_INDENT;
                    if (indent < 0) {
                        indent = 0;
                    }
                    if (out.length() > 0
                        && out.charAt(out.length() - 1) != ','
                        && out.charAt(out.length() - 1) != '{'
                        && out.charAt(out.length() - 1) != '[') {
                        out.append('\n');
                        out.append(" ".repeat(indent));
                    }
                    out.append(c);
                    i++;
                }
                case ',' -> {
                    out.append(c);
                    out.append('\n');
                    out.append(" ".repeat(indent));
                    i++;
                }
                case ':' -> {
                    out.append(c);
                    if (i + 1 < input.length() && input.charAt(i + 1) != ' ') {
                        out.append(' ');
                    }
                    i++;
                }
                default -> {
                    if (!Character.isWhitespace(c)
                        || (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1)))) {
                        out.append(c);
                    }
                    i++;
                }
            }
        }
        return out.toString();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static String formatXml(String text, String language) {
        try {
            String trimmed = text.trim();
            boolean wrapHtml = "html".equals(language)
                && !trimmed.toLowerCase(Locale.ROOT).startsWith("<!doctype")
                && !trimmed.toLowerCase(Locale.ROOT).startsWith("<?xml")
                && !trimmed.toLowerCase(Locale.ROOT).startsWith("<html");
            if (wrapHtml) {
                trimmed = "<_root>" + trimmed + "</_root>";
            }
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new StreamSource(new StringReader(trimmed)), new StreamResult(writer));
            String result = writer.toString();
            if (wrapHtml && result.contains("<_root>")) {
                result = result.replace("<_root>", "").replace("</_root>", "").trim();
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatYaml(String text) {
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        int prevIndent = -1;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                continue;
            }
            int spaces = 0;
            while (spaces < line.length() && (line.charAt(spaces) == ' ' || line.charAt(spaces) == '\t')) {
                spaces++;
            }
            int indent = spaces;
            if (indent % 2 != 0) {
                indent = (indent / 2 + 1) * 2;
            }
            indent = Math.min(indent, prevIndent + 2);
            if (!trimmed.startsWith("-") && !trimmed.startsWith("#") && prevIndent >= 0 && indent > prevIndent + 2) {
                indent = prevIndent + 2;
            }
            prevIndent = indent;
            out.append(" ".repeat(indent)).append(trimmed).append('\n');
        }
        return out.toString().trim();
    }

    private static String formatToml(String text) {
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!first && out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (trimmed.startsWith("[")) {
                if (!first) {
                    out.append('\n');
                }
                out.append(trimmed).append('\n');
                first = false;
                continue;
            }
            if (trimmed.startsWith("#")) {
                out.append(trimmed).append('\n');
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                out.append(key).append(" = ").append(value).append('\n');
            } else {
                out.append(trimmed).append('\n');
            }
            first = false;
        }
        return out.toString().trim();
    }

    private static String formatIni(String text) {
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        boolean needNewline = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                needNewline = true;
                continue;
            }
            if (needNewline && out.length() > 0) {
                out.append('\n');
            }
            needNewline = false;
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                out.append(trimmed).append('\n');
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith(";")) {
                out.append(trimmed).append('\n');
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                out.append(key).append(" = ").append(value).append('\n');
            } else {
                out.append(trimmed).append('\n');
            }
        }
        return out.toString().trim();
    }

    private static String normalizeIndent(String text, int indentSize) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int col = 0;
            while (col < line.length() && (line.charAt(col) == ' ' || line.charAt(col) == '\t')) {
                col++;
            }
            int indent = 0;
            for (int k = 0; k < col; k++) {
                indent += line.charAt(k) == '\t' ? indentSize : 1;
            }
            indent = (indent / indentSize) * indentSize;
            out.append(" ".repeat(indent)).append(line.substring(col));
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String formatJava(String text) throws FormatterException {
        try {
            return new com.google.googlejavaformat.java.Formatter().formatSource(text);
        } catch (com.google.googlejavaformat.java.FormatterException e) {
            throw new FormatterException("google-java-format failed: " + e.getMessage(), e);
        } catch (IllegalAccessError e) {
            throw new FormatterException("google-java-format needs the configured jdk.compiler module exports.", e);
        }
    }

    private static boolean isGoogleJavaFormatAvailable() {
        try {
            Class.forName("com.google.googlejavaformat.java.Formatter");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String runStdinFormatter(List<String> args, String input) throws FormatterException {
        Process process = null;
        try {
            process = new ProcessBuilder(args).start();
            CompletableFuture<byte[]> stdoutFuture = readAllBytesAsync(process.getInputStream());
            CompletableFuture<byte[]> stderrFuture = readAllBytesAsync(process.getErrorStream());
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            waitForFormatter(process);
            String stdout = readFuture(stdoutFuture);
            String stderr = readFuture(stderrFuture);
            ensureSuccessfulExit(process.exitValue(), stderr);
            return stdout;
        } catch (IOException e) {
            throw new FormatterException("Could not start formatter: " + firstArg(args), e);
        } finally {
            destroyIfAlive(process);
        }
    }

    private static String runFileFormatter(List<String> args, String input, String extension) throws FormatterException {
        Path tempFile = null;
        Process process = null;
        try {
            tempFile = Files.createTempFile("kortty_format_", extension != null ? extension : ".txt");
            Files.writeString(tempFile, input, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(args);
            command.add(tempFile.toString());

            process = new ProcessBuilder(command).start();
            CompletableFuture<byte[]> stdoutFuture = readAllBytesAsync(process.getInputStream());
            CompletableFuture<byte[]> stderrFuture = readAllBytesAsync(process.getErrorStream());
            waitForFormatter(process);
            readFuture(stdoutFuture);
            String stderr = readFuture(stderrFuture);
            ensureSuccessfulExit(process.exitValue(), stderr);
            return Files.readString(tempFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FormatterException("Could not run file formatter: " + firstArg(args), e);
        } finally {
            destroyIfAlive(process);
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Temporary file cleanup is best effort.
                }
            }
        }
    }

    private static CompletableFuture<byte[]> readAllBytesAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream input = stream) {
                return input.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void waitForFormatter(Process process) throws FormatterException {
        try {
            boolean finished = process.waitFor(FORMATTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new FormatterException("Formatter timed out after " + FORMATTER_TIMEOUT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FormatterException("Formatter was interrupted", e);
        }
    }

    private static String readFuture(CompletableFuture<byte[]> future) throws FormatterException {
        try {
            return new String(future.get(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FormatterException("Formatter output read was interrupted", e);
        } catch (ExecutionException e) {
            throw new FormatterException("Could not read formatter output", e);
        }
    }

    private static void ensureSuccessfulExit(int exitCode, String stderr) throws FormatterException {
        if (exitCode != 0) {
            String message = stderr != null && !stderr.isBlank() ? stderr.trim() : "exit code " + exitCode;
            throw new FormatterException(message);
        }
    }

    private static void destroyIfAlive(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static String firstArg(List<String> args) {
        return args == null || args.isEmpty() ? "formatter" : args.get(0);
    }

    private static Optional<Path> findBundledExecutable(String formatterDir, String executable) {
        for (Path root : formatterRoots()) {
            List<Path> candidates = List.of(
                root.resolve("bin").resolve(executable),
                root.resolve(formatterDir).resolve(executable),
                root.resolve(formatterDir).resolve("bin").resolve(executable));
            for (Path candidate : candidates) {
                if (Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findBundledFile(String formatterDir, Path relativePath) {
        for (Path root : formatterRoots()) {
            Path candidate = root.resolve(formatterDir).resolve(relativePath);
            if (Files.exists(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static List<Path> formatterRoots() {
        List<Path> roots = new ArrayList<>();
        addConfiguredRoot(roots, System.getProperty("kortty.formatters.dir"));
        addConfiguredRoot(roots, System.getenv("KORTTY_FORMATTERS_DIR"));
        addCodeSourceRoots(roots);
        addConfiguredRoot(roots, Path.of(System.getProperty("user.dir", "."), "formatters").toString());
        addConfiguredRoot(roots, Path.of(System.getProperty("user.dir", "."), "build", "jpackage-input", "libs", "formatters").toString());
        return roots.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static void addConfiguredRoot(List<Path> roots, String value) {
        if (value != null && !value.isBlank()) {
            roots.add(Path.of(value).toAbsolutePath().normalize());
        }
    }

    private static void addCodeSourceRoots(List<Path> roots) {
        try {
            CodeSource source = CodeFormatterService.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return;
            }
            Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            Path base = Files.isRegularFile(location) ? location.getParent() : location;
            if (base != null) {
                roots.add(base.resolve("formatters"));
                Path parent = base.getParent();
                if (parent != null) {
                    roots.add(parent.resolve("formatters"));
                }
            }
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // Formatter roots are optional. Other configured roots may still work.
        }
    }

    private static Optional<Path> findCommandOnPath(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        if (command.contains(File.separator)) {
            Path path = Path.of(command);
            return Files.isExecutable(path) ? Optional.of(path) : Optional.empty();
        }

        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String[] executableSuffixes = executableSuffixes();
        for (String dir : path.split(PATH_SEPARATOR_REGEX)) {
            if (dir.isBlank()) {
                continue;
            }
            Path base = Path.of(dir, command);
            for (String suffix : executableSuffixes) {
                Path candidate = Path.of(base.toString() + suffix);
                if (Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static String[] executableSuffixes() {
        String pathExt = System.getenv("PATHEXT");
        if (pathExt == null || pathExt.isBlank()) {
            return new String[]{""};
        }
        String[] parts = pathExt.split(PATH_SEPARATOR_REGEX);
        String[] suffixes = new String[parts.length + 1];
        suffixes[0] = "";
        for (int i = 0; i < parts.length; i++) {
            suffixes[i + 1] = parts[i].toLowerCase(Locale.ROOT);
        }
        return suffixes;
    }

    private static String executableName(String baseName) {
        return isWindows() ? baseName + ".exe" : baseName;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private CodeFormatterService() {}
}
