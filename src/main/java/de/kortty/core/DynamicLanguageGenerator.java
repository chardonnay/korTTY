package de.kortty.core;

import de.kortty.KorTTYApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates i18n properties files for a target language by translating the base (English) messages
 * using a {@link TranslationService} and writing the result to the config directory.
 */
public class DynamicLanguageGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DynamicLanguageGenerator.class);
    private static final String BASE_BUNDLE_RESOURCE = "i18n/messages.properties";
    private static final String SOURCE_LANG = "en";
    private static final int BATCH_SIZE = 25;
    /** Placeholders to preserve: {0}, {1}, ${var} */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("(\\{\\d+\\}|\\$\\{[^}]+\\})");

    private final TranslationService translationService;
    private final Path i18nDir;

    public DynamicLanguageGenerator(TranslationService translationService, Path configDirectory) {
        this.translationService = translationService;
        this.i18nDir = configDirectory.resolve("i18n");
    }

    /**
     * Loads the base (English) property keys and values from the classpath.
     */
    public static Map<String, String> loadBaseProperties() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = DynamicLanguageGenerator.class.getClassLoader().getResourceAsStream(BASE_BUNDLE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Base resource not found: " + BASE_BUNDLE_RESOURCE);
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String key : props.stringPropertyNames()) {
                out.put(key, props.getProperty(key));
            }
        }
        return out;
    }

    /**
     * Generates messages_XX.properties for the given target language code.
     *
     * @param targetLangCode target language code (e.g. "de", "fr")
     * @param progress       optional callback for progress 0.0..1.0; can be null
     * @return path to the written file
     */
    public Path generate(String targetLangCode, java.util.function.Consumer<Double> progress) throws IOException {
        if (targetLangCode == null || targetLangCode.isEmpty()) {
            throw new IllegalArgumentException("targetLangCode is required");
        }
        Map<String, String> base = loadBaseProperties();
        List<String> keys = new ArrayList<>(base.keySet());
        int total = keys.size();
        if (total == 0) {
            throw new IOException("No keys found in base resource " + BASE_BUNDLE_RESOURCE);
        }

        Files.createDirectories(i18nDir);

        List<String> valuesToTranslate = new ArrayList<>(total);
        List<List<String>> placeholderList = new ArrayList<>(total);
        for (String key : keys) {
            String value = base.get(key);
            MaskResult masked = maskPlaceholders(value);
            valuesToTranslate.add(masked.masked);
            placeholderList.add(masked.placeholders);
        }

        List<String> translatedValues = new ArrayList<>(total);
        int processed = 0;
        for (int i = 0; i < valuesToTranslate.size(); i += BATCH_SIZE) {
            int to = Math.min(i + BATCH_SIZE, valuesToTranslate.size());
            List<String> batch = valuesToTranslate.subList(i, to);
            List<String> batchResult = translationService.translateBatch(batch, SOURCE_LANG, targetLangCode);
            if (batchResult == null || batchResult.size() != batch.size()) {
                throw new IOException("Translation service returned failure or wrong batch size");
            }
            for (int j = 0; j < batch.size(); j++) {
                String translated = unmaskPlaceholders(batchResult.get(j), placeholderList.get(i + j));
                translatedValues.add(translated);
            }
            processed = to;
            if (progress != null) {
                progress.accept((double) processed / total);
            }
        }

        String filename = "messages_" + targetLangCode.toLowerCase() + ".properties";
        Path outFile = i18nDir.resolve(filename);
        writePropertiesFile(keys, translatedValues, outFile);
        if (progress != null) {
            progress.accept(1.0);
        }
        logger.info("Generated {} with {} keys", outFile, total);
        return outFile;
    }

    private static class MaskResult {
        final String masked;
        final List<String> placeholders;

        MaskResult(String masked, List<String> placeholders) {
            this.masked = masked;
            this.placeholders = placeholders;
        }
    }

    private static MaskResult maskPlaceholders(String text) {
        List<String> placeholders = new ArrayList<>();
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            placeholders.add(m.group(0));
            m.appendReplacement(sb, "__PH_" + (placeholders.size() - 1) + "__");
        }
        m.appendTail(sb);
        return new MaskResult(sb.toString(), placeholders);
    }

    private static String unmaskPlaceholders(String text, List<String> placeholders) {
        if (text == null) return null;
        for (int i = 0; i < placeholders.size(); i++) {
            text = text.replace("__PH_" + i + "__", placeholders.get(i));
        }
        return text;
    }

    /**
     * Escapes a key for Java properties format: backslashes first, then leading whitespace,
     * then '=', ':', '#' and '!' so the key cannot break the properties format.
     */
    private static String escapePropertyKey(String key) {
        if (key == null) return "";
        String k = key.replace("\\", "\\\\");
        int i = 0;
        while (i < k.length() && (k.charAt(i) == ' ' || k.charAt(i) == '\t')) i++;
        if (i > 0) {
            StringBuilder lead = new StringBuilder();
            for (int j = 0; j < i; j++) lead.append('\\').append(k.charAt(j));
            k = lead.toString() + k.substring(i);
        }
        k = k.replace("=", "\\=").replace(":", "\\:").replace("#", "\\#").replace("!", "\\!");
        return k;
    }

    private static void writePropertiesFile(List<String> keys, List<String> values, Path outFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by KorTTY dynamic i18n\n");
        sb.append("# KorTTY version: ").append(KorTTYApplication.getAppVersion()).append("\n");
        for (int i = 0; i < keys.size(); i++) {
            String key = escapePropertyKey(keys.get(i));
            String value = values.get(i);
            if (value != null) {
                value = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
            } else {
                value = "";
            }
            sb.append(key).append('=').append(value).append('\n');
        }
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Prefix written in generated files for version tracking. */
    private static final String VERSION_HEADER = "# KorTTY version: ";

    /**
     * Reads the KorTTY version from a generated messages_XX.properties file (first lines).
     * @return the version string (e.g. "1.9.0"), or null if not found (e.g. file from before this was added)
     */
    public static String readGeneratedVersion(Path propertiesFile) {
        if (propertiesFile == null || !Files.isRegularFile(propertiesFile)) return null;
        try {
            List<String> lines = Files.readAllLines(propertiesFile, StandardCharsets.UTF_8);
            for (int i = 0; i < Math.min(5, lines.size()); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith(VERSION_HEADER)) {
                    return line.substring(VERSION_HEADER.length()).trim();
                }
            }
        } catch (IOException e) {
            logger.debug("Could not read version from {}", propertiesFile, e);
        }
        return null;
    }
}
