package de.kortty.policy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.SftpConflictDefault;
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
 * The enterprise-policy leg of SFTP file transfer (SFTP-08): the {@code file-transfer} feature and
 * {@link FileTransferGate}, the {@code [rule.sftp]} table with its merge rules, and the clamp that
 * caps the parallel transfers and pins the conflict default in {@code global-settings.xml}.
 */
class FileTransferPolicyTest {

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kt-ftpolicy");
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

    private static PolicyRule sftp(Integer maxParallel, SftpConflictDefault conflict) {
        return PolicyRule.builder().sftp(new PolicyRule.SftpRule(maxParallel, conflict)).build();
    }

    private static EffectivePolicy deny() {
        return resolve(PolicyRule.builder()
            .features(Map.of(PolicyFeature.FILE_TRANSFER, PolicyDecision.DENY)).build());
    }

    private PolicyLoadResult load(String toml) throws IOException {
        Path file = dir.resolve("kortty-policy.toml");
        Files.writeString(file, "[meta]\nschema-version = 1\norganization = \"ACME\"\n\n" + toml, StandardCharsets.UTF_8);
        return PolicyLoader.load(file);
    }

    // ---- toml -----------------------------------------------------------------------------

    @Test
    void theTomlKeyIsFileTransfer() {
        assertThat(PolicyFeature.FILE_TRANSFER.tomlKey()).isEqualTo("file-transfer");
        assertThat(PolicyFeature.fromTomlKey(" File-Transfer ")).isEqualTo(PolicyFeature.FILE_TRANSFER);
    }

