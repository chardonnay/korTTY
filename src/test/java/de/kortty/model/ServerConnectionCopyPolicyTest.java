package de.kortty.model;

import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Guard for the partial copy sites {@link ServerConnection#copyForDuplicate},
 * {@link ServerConnection#copyForExport} and {@link ServerConnection#copyForImport}: unlike copyForAuth they leave fields behind on purpose
 * (credentials, usage statistics, machine-local state), so a blanket carries-everything check does
 * not apply. Instead every persisted field must be classified here as carried, conditional (export
 * checkboxes) or excluded — a new field fails until it is classified, which turns a silent
 * omission (the bug class fixed in PR #195) into an explicit decision.
 */
public class ServerConnectionCopyPolicyTest {

    /** Fields the copies carry as a fresh instance by value, compared field-by-field. */
    private static final Set<String> DEEP_COPIED_FIELDS = Set.of("settings");

    private static final Set<String> DUPLICATE_CARRIED = Set.of(
            "highlightRuleSetId",                                          // keyword highlighting rule set
            "pasteWarningMode", "pasteLineDelayMs",                        // per-connection paste protection
            "isolationMode", "incognito", "strictTerminalMode",           // session isolation
            "host", "port", "username", "protocol", "localShellCommand",
            "localShellWorkingDirectory", "authMethod", "privateKeyPath",
            "shellIntegrationAutoInject",                                  // a duplicate runs the same local shell
            "terminalEffectPluginId", "terminalEffectAnimationSpeed", "terminalEmulationType",
            "encoding", "group", "tag", "tabColor", "disableHostKeyCheck", "aiProfileId", "aiSkillIds",
            "settings");

    /** Duplicate deliberately leaves these behind; moving one to carried is a product decision. */
    private static final Set<String> DUPLICATE_EXCLUDED = Set.of(
            "id", "name",                                                  // fresh identity; the dialog names the copy
            "encryptedPassword", "privateKeyPassphrase", "credentialId", "sshKeyId",
            "usageCount", "lastUsed",                                      // usage statistics start over
            "sshTunnels", "jumpServer", "windowGeometry",
            "logConfig", "sessionJournalConfig",
            "connectionTimeoutSeconds", "retryCount",
            "temporaryKeyContent", "temporaryKeyExpirationMinutes", "temporaryKeyPermanent",
            "connectionSource", "teamworkSourceId", "teamworkVersionToken", "teamworkRole");

    private static final Set<String> EXPORT_CARRIED = Set.of(
            "name", "host", "port", "group", "tag", "tabColor", "protocol", "localShellCommand",
            "localShellWorkingDirectory", "authMethod", "privateKeyPath", "sshKeyId",
            "disableHostKeyCheck", "terminalEffectPluginId", "terminalEffectAnimationSpeed",
            "terminalEmulationType", "encoding", "highlightRuleSetId", "pasteWarningMode", "pasteLineDelayMs",
            "isolationMode", "incognito", "strictTerminalMode",
            "settings");

    /** Exported only when the matching export-dialog checkbox is set. */
    private static final Set<String> EXPORT_CONDITIONAL = Set.of(
            "username", "encryptedPassword", "credentialId", "sshTunnels", "jumpServer");

    /** Machine-local state that never leaves via export. */
    private static final Set<String> EXPORT_EXCLUDED = Set.of(
            "id", "privateKeyPassphrase", "usageCount", "lastUsed", "windowGeometry",
            "logConfig", "sessionJournalConfig", "connectionTimeoutSeconds", "retryCount",
            "temporaryKeyContent", "temporaryKeyExpirationMinutes", "temporaryKeyPermanent",
            "connectionSource", "teamworkSourceId", "teamworkVersionToken", "teamworkRole",
            "aiProfileId", "aiSkillIds",
            // Set on the computer the shell runs on: an imported or shared file never switches on
            // korTTY's shell-integration wrapper.
            "shellIntegrationAutoInject");

    /**
     * Read back from an exported file except for the host-key check override, which the import has
     * never taken over.
     */
    private static final Set<String> IMPORT_NOT_READ_BACK = Set.of("disableHostKeyCheck");

    /**
     * Import reads back what export writes. Built from the export sets, so a field export leaves
     * behind (machine-local state) is excluded from import too, even when a hand-edited file
     * carries it.
     */
    private static final Set<String> IMPORT_CARRIED = without(EXPORT_CARRIED, IMPORT_NOT_READ_BACK);

    /** Imported only when the matching import-dialog checkbox is set. */
    private static final Set<String> IMPORT_CONDITIONAL = EXPORT_CONDITIONAL;

    private static final Set<String> IMPORT_EXCLUDED = union(EXPORT_EXCLUDED, IMPORT_NOT_READ_BACK);

    @Test
    void duplicateClassifiesAndCarriesEveryPersistedField() throws Exception {
        assertClassificationCoversAllFields("copyForDuplicate",
                List.of(DUPLICATE_CARRIED, DUPLICATE_EXCLUDED));

        ServerConnection source = new ServerConnection();
        ServerConnectionFieldFixture.populateAllDistinct(source, DEEP_COPIED_FIELDS);

        ServerConnection copy = ServerConnection.copyForDuplicate(source);

        assertCarried("copyForDuplicate", source, copy, DUPLICATE_CARRIED);
        assertNotCarried("copyForDuplicate", source, copy, DUPLICATE_EXCLUDED);
        assertThat(copy.getSettings()).isNotSameInstanceAs(source.getSettings());
    }

    @Test
    void exportWithAllOptionsCarriesConditionalFields() throws Exception {
        assertClassificationCoversAllFields("copyForExport",
                List.of(EXPORT_CARRIED, EXPORT_CONDITIONAL, EXPORT_EXCLUDED));

        ServerConnection source = new ServerConnection();
        ServerConnectionFieldFixture.populateAllDistinct(source, DEEP_COPIED_FIELDS);

        ServerConnection copy = ServerConnection.copyForExport(source, true, true, true, true);

        assertCarried("copyForExport(all options)", source, copy, EXPORT_CARRIED);
        assertCarried("copyForExport(all options)", source, copy, EXPORT_CONDITIONAL);
        assertNotCarried("copyForExport(all options)", source, copy, EXPORT_EXCLUDED);
        assertThat(copy.getSettings()).isNotSameInstanceAs(source.getSettings());
    }

    @Test
    void exportWithoutOptionsStillCarriesConfiguration() throws Exception {
        ServerConnection source = new ServerConnection();
        ServerConnectionFieldFixture.populateAllDistinct(source, DEEP_COPIED_FIELDS);

        ServerConnection copy = ServerConnection.copyForExport(source, false, false, false, false);

        assertCarried("copyForExport(no options)", source, copy, EXPORT_CARRIED);
        assertNotCarried("copyForExport(no options)", source, copy, EXPORT_CONDITIONAL);
        assertNotCarried("copyForExport(no options)", source, copy, EXPORT_EXCLUDED);
        assertThat(copy.getUsername()).isEmpty();
        assertThat(copy.getEncryptedPassword()).isNull();
        assertThat(copy.getCredentialId()).isNull();
        assertThat(copy.getJumpServer()).isNull();
    }

    @Test
    void importWithAllOptionsReadsBackWhatExportWrites() throws Exception {
        assertClassificationCoversAllFields("copyForImport",
                List.of(IMPORT_CARRIED, IMPORT_CONDITIONAL, IMPORT_EXCLUDED));

        ServerConnection source = new ServerConnection();
        ServerConnectionFieldFixture.populateAllDistinct(source, DEEP_COPIED_FIELDS);

        ServerConnection copy = ServerConnection.copyForImport(source, true, true, true, true);

        assertCarried("copyForImport(all options)", source, copy, IMPORT_CARRIED);
        assertCarried("copyForImport(all options)", source, copy, IMPORT_CONDITIONAL);
        assertNotCarried("copyForImport(all options)", source, copy, IMPORT_EXCLUDED);
        assertThat(copy.getSettings()).isNotSameInstanceAs(source.getSettings());
    }

    @Test
    void importWithoutOptionsStillReadsBackConfiguration() throws Exception {
        ServerConnection source = new ServerConnection();
        ServerConnectionFieldFixture.populateAllDistinct(source, DEEP_COPIED_FIELDS);

        ServerConnection copy = ServerConnection.copyForImport(source, false, false, false, false);

        assertCarried("copyForImport(no options)", source, copy, IMPORT_CARRIED);
        assertNotCarried("copyForImport(no options)", source, copy, IMPORT_CONDITIONAL);
        assertNotCarried("copyForImport(no options)", source, copy, IMPORT_EXCLUDED);
        assertThat(copy.getUsername()).isEmpty();
        assertThat(copy.getEncryptedPassword()).isNull();
        assertThat(copy.getCredentialId()).isNull();
        assertThat(copy.getJumpServer()).isNull();
    }

    @Test
    void importKeepsTheLocalShellAndMoshProtocols() {
        // The reported bug: the import built a fresh connection and never set the protocol, so an
        // exported Local Shell (or Mosh) connection came back as SSH and lost its shell.
        ServerConnection localShell = new ServerConnection();
        localShell.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        localShell.setLocalShellCommand("/usr/bin/fish");
        localShell.setLocalShellWorkingDirectory("/srv/work");
        ServerConnection mosh = new ServerConnection("Mosh", "mosh.example.test", 22, "demo");
        mosh.setProtocol(ConnectionProtocol.MOSH);

        ServerConnection importedShell = ServerConnection.copyForImport(
                ServerConnection.copyForExport(localShell, false, false, false, false),
                false, false, false, false);
        ServerConnection importedMosh = ServerConnection.copyForImport(
                ServerConnection.copyForExport(mosh, false, false, false, false),
                false, false, false, false);

        assertThat(importedShell.isLocalShell()).isTrue();
        assertThat(importedShell.getLocalShellCommand()).isEqualTo("/usr/bin/fish");
        assertThat(importedShell.getLocalShellWorkingDirectory()).isEqualTo("/srv/work");
        assertThat(importedMosh.getProtocol()).isEqualTo(ConnectionProtocol.MOSH);
    }

    @Test
    void duplicateExportAndImportCarryTheTerminalSettingsChoice() {
        // "settings" is carried as a whole; this pins that the global-vs-own choice inside it is too,
        // in both directions, so an exported own-settings connection keeps drawing with its values
        // and a global-following one keeps following the importer's global settings.
        for (boolean useGlobal : new boolean[] {true, false}) {
            ServerConnection source = new ServerConnection("Demo", "demo.example.test", 22, "demo");
            source.getSettings().setUseGlobalSettings(useGlobal);
            source.getSettings().setFontFamily("Own Mono");

            ServerConnection duplicate = ServerConnection.copyForDuplicate(source);
            ServerConnection imported = ServerConnection.copyForImport(
                    ServerConnection.copyForExport(source, false, false, false, false),
                    false, false, false, false);

            for (ServerConnection copy : List.of(duplicate, imported)) {
                assertThat(copy.getSettings().isUseGlobalSettings()).isEqualTo(useGlobal);
                assertThat(copy.getSettings().getFontFamily()).isEqualTo("Own Mono");
            }
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.addAll(b);
        return Set.copyOf(result);
    }

    private static Set<String> without(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.removeAll(b);
        return Set.copyOf(result);
    }

    /** Every persisted field must appear in exactly one classification set. */
    private static void assertClassificationCoversAllFields(String method, List<Set<String>> sets) {
        Set<String> classified = new HashSet<>();
        for (Set<String> set : sets) {
            for (String name : set) {
                assertWithMessage("Field '%s' is classified twice for %s", name, method)
                        .that(classified.add(name))
                        .isTrue();
            }
        }
        Set<String> persisted = new HashSet<>();
        for (Field field : ServerConnectionFieldFixture.persistedFields()) {
            persisted.add(field.getName());
        }
        assertWithMessage(
                "Every persisted ServerConnection field must be explicitly classified for %s;"
                        + " decide whether the new field is carried or excluded and add it to the"
                        + " matching set (this is how PR #195's silently-dropped fields are"
                        + " prevented from recurring)",
                method)
                .that(classified)
                .isEqualTo(persisted);
    }

    private static void assertCarried(String method, ServerConnection source, ServerConnection copy,
            Set<String> fieldNames) throws Exception {
        List<String> dropped = new ArrayList<>();
        for (Field field : fieldsNamed(fieldNames)) {
            Object expected = field.get(source);
            Object actual = field.get(copy);
            boolean carried = DEEP_COPIED_FIELDS.contains(field.getName())
                    ? ServerConnectionFieldFixture.equalByFields(expected, actual)
                    : Objects.equals(expected, actual);
            if (!carried) {
                dropped.add(field.getName() + " (expected " + expected + ", but copy has " + actual + ")");
            }
        }
        assertWithMessage("%s drops fields it is documented to carry", method)
                .that(dropped)
                .isEmpty();
    }

    private static void assertNotCarried(String method, ServerConnection source, ServerConnection copy,
            Set<String> fieldNames) throws Exception {
        List<String> leaked = new ArrayList<>();
        for (Field field : fieldsNamed(fieldNames)) {
            if (Objects.equals(field.get(source), field.get(copy))) {
                leaked.add(field.getName());
            }
        }
        assertWithMessage(
                "%s now carries fields documented as excluded; if that is intentional, move them"
                        + " to the carried set",
                method)
                .that(leaked)
                .isEmpty();
    }

    private static List<Field> fieldsNamed(Set<String> names) {
        List<Field> fields = new ArrayList<>();
        for (Field field : ServerConnectionFieldFixture.persistedFields()) {
            if (names.contains(field.getName())) {
                fields.add(field);
            }
        }
        return fields;
    }
}
