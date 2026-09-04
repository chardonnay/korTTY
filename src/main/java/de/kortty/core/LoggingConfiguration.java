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
    /** logback.xml: maximum number of rotated daily files kept (0 = unlimited). */
    public static final String LOG_MAX_HISTORY_PROPERTY = "kortty.log.maxHistory";
    /** logback.xml: total size cap over all rotated files, logback FileSize syntax (0 = uncapped). */
    public static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "kortty.log.totalSizeCap";
    /** logback.xml: fully-qualified encoder class for the FILE appender (pattern vs JSON). */
    public static final String LOG_ENCODER_CLASS_PROPERTY = "kortty.log.encoderClass";
    private static final String JSON_ENCODER_CLASS = "ch.qos.logback.classic.encoder.JsonEncoder";
    public static final String DEFAULT_LOG_DIRECTORY_NAME = "logs";
    private static final String SETTINGS_FILE = "global-settings.xml";
    private static final Duration COMPRESSION_AGE = Duration.ofHours(24);
    private static final Pattern ROTATED_LOG_PATTERN = Pattern.compile("kortty\\.\\d{4}-\\d{2}-\\d{2}\\.log");
    private static final Pattern ROTATED_COMPRESSED_LOG_PATTERN =
        Pattern.compile("kortty\\.\\d{4}-\\d{2}-\\d{2}\\.log\\.gz");

    private LoggingConfiguration() {
    }

    public static void bootstrapFromPersistedSettings(Path configDir) {
        // Enterprise policy overrides for logging — read logger-free (see PolicyBootstrap): this
        // runs before logback initializes, so even the first log lines land in the admin's
        // directory, format and rotation scheme.
        de.kortty.policy.PolicyRule.LoggingRule policyLogging =
            de.kortty.policy.PolicyBootstrap.peekQuietly().logging();
        try {
            PersistedLogSettings settings = readPersistedLogSettings(configDir);
            String directorySetting = policyLogging.directory() != null
                ? policyLogging.directory()
                : settings.logDirectoryPath();
            int retentionDays = policyLogging.retentionDays() != null
                ? policyLogging.retentionDays()
                : settings.logRetentionDays();
            Path logDirectory = resolveLogDirectory(directorySetting, configDir);
            System.setProperty(LOG_DIR_PROPERTY, logDirectory.toString());
            applyPolicyLogbackProperties(policyLogging);
            Files.createDirectories(logDirectory);
            maintainLogDirectory(logDirectory, retentionDays, Instant.now(),
                isCompressionEnabled(policyLogging));
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
        // The directory/retention fields in GlobalSettings are already policy-clamped; format and
        // rotation come straight from the policy.
        de.kortty.policy.PolicyRule.LoggingRule policyLogging =
            de.kortty.policy.PolicyManager.effective().logging();
        Path logDirectory = resolveLogDirectory(settings, configDir);
        Files.createDirectories(logDirectory);
        System.setProperty(LOG_DIR_PROPERTY, logDirectory.toString());
        applyPolicyLogbackProperties(policyLogging);
        reconfigureLogback();
        maintainLogDirectory(logDirectory,
            settings != null ? settings.getLogRetentionDays() : GlobalSettings.DEFAULT_LOG_RETENTION_DAYS,
            Instant.now(),
            isCompressionEnabled(policyLogging));
    }

    private static boolean isCompressionEnabled(de.kortty.policy.PolicyRule.LoggingRule policyLogging) {
        return policyLogging.compress() == null || policyLogging.compress();
    }

    /**
     * Feeds the policy's format and rotation caps into logback.xml via its property placeholders.
     * Set before logback initializes (bootstrap) and before every {@link #reconfigureLogback()},
     * so the FILE appender is built with the right encoder and rotation from the start — no
     * fragile post-hoc appender surgery.
     */
    private static void applyPolicyLogbackProperties(de.kortty.policy.PolicyRule.LoggingRule policyLogging) {
        if (policyLogging.format() == de.kortty.policy.LogFormat.JSON) {
            System.setProperty(LOG_ENCODER_CLASS_PROPERTY, JSON_ENCODER_CLASS);
        }
        if (policyLogging.rotationMaxFiles() != null) {
            System.setProperty(LOG_MAX_HISTORY_PROPERTY, String.valueOf(policyLogging.rotationMaxFiles()));
        }
        if (policyLogging.rotationTotalSizeMb() != null) {
            System.setProperty(LOG_TOTAL_SIZE_CAP_PROPERTY,
                policyLogging.rotationTotalSizeMb() > 0
                    ? policyLogging.rotationTotalSizeMb() + "MB"
                    : "0");
        }
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
        maintainLogDirectory(logDirectory, retentionDays, now, true);
    }

    static void maintainLogDirectory(Path logDirectory, int retentionDays, Instant now,
                                     boolean compressionEnabled) throws IOException {
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

                if (compressionEnabled
                    && ROTATED_LOG_PATTERN.matcher(fileName).matches()
                    && modified.isBefore(compressionCutoff)) {
                    compressLogFile(file, modifiedTime);
                }
            }
        }
        // The archived AI answers hold the user's script regions in plain text; they live under
        // the same retention rule as the rotated logs beside them.
        if (normalizedRetentionDays > 0) {
            AiAnswerArchive.deleteOlderThan(logDirectory.resolve(AiAnswerArchive.DIRECTORY_NAME), retentionCutoff);
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
