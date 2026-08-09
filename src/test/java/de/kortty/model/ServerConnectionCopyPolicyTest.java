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
 * Guard for the partial copy sites {@link ServerConnection#copyForDuplicate} and
 * {@link ServerConnection#copyForExport}: unlike copyForAuth they leave fields behind on purpose
 * (credentials, usage statistics, machine-local state), so a blanket carries-everything check does
 * not apply. Instead every persisted field must be classified here as carried, conditional (export
 * checkboxes) or excluded — a new field fails until it is classified, which turns a silent
 * omission (the bug class fixed in PR #195) into an explicit decision.
 */
public class ServerConnectionCopyPolicyTest {

    /** Fields the copies carry as a fresh instance by value, compared field-by-field. */
    private static final Set<String> DEEP_COPIED_FIELDS = Set.of("settings");

    private static final Set<String> DUPLICATE_CARRIED = Set.of(
            "host", "port", "username", "protocol", "localShellCommand",
            "localShellWorkingDirectory", "authMethod", "privateKeyPath",
            "terminalEffectPluginId", "terminalEffectAnimationSpeed", "terminalEmulationType",
            "group", "tag", "disableHostKeyCheck", "aiProfileId", "aiSkillIds", "settings");

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
            "name", "host", "port", "group", "tag", "protocol", "localShellCommand",
            "localShellWorkingDirectory", "authMethod", "privateKeyPath", "sshKeyId",
            "disableHostKeyCheck", "terminalEffectPluginId", "terminalEffectAnimationSpeed",
            "terminalEmulationType", "settings");

    /** Exported only when the matching export-dialog checkbox is set. */
    private static final Set<String> EXPORT_CONDITIONAL = Set.of(
            "username", "encryptedPassword", "credentialId", "sshTunnels", "jumpServer");

    /** Machine-local state that never leaves via export. */
    private static final Set<String> EXPORT_EXCLUDED = Set.of(
            "id", "privateKeyPassphrase", "usageCount", "lastUsed", "windowGeometry",
            "logConfig", "sessionJournalConfig", "connectionTimeoutSeconds", "retryCount",
            "temporaryKeyContent", "temporaryKeyExpirationMinutes", "temporaryKeyPermanent",
            "connectionSource", "teamworkSourceId", "teamworkVersionToken", "teamworkRole",
            "aiProfileId", "aiSkillIds");

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
