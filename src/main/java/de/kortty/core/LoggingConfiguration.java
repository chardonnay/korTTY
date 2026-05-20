package de.kortty.core;

import de.kortty.model.GlobalSettings;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Resolves and maintains KorTTY log storage without depending on already initialized logging.
 */
public final class LoggingConfiguration {

    public static final String LOG_DIR_PROPERTY = "kortty.log.dir";
    public static final String DEFAULT_LOG_DIRECTORY_NAME = "logs";
    private static final String SETTINGS_FILE = "global-settings.xml";
    private static final Duration COMPRESSION_AGE = Duration.ofHours(24);
    private static final Pattern ROTATED_LOG_PATTERN = Pattern.compile("kortty\\.\\d{4}-\\d{2}-\\d{2}\\.log");
    private static final Pattern ROTATED_COMPRESSED_LOG_PATTERN =
        Pattern.compile("kortty\\.\\d{4}-\\d{2}-\\d{2}\\.log\\.gz");

    private LoggingConfiguration() {
    }

    public static void bootstrapFromPersistedSettings(Path configDir) {
        try {
            PersistedLogSettings settings = readPersistedLogSettings(configDir);
            Path logDirectory = resolveLogDirectory(settings.logDirectoryPath(), configDir);
            System.setProperty(LOG_DIR_PROPERTY, logDirectory.toString());
            Files.createDirectories(logDirectory);
            maintainLogDirectory(logDirectory, settings.logRetentionDays(), Instant.now());
        } catch (Exception ignored) {
            Path fallback = defaultLogDirectory(configDir);
            System.setProperty(LOG_DIR_PROPERTY, fallback.toString());
            try {
                Files.createDirectories(fallback);
            } catch (IOException ignoredAgain) {
                // Logging must not prevent application startup.
            }
        }
    }

    public static void applyRuntimeSettings(GlobalSettings settings, Path configDir) throws Exception {
        Path logDirectory = resolveLogDirectory(settings, configDir);
        Files.createDirectories(logDirectory);
        System.setProperty(LOG_DIR_PROPERTY, logDirectory.toString());
        reconfigureLogback();
        maintainLogDirectory(logDirectory, settings != null ? settings.getLogRetentionDays() : GlobalSettings.DEFAULT_LOG_RETENTION_DAYS);
    }

    public static Path defaultLogDirectory(Path configDir) {
        return configDir.resolve(DEFAULT_LOG_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    public static Path resolveLogDirectory(GlobalSettings settings, Path configDir) {
        String configured = settings != null ? settings.getLogDirectoryPath() : null;
        return resolveLogDirectory(configured, configDir);
    }

    public static Path resolveLogDirectory(String configuredPath, Path configDir) {
        String normalized = configuredPath != null ? configuredPath.trim() : "";
        if (normalized.isBlank()) {
            return defaultLogDirectory(configDir);
        }
        if ("~".equals(normalized) || normalized.startsWith("~/")) {
            String home = System.getProperty("user.home", "");
            normalized = home + normalized.substring(1);
        }
        return Path.of(normalized).toAbsolutePath().normalize();
    }

    public static void maintainLogDirectory(Path logDirectory, int retentionDays) throws IOException {
        maintainLogDirectory(logDirectory, retentionDays, Instant.now());
    }

    static void maintainLogDirectory(Path logDirectory, int retentionDays, Instant now) throws IOException {
        if (logDirectory == null || !Files.isDirectory(logDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        int normalizedRetentionDays = Math.max(0, Math.min(retentionDays, GlobalSettings.MAX_LOG_RETENTION_DAYS));
        Instant compressionCutoff = now.minus(COMPRESSION_AGE);
        Instant retentionCutoff = normalizedRetentionDays > 0
            ? now.minus(Duration.ofDays(normalizedRetentionDays))
            : Instant.MIN;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDirectory)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String fileName = file.getFileName().toString();
                FileTime modifiedTime = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
                Instant modified = modifiedTime.toInstant();

                if (isRotatedLogFile(fileName)
                    && normalizedRetentionDays > 0
                    && modified.isBefore(retentionCutoff)) {
                    Files.deleteIfExists(file);
                    continue;
                }

                if (ROTATED_LOG_PATTERN.matcher(fileName).matches()
                    && modified.isBefore(compressionCutoff)) {
                    compressLogFile(file, modifiedTime);
                }
            }
        }
    }

    static PersistedLogSettings readPersistedLogSettings(Path configDir) {
        Path settingsFile = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
            return new PersistedLogSettings(null, GlobalSettings.DEFAULT_LOG_RETENTION_DAYS);
        }
        try (InputStream input = Files.newInputStream(settingsFile)) {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            Document document = factory.newDocumentBuilder().parse(input);
            Element root = document.getDocumentElement();
            String path = childText(root, "logDirectoryPath");
            int retentionDays = parseRetentionDays(childText(root, "logRetentionDays"));
            return new PersistedLogSettings(path, retentionDays);
        } catch (IOException | ParserConfigurationException | SAXException e) {
            return new PersistedLogSettings(null, GlobalSettings.DEFAULT_LOG_RETENTION_DAYS);
        }
    }

    private static void reconfigureLogback() throws Exception {
        org.slf4j.ILoggerFactory loggerFactory = org.slf4j.LoggerFactory.getILoggerFactory();
        if (!(loggerFactory instanceof ch.qos.logback.classic.LoggerContext context)) {
            return;
        }
        URL configUrl = Thread.currentThread().getContextClassLoader().getResource("logback.xml");
        if (configUrl == null) {
            return;
        }
        ch.qos.logback.classic.joran.JoranConfigurator configurator =
            new ch.qos.logback.classic.joran.JoranConfigurator();
        configurator.setContext(context);
        context.reset();
        configurator.doConfigure(configUrl);
    }

    private static boolean isRotatedLogFile(String fileName) {
        return ROTATED_LOG_PATTERN.matcher(fileName).matches()
            || ROTATED_COMPRESSED_LOG_PATTERN.matcher(fileName).matches();
    }

    private static void compressLogFile(Path logFile, FileTime originalModifiedTime) throws IOException {
        Path compressedFile = logFile.resolveSibling(logFile.getFileName() + ".gz");
        try (InputStream input = Files.newInputStream(logFile);
             OutputStream rawOutput = Files.newOutputStream(compressedFile, StandardOpenOption.CREATE_NEW);
             GZIPOutputStream output = new GZIPOutputStream(rawOutput)) {
            input.transferTo(output);
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(logFile);
            return;
        }
        Files.setLastModifiedTime(compressedFile, originalModifiedTime);
        Files.deleteIfExists(logFile);
    }

    private static int parseRetentionDays(String value) {
        if (value == null || value.isBlank()) {
            return GlobalSettings.DEFAULT_LOG_RETENTION_DAYS;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(0, Math.min(parsed, GlobalSettings.MAX_LOG_RETENTION_DAYS));
        } catch (NumberFormatException e) {
            return GlobalSettings.DEFAULT_LOG_RETENTION_DAYS;
        }
    }

    private static String childText(Element root, String tagName) {
        if (root == null) {
            return null;
        }
        var nodes = root.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || nodes.item(0) == null) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value != null ? value.trim() : null;
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean enabled)
        throws ParserConfigurationException {
        try {
            factory.setFeature(feature, enabled);
        } catch (ParserConfigurationException e) {
            if (feature.toLowerCase(Locale.ROOT).contains("doctype")) {
                throw e;
            }
        }
    }

    record PersistedLogSettings(String logDirectoryPath, int logRetentionDays) {
    }
}