    @Test
    void theLoaderReadsTheFeatureAndTheSftpTable() throws IOException {
        PolicyLoadResult result = load("""
            [[rule]]
              [rule.features]
              file-transfer = "deny"
              [rule.sftp]
              max-parallel-transfers = 2
              conflict-default = "Skip"
            """);

        assertWithMessage(String.valueOf(result.errors())).that(result.isValid()).isTrue();
        assertThat(result.warnings()).isEmpty();
        PolicyRule rule = result.file().rules().get(0);
        assertThat(rule.features()).containsEntry(PolicyFeature.FILE_TRANSFER, PolicyDecision.DENY);
        assertThat(rule.sftp()).isEqualTo(new PolicyRule.SftpRule(2, SftpConflictDefault.SKIP));

        EffectivePolicy policy = EffectivePolicy.resolve(result.file(), USER);
        assertThat(policy.fileTransferAllowed()).isFalse();
        assertThat(policy.sftpMaxParallelTransfers()).isEqualTo(2);
        assertThat(policy.sftpConflictDefault()).isEqualTo(SftpConflictDefault.SKIP);
        assertThat(policy.isManaged(ManagedSetting.FILE_TRANSFER)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.SFTP_TRANSFERS)).isTrue();
    }

    @Test
    void anOutOfRangeCapOrAnUnknownConflictDefaultRejectsTheFile() throws IOException {
        assertThat(load("[[rule]]\n  [rule.sftp]\n  max-parallel-transfers = 0\n").isValid()).isFalse();
        assertThat(load("[[rule]]\n  [rule.sftp]\n  max-parallel-transfers = 9\n").isValid()).isFalse();
        PolicyLoadResult bad = load("[[rule]]\n  [rule.sftp]\n  conflict-default = \"rename\"\n");
        assertThat(bad.isValid()).isFalse();
        assertThat(String.join("\n", bad.errors())).contains("conflict-default");
    }

    @Test
    void anUnknownSftpKeyIsOnlyAWarning() throws IOException {
        PolicyLoadResult result = load("[[rule]]\n  [rule.sftp]\n  turbo = true\n");
        assertThat(result.isValid()).isTrue();
        assertThat(String.join("\n", result.warnings())).contains("turbo");
    }

    // ---- the gate -------------------------------------------------------------------------

    @Test
    void denyBlocksEveryGatedRouteWithTheOrganizationsReason() {
        EffectivePolicy policy = deny();
        for (FileTransferGate.Route route : FileTransferGate.Route.values()) {
            FileTransferGate.Verdict verdict = FileTransferGate.check(policy, route);
            assertWithMessage(route.name()).that(verdict.allowed()).isFalse();
            assertWithMessage(route.name()).that(verdict.reasonKey()).isEqualTo(FileTransferGate.DENIED_KEY);
            assertWithMessage(route.name()).that(verdict.organization()).isEqualTo("ACME");
            assertWithMessage(route.name()).that(verdict.reason()).isNotEmpty();
        }
    }

    @Test
    void withoutAPolicyOrWithAllowEveryRouteIsOpen() {
        EffectivePolicy allow = resolve(PolicyRule.builder()
            .features(Map.of(PolicyFeature.FILE_TRANSFER, PolicyDecision.ALLOW)).build());
        for (FileTransferGate.Route route : FileTransferGate.Route.values()) {
            assertThat(FileTransferGate.check(EffectivePolicy.unrestricted(), route).allowed()).isTrue();
            assertThat(FileTransferGate.check(null, route).allowed()).isTrue();
            assertThat(FileTransferGate.check(allow, route).allowed()).isTrue();
            assertThat(FileTransferGate.check(allow, route).reason()).isNull();
        }
        assertThat(EffectivePolicy.unrestricted().isManaged(ManagedSetting.FILE_TRANSFER)).isFalse();
    }

    @Test
    void lockdownDeniesFileTransferAndPinsTheSafestSftpValues() {
        EffectivePolicy lockdown = EffectivePolicy.lockdown();
        assertThat(lockdown.fileTransferAllowed()).isFalse();
        assertThat(lockdown.sftpMaxParallelTransfers()).isEqualTo(1);
        assertThat(lockdown.sftpConflictDefault()).isEqualTo(SftpConflictDefault.ASK);
    }

    // ---- merge ----------------------------------------------------------------------------

    @Test
    void sameTierRulesPickTheSmallestCapAndTheMostRestrictiveConflictDefault() {
        EffectivePolicy policy = resolve(sftp(4, SftpConflictDefault.OVERWRITE), sftp(2, SftpConflictDefault.SKIP));
        assertThat(policy.sftpMaxParallelTransfers()).isEqualTo(2);
        assertThat(policy.sftpConflictDefault()).isEqualTo(SftpConflictDefault.SKIP);

        EffectivePolicy ask = resolve(sftp(null, SftpConflictDefault.ASK), sftp(null, SftpConflictDefault.OVERWRITE),
            sftp(null, SftpConflictDefault.SKIP));
        assertThat(ask.sftpConflictDefault()).isEqualTo(SftpConflictDefault.ASK);
        assertThat(ask.sftpMaxParallelTransfers()).isNull();
    }

    @Test
    void aUserRuleWinsOverTheAllUsersRule() {
        PolicyRule forUser = PolicyRule.builder().users(Set.of("u"))
            .sftp(new PolicyRule.SftpRule(6, SftpConflictDefault.OVERWRITE)).build();
        EffectivePolicy policy = resolve(sftp(1, SftpConflictDefault.ASK), forUser);
        assertThat(policy.sftpMaxParallelTransfers()).isEqualTo(6);
        assertThat(policy.sftpConflictDefault()).isEqualTo(SftpConflictDefault.OVERWRITE);
    }

    @Test
    void mostRestrictiveOrdersAskBeforeSkipBeforeOverwrite() {
        assertThat(SftpConflictDefault.mostRestrictive(SftpConflictDefault.OVERWRITE, SftpConflictDefault.ASK))
            .isEqualTo(SftpConflictDefault.ASK);
        assertThat(SftpConflictDefault.mostRestrictive(SftpConflictDefault.SKIP, SftpConflictDefault.OVERWRITE))
            .isEqualTo(SftpConflictDefault.SKIP);
        assertThat(SftpConflictDefault.mostRestrictive(null, SftpConflictDefault.OVERWRITE))
            .isEqualTo(SftpConflictDefault.OVERWRITE);
    }

    // ---- clamp ----------------------------------------------------------------------------

    @Test
    void theClampCapsParallelTransfersAndPinsTheConflictDefaultOnLoadAndSave() throws Exception {
        Path configDir = Files.createDirectories(dir.resolve("config"));
        GlobalSettingsManager plain = new GlobalSettingsManager(configDir);
        plain.load();
        plain.getSettings().setSftpParallelTransfers(7);
        plain.getSettings().setSftpConflictDefault(SftpConflictDefault.OVERWRITE);
        plain.save();

        EffectivePolicy policy = resolve(sftp(2, SftpConflictDefault.ASK));
        GlobalSettingsManager managed = new GlobalSettingsManager(configDir);
        managed.setPolicyClamp(new PolicyClamp(policy));
        managed.load();
        assertThat(managed.getSettings().getSftpParallelTransfers()).isEqualTo(2);
        assertThat(managed.getSettings().getSftpConflictDefault()).isEqualTo(SftpConflictDefault.ASK);
        assertThat(policy.isManaged(ManagedSetting.SFTP_TRANSFERS)).isTrue();

        managed.getSettings().setSftpParallelTransfers(8);
        managed.getSettings().setSftpConflictDefault(SftpConflictDefault.SKIP);
        managed.save();
        String xml = Files.readString(configDir.resolve("global-settings.xml"));
        assertWithMessage("a locked control can never persist a diverging value")
            .that(xml).contains("<sftpParallelTransfers>2</sftpParallelTransfers>");
        assertThat(xml).contains("<sftpConflictDefault>ask</sftpConflictDefault>");
    }

    @Test
    void theCapLeavesAUsersSmallerChoiceAlone() throws Exception {
        Path configDir = Files.createDirectories(dir.resolve("config"));
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(resolve(sftp(4, null))));
        manager.load();
        manager.getSettings().setSftpParallelTransfers(1);
        manager.getSettings().setSftpConflictDefault(SftpConflictDefault.OVERWRITE);
        manager.save();

        assertThat(manager.getSettings().getSftpParallelTransfers()).isEqualTo(1);
        assertWithMessage("a policy without conflict-default leaves the choice to the user")
            .that(manager.getSettings().getSftpConflictDefault()).isEqualTo(SftpConflictDefault.OVERWRITE);
    }

    // ---- wiring pins ----------------------------------------------------------------------

    @Test
    void theSftpManagerAsksTheGateOnEveryTransferRoute() throws IOException {
        String tab = Files.readString(Path.of("src/main/java/de/kortty/ui/SFTPManagerTab.java"))
            .replace("\r\n", "\n");
        assertThat(tab).contains("if (refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD)) return;");
        assertThat(tab).contains("if (refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_DOWNLOAD)) return;");
        assertThat(tab).contains("refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_DRAG_OUT)");
        assertThat(tab).contains("|| !fileTransferAllowed());");
        assertThat(tab).contains("downloadButton.setDisable(!usable || !fileTransferAllowed());");
    }

    @Test
    void theSettingsDialogLocksBothTransferControls() throws IOException {
        String dialog = Files.readString(Path.of("src/main/java/de/kortty/ui/SettingsDialog.java"))
            .replace("\r\n", "\n");
        assertThat(dialog).contains(
            "de.kortty.policy.PolicyUiSupport.lockIf(sftpConflictDefaultCombo, sftpPolicy.sftpConflictDefault() != null);");
        assertThat(dialog).contains("Integer parallelCap = sftpPolicy.sftpMaxParallelTransfers();");
    }
}
