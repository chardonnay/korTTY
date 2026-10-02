package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.StoredCredential;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Keeps the configuration backup complete. A store that persists itself under a new file-name
 * constant must be classified here — backed up (and then present in
 * {@link BackupManager#managedBackupFiles()}) or excluded with a reason — so a new store can no
 * longer be left out of every backup by accident, as themes, environments, swarm chats and the
 * MLX registry were.
 *
 * <p>Only {@code static final String X = "name.ext"} constants are discovered; a store that
 * builds its path inline is invisible to this scan.</p>
 */
class BackupCoverageTest {

    private static final Path JAVA_ROOT = Path.of("src/main/java");

    private static final Pattern STORE_CONSTANT = Pattern.compile(
        "static final String (\\w+)\\s*=\\s*\"([\\w.-]+\\.(xml|json|properties|key|enc|toml))\"");

    /** {@code SimpleClass.CONSTANT} → the archive entry the file is backed up as. */
    private static final Map<String, String> BACKED_UP = Map.ofEntries(
        Map.entry("XMLConnectionRepository.CONNECTIONS_FILE", "connections.xml"),
        Map.entry("CredentialManager.CREDENTIALS_FILE", "credentials.xml"),
        Map.entry("EnvironmentManager.ENVIRONMENTS_FILE", "environments.xml"),
        Map.entry("GPGKeyManager.GPG_KEYS_FILE", "gpg-keys.xml"),
        Map.entry("SSHKeyManager.SSH_KEYS_FILE", "ssh-keys.xml"),
        Map.entry("GlobalSettingsManager.SETTINGS_FILE", "global-settings.xml"),
        Map.entry("LoggingConfiguration.SETTINGS_FILE", "global-settings.xml"),
        Map.entry("ThemeManager.THEMES_FILE", "themes.xml"),
        Map.entry("JobSchedulerRepository.FILE_NAME", "job-scheduler.xml"),
        Map.entry("MasterPasswordManager.MASTER_KEY_FILE", "master.key"),
        Map.entry("SshHostKeyTrustManager.STORE_FILE_NAME", "ssh-host-keys.properties"),
        Map.entry("SnippetManager.SNIPPETS_FILE", "snippets.xml"),
        Map.entry("SnippetVariableManager.VARIABLES_FILE", "snippet-variables.xml"),
        Map.entry("AiChatManager.AI_CHATS_FILE", "ai-chats.xml"),
        Map.entry("SwarmChatManager.SWARM_CHATS_FILE", "swarm-chats.xml"),
        Map.entry("LlamaModelRegistry.REGISTRY_FILE_NAME", "llm/models.xml"),
        Map.entry("MlxModelRegistry.REGISTRY_FILE_NAME", "llm/mlx-models.json"));

    /** {@code SimpleClass.CONSTANT} → why the file is deliberately not part of the backup. */
    private static final Map<String, String> EXCLUDED = Map.ofEntries(
        Map.entry("HuggingFaceTokenStore.FILE_NAME",
            "legacy token file; the live token is GlobalSettings.encryptedHuggingFaceToken"),
        Map.entry("JvmLaunchProfileStore.FILE_NAME", "launcher mirror of global-settings.xml"),
        Map.entry("LlamaRuntimePackageInstaller.DESCRIPTOR_FILE", "regenerable runtime package"),
        Map.entry("LlamaRuntimePackageIntegrity.DESCRIPTOR_FILE", "regenerable runtime package"),
        Map.entry("LlamaRuntimePackageIntegrity.MANIFEST_FILE", "regenerable runtime package"),
        Map.entry("ControlEndpointFile.FILE_NAME", "per-process endpoint of the running app"),
        Map.entry("SessionJournalSearchCardIndex.CACHE_FILE_NAME", "regenerable search cache"),
        Map.entry("SessionJournalService.DOCUMENT_FILE_NAME",
            "lives in ~/.kortty/journals/, which is not part of the configuration backup"),
        Map.entry("SessionJournalVisitedStore.FILE_NAME", "UI state"),
        Map.entry("PolicyLocator.POLICY_FILE_NAME", "managed by the administrator"),
        Map.entry("GitTeamworkAdapter.CONNECTIONS_FILENAME", "inside the teamwork repository"),
        Map.entry("GitTeamworkAdapter.CONNECTIONS_FILENAME_LEGACY", "inside the teamwork repository"),
        Map.entry("TeamworkCacheRepository.CACHE_FILE", "re-synced from the teamwork repository"),
        Map.entry("TeamworkRecycleBinService.RECYCLE_FILE", "teamwork state, re-synced"),
        Map.entry("TelemetryService.SPOOL_FILE", "unsent telemetry spool"));

    /** Files in ~/.kortty that must never end up in a backup archive. */
    private static final List<String> NEVER_BACKED_UP = List.of(
        "master.autounlock",
        "jvm-launch.properties",
        "huggingface-token.enc",
        "endpoint.json",
        "telemetry-spool.json",
        "teamwork-cache.xml",
        "journal-search-visited.xml",
        "kortty-policy.toml");

    @Test
    void everyPersistedStoreFileIsClassified() throws IOException {
        Map<String, String> discovered = discoverStoreConstants();
        assertWithMessage("the source scan found no store constants — is the working directory the repo root?")
            .that(discovered).isNotEmpty();

        List<String> unclassified = new ArrayList<>();
        for (Map.Entry<String, String> constant : discovered.entrySet()) {
            String name = constant.getKey();
            if (BACKED_UP.containsKey(name)) {
                assertWithMessage(name + " is backed up as " + BACKED_UP.get(name)
                        + " but names " + constant.getValue())
                    .that(BACKED_UP.get(name)).endsWith(constant.getValue());
            } else if (!EXCLUDED.containsKey(name)) {
                unclassified.add("classify " + name + " (\"" + constant.getValue() + "\") in BackupCoverageTest");
            }
        }
        assertWithMessage("new store files must be backed up or excluded with a reason")
            .that(unclassified).isEmpty();

        List<String> stale = new ArrayList<>();
        for (String name : BACKED_UP.keySet()) {
            if (!discovered.containsKey(name)) {
                stale.add(name);
            }
        }
        for (String name : EXCLUDED.keySet()) {
            if (!discovered.containsKey(name)) {
                stale.add(name);
            }
        }
        assertWithMessage("remove these entries from BackupCoverageTest, the constants are gone")
            .that(stale).isEmpty();
    }

    @Test
    void everyBackedUpStoreIsInManagedBackupFiles() {
        // Two constants name global-settings.xml, so compare the distinct entries.
        assertThat(BackupManager.managedBackupFiles())
            .containsAtLeastElementsIn(new java.util.TreeSet<>(BACKED_UP.values()));
    }

    @Test
    void excludedConfigRootFilesStayOut() {
        for (String file : NEVER_BACKED_UP) {
            assertWithMessage(file + " must not be backed up")
                .that(BackupManager.managedBackupFiles()).doesNotContain(file);
        }
    }

    @Test
    void passwordBackupRoundTripsThemesEnvironmentsSwarmChatsAndMlxRegistry() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-coverage-");
        Path configDir = Files.createDirectories(root.resolve("config"));
        Map<String, byte[]> files = new TreeMap<>();
        files.put(ThemeManager.THEMES_FILE, "<themes><theme id=\"custom\"/></themes>".getBytes(StandardCharsets.UTF_8));
        files.put(EnvironmentManager.ENVIRONMENTS_FILE,
            "<environments><environment id=\"lab\"/></environments>".getBytes(StandardCharsets.UTF_8));
        files.put(SwarmChatManager.SWARM_CHATS_FILE, "<swarmChats><chat id=\"s1\"/></swarmChats>".getBytes(StandardCharsets.UTF_8));
        files.put("llm/mlx-models.json", "{\"models\":[{\"id\":\"qwen3\"}]}".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = configDir.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }

        char[] masterPassword = "master-pw".toCharArray();
        CredentialManager credentialManager = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential(
            "backup", "user", StoredCredential.Environment.PRODUCTION);
        credentialManager.setPassword(credential, "backup-pw", masterPassword);
        credentialManager.addCredential(credential);
        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());
        Path backup = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), credentialManager, null, masterPassword);

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        new BackupManager(restoreDir, new GlobalSettings()).importBackup(backup, "backup-pw", true);

        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            assertWithMessage(file.getKey() + " was not restored byte for byte")
                .that(Files.readAllBytes(restoreDir.resolve(file.getKey()))).isEqualTo(file.getValue());
        }
    }

    /** {@code SimpleClass.CONSTANT} → file name, for every store-file constant in main. */
    private static Map<String, String> discoverStoreConstants() throws IOException {
        Map<String, String> constants = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(JAVA_ROOT)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String className = source.getFileName().toString().replaceFirst("\\.java$", "");
                Matcher matcher = STORE_CONSTANT.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    constants.put(className + "." + matcher.group(1), matcher.group(2));
                }
            }
        }
        return constants;
    }
}
