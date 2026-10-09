package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ExternalAiSkillCandidate;
import de.kortty.core.ExternalAiSkillDocument;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillExternalSource;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.AiSkillProviderAuth;
import de.kortty.model.AiSkillProviderType;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's external AI-skill screenshots: AI Skills → External, the import
 * dialog and the providers dialog. Uses a throwaway settings directory and a demo library — no network
 * call is made; search results and the preview are filled in directly.
 *
 * <p>Run via the {@code generateExternalAiSkillsScreenshots} Gradle task. Exit 0 = OK.</p>
 */
public final class ExternalAiSkillsScreenshotGenerator {

    private static final String EXTERNAL_VIEW = "app-docs/screenshots/ai/ai-skills-external.png";
    private static final String IMPORT_DIALOG = "app-docs/screenshots/ai/ai-skills-import.png";
    private static final String PROVIDERS_DIALOG = "app-docs/screenshots/ai/ai-skill-providers.png";

    private static final String PDF_PREVIEW = """
        ---
        name: kubernetes-patterns
        description: Kubernetes workload patterns, probes, RBAC and autoscaling.
        ---

        # Kubernetes patterns

        ## Probes
        - Give every container a readiness probe; use a liveness probe only for deadlocks.
        - Keep `initialDelaySeconds` short and use a startup probe for slow starts.

        ## Resources
        - Always set requests; set limits for memory, not for CPU.
        """;

