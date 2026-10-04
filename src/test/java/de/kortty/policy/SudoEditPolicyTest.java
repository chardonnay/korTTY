package de.kortty.policy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The {@code sftp-sudo-edit} feature (SFTP-22, D16): allowed unless denied, and only together with
 * {@code file-transfer} and {@code load-into-snippet-editor = "allow"}; the SFTP manager greys out
 * "Edit as root (sudo)..." up front whenever it is not allowed.
 */
class SudoEditPolicyTest {

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kt-sudoedit-policy");
    }

    @AfterMethod(alwaysRun = true)
    void cleanup() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static final PolicyIdentity USER = new PolicyIdentity() {
        @Override
        public String userName() {
            return "u";
        }

        @Override
        public Set<String> osGroups() {
            return Set.of();
        }
    };

    private static EffectivePolicy resolve(PolicyRule... rules) {
        return EffectivePolicy.resolve(new PolicyFile(1, "ACME", Map.of(), List.of(rules),
            List.of(), List.of(), List.of(), List.of()), USER);
    }

    private PolicyLoadResult load(String toml) throws IOException {
        Path file = dir.resolve("kortty-policy.toml");
        Files.writeString(file, "[meta]\nschema-version = 1\norganization = \"ACME\"\n\n" + toml, StandardCharsets.UTF_8);
        return PolicyLoader.load(file);
    }

    @Test
    void theTomlKeyIsSftpSudoEdit() {
        assertThat(PolicyFeature.SFTP_SUDO_EDIT.tomlKey()).isEqualTo("sftp-sudo-edit");
        assertThat(PolicyFeature.fromTomlKey(" SFTP-Sudo-Edit ")).isEqualTo(PolicyFeature.SFTP_SUDO_EDIT);
    }

    @Test
    void theLoaderReadsTheFeatureAndDenyTakesItAway() throws IOException {
        PolicyLoadResult result = load("""
            [[rule]]
              [rule.features]
              sftp-sudo-edit = "deny"
            """);

        assertWithMessage(String.valueOf(result.errors())).that(result.isValid()).isTrue();
        assertThat(result.warnings()).isEmpty();
        EffectivePolicy policy = EffectivePolicy.resolve(result.file(), USER);
        assertThat(policy.sudoEditAllowed()).isFalse();
        assertThat(policy.isManaged(ManagedSetting.SFTP_SUDO_EDIT)).isTrue();
        // Only the root edit goes: the other SFTP features stay.
        assertThat(policy.fileTransferAllowed()).isTrue();
        assertThat(policy.loadIntoSnippetEditor()).isNotEqualTo(LoadIntoEditorMode.DENY);
    }

    @Test
    void allowedWithoutAPolicyAndWithAnExplicitAllow() {
        assertThat(EffectivePolicy.unrestricted().sudoEditAllowed()).isTrue();
        assertThat(EffectivePolicy.unrestricted().isManaged(ManagedSetting.SFTP_SUDO_EDIT)).isFalse();
        EffectivePolicy allow = resolve(PolicyRule.builder()
            .features(Map.of(PolicyFeature.SFTP_SUDO_EDIT, PolicyDecision.ALLOW)).build());
        assertThat(allow.sudoEditAllowed()).isTrue();
    }

    @Test
    void readOnlyOrDenyOfTheSnippetEditorModeAlsoTakeItAway() {
        for (LoadIntoEditorMode mode : LoadIntoEditorMode.values()) {
            EffectivePolicy policy = resolve(PolicyRule.builder()
                .features(Map.of(PolicyFeature.SFTP_SUDO_EDIT, PolicyDecision.ALLOW))
                .loadIntoSnippetEditor(mode).build());
            assertWithMessage(mode.name()).that(policy.sudoEditAllowed()).isEqualTo(mode == LoadIntoEditorMode.ALLOW);
        }
    }

    @Test
    void aFileTransferDenyAlsoTakesItAway() {
        EffectivePolicy policy = resolve(PolicyRule.builder()
            .features(Map.of(PolicyFeature.SFTP_SUDO_EDIT, PolicyDecision.ALLOW,
                PolicyFeature.FILE_TRANSFER, PolicyDecision.DENY)).build());
        assertThat(policy.sudoEditAllowed()).isFalse();
    }

    @Test
    void lockdownDeniesIt() {
        assertThat(EffectivePolicy.lockdown().sudoEditAllowed()).isFalse();
    }

    @Test
    void theSftpManagerGreysTheEntriesOutUpFrontAndChecksAgainOnUse() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/SFTPManagerTab.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(source).contains("return de.kortty.policy.PolicyManager.effective().sudoEditAllowed();");
        assertThat(source).contains("editRemoteSudoItem.setDisable(!editableFile || !sudoEditAvailable());");
        assertThat(source).contains("sudoEditItem.setDisable(!isSingleFile || !sudoEditAvailable());");
        assertThat(source).contains("openWithMenu.setDisable(sudoEditItem.isDisable());");
        String open = source.substring(source.indexOf("private void openSelectedRemoteFileAsRoot("));
        open = open.substring(0, open.indexOf("remoteEdits.openAsRoot("));
        assertThat(open).contains("remoteEditAllowedByPolicy(sudoEditAvailable())");
        // "Open with" sits after the external editor, and the leftover-parts item stays last.
        String menu = source.substring(source.indexOf("private ContextMenu createRemoteContextMenu()"));
        menu = menu.substring(0, menu.indexOf("return menu;"));
        assertThat(menu).contains("editWithSnippetEditorItem, editExternalItem, openWithMenu, openImageItem");
        assertThat(menu.indexOf("openWithMenu")).isLessThan(menu.indexOf("addRemovePartsItem(menu, true);"));
    }
}