    private ExternalAiSkillsScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-external-skills-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                KorTTYApplication app = demoApplication(isolatedHome);
                writeExternalView(app);
                writeImportDialog(app);
                writeProvidersDialog(app);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + failure.get());
            System.exit(1);
        }
        System.exit(0);
    }

    /** An application object that only carries a settings manager — enough for the AI-skill UI. */
    private static KorTTYApplication demoApplication(Path home) throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(home);
        GlobalSettings settings = manager.getSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        List<AiSkillProvider> providers = new ArrayList<>(AiSkillProvider.defaults());
        providers.get(0).setAuth(AiSkillProviderAuth.TOKEN);
        providers.get(0).setEncryptedSecret("demo-not-a-secret");
        AiSkillProvider gitea = new AiSkillProvider();
        gitea.setId("team-gitea");
        gitea.setName("Team Gitea");
        gitea.setType(AiSkillProviderType.HTTP);
        gitea.setBaseUrl("https://gitea.example.test/platform/skills/raw/branch/main");
        gitea.setAuth(AiSkillProviderAuth.BASIC);
        gitea.setUsername("demo");
        providers.add(gitea);
        settings.setAiSkillProviders(providers);

        settings.setAiSkills(List.of(
            local("bash-shell-quality", "Shell review rules", "linux, bash"),
            local("linux-sysadmin", "Operations conventions", "linux, ops"),
            external("pdf", "Work with PDF files: extract text, fill forms, merge pages.", AiSkillProvider.DEFAULT_GITHUB_ID,
                "https://github.com/anthropics/skills/tree/main/skills/pdf", "d3e046a5ae10", false),
            external("kubernetes-patterns", "Kubernetes workload patterns, probes, RBAC and autoscaling.",
                AiSkillProvider.DEFAULT_SKILLSMP_ID,
                "https://github.com/affaan-m/ECC/tree/main/skills/kubernetes-patterns", "7c1f92b04d3e", true),
            external("incident-runbook", "Team runbook for production incidents.", "team-gitea",
                "https://gitea.example.test/platform/skills/raw/branch/main/incident/SKILL.md", "5e0b7a61c2f4", false)));

        KorTTYApplication app = new KorTTYApplication();
        setField(app, "globalSettingsManager", manager);
        Field instance = KorTTYApplication.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, app);
        return app;
    }

    private static AiSkill local(String name, String description, String tags) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setDescription(description);
        skill.setTagsFromString(tags);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent("");
        return skill;
    }

    private static AiSkill external(String name, String description, String providerId, String reference,
                                    String revision, boolean enabled) {
        AiSkill skill = local(name, description, "");
        skill.setEnabled(enabled);
        AiSkillExternalSource source = new AiSkillExternalSource();
        source.setProviderId(providerId);
        source.setReference(reference);
        source.setSourceUrl(reference);
        source.setRevision(revision);
        source.setContentSha256("demo"); // reads as "edited locally" until writeExternalView fixes it up
        skill.setExternalSource(source);
        return skill;
    }

    private static void writeExternalView(KorTTYApplication app) throws Exception {
        AiManagerDialog dialog = new AiManagerDialog(null);
        prepare(dialog);
        dialog.show();
        TabPane tabPane = (TabPane) dialog.getDialogPane().lookup(".ai-manager-primary-navigation");
        for (Tab tab : tabPane.getTabs()) {
            if (I18n.get("settings.tab.aiSkills").equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
            }
        }
        AiSkillsPane pane = field(dialog, "aiSkillsPane");
        List<AiSkill> skills = field(pane, "aiSkills");
        // Every demo skill but the first external one reads as unedited.
        for (AiSkill skill : skills) {
            if (skill.isExternal() && !"pdf".equals(skill.getName())) {
                skill.getExternalSource().setContentSha256(sha256(skill.getContent()));
            }
        }
        AiSkill kubernetes = skills.stream().filter(s -> "kubernetes-patterns".equals(s.getName())).findFirst().orElseThrow();
        Map<String, ExternalAiSkillDocument> pending = field(pane, "pendingUpdates");
        pending.put(kubernetes.getId(), new ExternalAiSkillDocument(kubernetes.getExternalSource().getReference(),
            kubernetes.getExternalSource().getReference(), "9a8b7c6d5e4f", PDF_PREVIEW, "kubernetes-patterns"));
        ((ToggleButton) field(pane, "externalViewButton")).setSelected(true);
        ListView<AiSkill> list = field(pane, "aiSkillListView");
        list.getSelectionModel().clearSelection();
        list.getSelectionModel().select(kubernetes);
        snapshot(dialog.getDialogPane(), 1180, 820, EXTERNAL_VIEW);
        dialog.close();
    }

    private static void writeImportDialog(KorTTYApplication app) throws Exception {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        ExternalAiSkillImportDialog dialog = new ExternalAiSkillImportDialog(null, app,
            settings.getAiSkillProviders(), settings.getAiSkills());
        prepare(dialog);
        dialog.show();
        ComboBox<AiSkillProvider> providerCombo = field(dialog, "providerCombo");
        providerCombo.getSelectionModel().select(settings.findAiSkillProvider(AiSkillProvider.DEFAULT_SKILLSMP_ID));
        ((TextField) field(dialog, "queryField")).setText("kubernetes");
        setField(dialog, "client", de.kortty.core.ExternalAiSkillSupport.clientFor(
            providerCombo.getValue(), provider -> null, settings.getAiSkillProviders()));
        TableView<ExternalAiSkillCandidate> table = field(dialog, "resultTable");
        table.getItems().setAll(
            new ExternalAiSkillCandidate("https://github.com/affaan-m/ECC/tree/main/skills/kubernetes-patterns",
                "kubernetes-patterns", "Kubernetes workload patterns, probes, RBAC and autoscaling.", "affaan-m", "", 269367),
            new ExternalAiSkillCandidate("https://github.com/demo-org/k8s-skills/tree/main/helm-charts",
                "helm-charts", "Write and review Helm charts with values schemas.", "demo-org", "", 1840),
            new ExternalAiSkillCandidate("https://github.com/demo-org/k8s-skills/tree/main/kubectl-debug",
                "kubectl-debug", "Debug pods with kubectl: events, logs, ephemeral containers.", "demo-org", "", 1840),
            new ExternalAiSkillCandidate("https://github.com/demo-user/platform/tree/main/skills/argo-rollouts",
                "argo-rollouts", "Canary and blue-green rollouts with Argo Rollouts.", "demo-user", "", 312));
        table.getSelectionModel().selectIndices(1, 2);
        ((TextArea) field(dialog, "previewArea")).setText(PDF_PREVIEW.replace("kubernetes-patterns", "helm-charts"));
        ((Label) field(dialog, "statusLabel")).setText(I18n.get("settings.aiSkills.external.dialog.results", 4));
        snapshot(dialog.getDialogPane(), 1040, 700, IMPORT_DIALOG);
        dialog.close();
    }

    private static void writeProvidersDialog(KorTTYApplication app) throws Exception {
        AiSkillProvidersDialog dialog = new AiSkillProvidersDialog(null, app);
        prepare(dialog);
        dialog.show();
        TableView<AiSkillProvider> table = field(dialog, "table");
        table.getSelectionModel().select(0);
        snapshot(dialog.getDialogPane(), 860, 640, PROVIDERS_DIALOG);
        dialog.close();
    }

    private static void prepare(Dialog<?> dialog) {
        DialogThemeHelper.applyTheme(dialog);
        GlobalSettings defaults = new GlobalSettings();
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(ThemeCssSupport.resolveThemeColors(defaults, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
    }

    private static void snapshot(DialogPane pane, double width, double height, String output) throws Exception {
        pane.setMinSize(width, height);
        pane.setPrefSize(width, height);
        pane.setMaxSize(width, height);
        pane.applyCss();
        pane.resize(width, height);
        pane.layout();
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(params, null);
        File file = new File(output);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        System.out.println("Generated " + file.getAbsolutePath());
    }

    private static String sha256(String text) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest((text != null ? text : "").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
