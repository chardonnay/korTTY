package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.security.MasterPasswordManager;
import de.kortty.security.PasswordStrengthChecker;
import javafx.animation.FadeTransition;
import javafx.animation.FillTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.shape.Rectangle;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

/**
 * Dialog for setting up or entering the master password.
 */
public class MasterPasswordDialog {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterPasswordDialog.class);
    private static final double LOGIN_DIALOG_WIDTH = 1080;
    private static final double LOGIN_DIALOG_HEIGHT = 560;
    private static final double ELEGANT_LOGIN_DIALOG_WIDTH = 520;
    private static final double ELEGANT_LOGIN_DIALOG_HEIGHT = 320;
    private static final double SETUP_DIALOG_WIDTH = 780;
    private static final double SETUP_DIALOG_HEIGHT = 600;
    private static final double LOGIN_BRAND_PANEL_WIDTH = 675;
    private static final double SETUP_BRAND_PANEL_WIDTH = 220;
    private static final double TACTICAL_AUTH_PANEL_WIDTH = 104;
    private static final double TACTICAL_BRAND_PANEL_WIDTH = 130;
    private static final String ANIMATED_LOGO_RESOURCE = "/icon/kortty_logo_ai_pingpong.mp4";
    private static final String LOGO_RESOURCE = "/icon/kortty_logo.png";
    private static final String ICON_RESOURCE = "/icon/kortty_icon.png";
    private static final double LOGIN_LOGO_WIDTH = 650;
    private static final double ELEGANT_LOGIN_LOGO_WIDTH = 96;
    private static final double TACTICAL_LOGIN_LOGO_WIDTH = 108;
    private static final double SETUP_LOGO_WIDTH = 190;
    private static final double PASSWORD_STAR_LEFT_PADDING = 16;
    private static final Duration PASSWORD_STAR_GLOW_DURATION = Duration.millis(420);
    private static final Duration BUTTON_HOVER_GLOW_DURATION = Duration.millis(340);
    private static final Duration PASSWORD_FIELD_FRAME_FLASH_DURATION = Duration.millis(360);
    private static final String BUTTON_GLOW_TIMELINE_KEY = "kortty.buttonGlowTimeline";
    private static final String PASSWORD_FIELD_FLASH_TIMELINE_KEY = "kortty.passwordFieldFlashTimeline";
    
    private final Stage dialog;
    private final MasterPasswordManager passwordManager;
    private final boolean matrixTerminalDesign;
    private final boolean holographicInterfaceDesign;
    private final boolean klingonTacticalDesign;
    private final boolean elegantDarkDesign;
    private final boolean customAppDesign;
    private MediaPlayer animatedLogoPlayer;
    private boolean result = false;
    private CheckBox telemetryConsentCheck;
    
    public MasterPasswordDialog(Stage owner, MasterPasswordManager passwordManager) {
        this.passwordManager = passwordManager;
        this.matrixTerminalDesign = AppDesignStyleSupport.isMatrixTerminalActive();
        this.holographicInterfaceDesign = AppDesignStyleSupport.isHolographicInterfaceActive();
        this.klingonTacticalDesign = AppDesignStyleSupport.isKlingonTacticalActive();
        this.elegantDarkDesign = AppDesignStyleSupport.isElegantDarkActive();
        this.customAppDesign = AppDesignStyleSupport.isCustomAppDesignActive();
        boolean passwordSet = passwordManager.isPasswordSet();
        
        dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        // Don't set owner if it's not showing yet
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
        }
        dialog.initStyle(isElegantDarkDesign() && passwordSet ? StageStyle.UNDECORATED : StageStyle.UTILITY);
        dialog.setResizable(false);
        dialog.setOnCloseRequest(e -> {
            result = false;
        });
        dialog.setOnHidden(e -> disposeAnimatedLogoPlayer());
        
        if (passwordSet) {
            setupLoginDialog();
        } else {
            setupSetupDialog();
        }
    }
    
    private void setupLoginDialog() {
        dialog.setTitle(I18n.get("masterPassword.title"));

        if (isElegantDarkDesign()) {
            setupElegantLoginDialog();
            return;
        }
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(34, 38, 30, 38));
        root.setAlignment(Pos.CENTER);
        
        Label titleLabel = new Label(I18n.get("masterPassword.enter"));
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        styleFieldLabel(titleLabel);
        
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("masterPassword.password"));
        passwordField.setPrefWidth(250);
        stylePasswordField(passwordField);
        StackPane passwordStack = createAnimatedPasswordFieldPane(passwordField);
        
        Label errorLabel = new Label();
        styleErrorLabel(errorLabel);
        errorLabel.setVisible(false);
        
        Button loginButton = new Button(I18n.get("masterPassword.loginButton"));
        loginButton.setDefaultButton(true);
        loginButton.setPrefWidth(100);
        stylePrimaryButton(loginButton);
        installButtonHoverGlow(loginButton);
        
        Button cancelButton = new Button(I18n.get("dialog.cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setPrefWidth(100);
        styleSecondaryButton(cancelButton);
        installButtonHoverGlow(cancelButton);
        
        HBox buttonBox = new HBox(10, loginButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER);
        
        root.getChildren().addAll(titleLabel, passwordStack, errorLabel, buttonBox);

        installLoginHandlers(passwordField, errorLabel, loginButton, cancelButton);

        Scene scene = createBrandedScene(root, LOGIN_DIALOG_WIDTH, LOGIN_DIALOG_HEIGHT);
        dialog.setScene(scene);
        dialog.setMinWidth(LOGIN_DIALOG_WIDTH);
        dialog.setMinHeight(LOGIN_DIALOG_HEIGHT);
    }

    private void setupElegantLoginDialog() {
        VBox shell = new VBox();
        shell.setPrefSize(ELEGANT_LOGIN_DIALOG_WIDTH, ELEGANT_LOGIN_DIALOG_HEIGHT);
        shell.setMinSize(ELEGANT_LOGIN_DIALOG_WIDTH, ELEGANT_LOGIN_DIALOG_HEIGHT);
        shell.getStyleClass().addAll("master-password-root", "elegant-root");
        applyElegantWindowShadow(shell);

        HBox titleBar = createElegantTitlebar();
        installUndecoratedDragSupport(titleBar);

        HBox body = new HBox(34);
        body.getStyleClass().add("elegant-body");
        body.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(14);
        form.setAlignment(Pos.CENTER_LEFT);
        form.setPrefWidth(318);
        HBox.setHgrow(form, Priority.ALWAYS);

        Label titleLabel = new Label(I18n.get("masterPassword.enter"));
        styleFieldLabel(titleLabel);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("masterPassword.password"));
        passwordField.setPrefWidth(318);
        passwordField.setMaxWidth(Double.MAX_VALUE);
        stylePasswordField(passwordField);

        Label eyeIcon = new Label("o");
        eyeIcon.getStyleClass().add("elegant-eye-icon");
        eyeIcon.setMouseTransparent(true);
        StackPane passwordStack = createAnimatedPasswordFieldPane(passwordField, eyeIcon);
        StackPane.setAlignment(eyeIcon, Pos.CENTER_RIGHT);
        passwordStack.setMaxWidth(Double.MAX_VALUE);

        Label errorLabel = new Label();
        styleErrorLabel(errorLabel);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.visibleProperty().addListener((obs, wasVisible, isVisible) -> errorLabel.setManaged(isVisible));

        Button loginButton = new Button(I18n.get("masterPassword.loginButton"));
        loginButton.setDefaultButton(true);
        loginButton.setPrefWidth(156);
        stylePrimaryButton(loginButton);
        installButtonHoverGlow(loginButton);

        Button cancelButton = new Button(I18n.get("dialog.cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setPrefWidth(156);
        styleSecondaryButton(cancelButton);
        installButtonHoverGlow(cancelButton);

        HBox buttonBox = new HBox(14, loginButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        form.getChildren().addAll(titleLabel, passwordStack, errorLabel, buttonBox);
        body.getChildren().addAll(form, createElegantBrandBox());

        HBox footer = createElegantFooter();
        VBox.setVgrow(body, Priority.ALWAYS);
        shell.getChildren().addAll(titleBar, body, footer);

        installLoginHandlers(passwordField, errorLabel, loginButton, cancelButton);

        shell.setOpacity(0);
        Scene scene = new Scene(shell, ELEGANT_LOGIN_DIALOG_WIDTH, ELEGANT_LOGIN_DIALOG_HEIGHT);
        AppDesignStyleSupport.applyToScene(scene);
        dialog.setScene(scene);
        dialog.setMinWidth(ELEGANT_LOGIN_DIALOG_WIDTH);
        dialog.setMinHeight(ELEGANT_LOGIN_DIALOG_HEIGHT);
        dialog.setOnShown(e -> {
            passwordField.requestFocus();
            FadeTransition fade = new FadeTransition(Duration.millis(200), shell);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.setInterpolator(Interpolator.EASE_OUT);
            fade.play();
        });
    }

    private HBox createElegantTitlebar() {
        HBox titleBar = new HBox(10);
        titleBar.getStyleClass().add("elegant-titlebar");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setMinHeight(38);
        titleBar.setPrefHeight(38);

        HBox windowDots = new HBox(8);
        windowDots.setAlignment(Pos.CENTER_LEFT);
        windowDots.setMinWidth(52);
        windowDots.setPrefWidth(52);
        windowDots.getChildren().addAll(
            createElegantWindowDot("elegant-dot-muted"),
            createElegantWindowDot("elegant-dot-accent"),
            createElegantWindowDot("elegant-dot-success")
        );

        HBox leftCluster = new HBox(12, windowDots, createLoginWindowVersionLabel());
        leftCluster.setAlignment(Pos.CENTER_LEFT);
        leftCluster.setMinWidth(168);
        leftCluster.setPrefWidth(168);

        Label title = new Label(I18n.get("masterPassword.title"));
        title.getStyleClass().add("elegant-window-title");
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        Region rightPad = new Region();
        rightPad.setMinWidth(168);
        rightPad.setPrefWidth(168);

        titleBar.getChildren().addAll(leftCluster, title, rightPad);
        return titleBar;
    }

    private Region createElegantWindowDot(String styleClass) {
        Region dot = new Region();
        dot.getStyleClass().addAll("elegant-window-dot", styleClass);
        return dot;
    }

    private VBox createElegantBrandBox() {
        VBox wrapper = new VBox(12);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setMinWidth(112);
        wrapper.setPrefWidth(112);
        wrapper.setMaxWidth(112);

        VBox logoPanel = new VBox(6);
        logoPanel.getStyleClass().add("logo-panel");
        logoPanel.setAlignment(Pos.CENTER);
        logoPanel.setMinSize(108, 96);
        logoPanel.setPrefSize(108, 96);

        Node logoNode = createLoginLogoNode(ELEGANT_LOGIN_LOGO_WIDTH);
        if (logoNode != null) {
            logoPanel.getChildren().add(logoNode);
        } else {
            Label name = new Label("KorTTY");
            name.getStyleClass().add("elegant-brand-name");
            logoPanel.getChildren().add(name);
        }

        wrapper.getChildren().add(logoPanel);
        return wrapper;
    }

    private HBox createElegantFooter() {
        HBox footer = new HBox(10);
        footer.getStyleClass().add("footer-bar");
        footer.setAlignment(Pos.CENTER_LEFT);

        Region statusDot = new Region();
        statusDot.getStyleClass().add("elegant-status-dot");
        Label status = new Label("SECURE CONNECTION");
        status.getStyleClass().add("footer-text");
        HBox statusBox = new HBox(8, statusDot, status);
        statusBox.setAlignment(Pos.CENTER_LEFT);

        Label cipher = new Label("AES-256 • RSA-4096");
        cipher.getStyleClass().add("footer-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        footer.getChildren().addAll(statusBox, spacer, cipher);
        return footer;
    }

    private void installLoginHandlers(PasswordField passwordField, Label errorLabel, Button loginButton, Button cancelButton) {
        loginButton.setOnAction(e -> {
            String password = passwordField.getText();
            if (password.isEmpty()) {
                errorLabel.setText(I18n.get("masterPassword.error.empty"));
                errorLabel.setVisible(true);
                return;
            }

            try {
                if (passwordManager.verifyPassword(password.toCharArray())) {
                    result = true;
                    dialog.close();
                } else {
                    errorLabel.setText(I18n.get("masterPassword.error.wrong"));
                    errorLabel.setVisible(true);
                    passwordField.clear();
                    passwordField.requestFocus();
                }
            } catch (Exception ex) {
                errorLabel.setText(I18n.get("error.title") + ": " + ex.getMessage());
                errorLabel.setVisible(true);
            }
        });

        cancelButton.setOnAction(e -> {
            result = false;
            dialog.close();
        });
    }

    private StackPane createAnimatedPasswordFieldPane(PasswordField passwordField) {
        return createAnimatedPasswordFieldPane(passwordField, null);
    }

    private StackPane createAnimatedPasswordFieldPane(PasswordField passwordField, Node trailingNode) {
        makePasswordTextTransparent(passwordField);

        HBox starBox = new HBox(2);
        starBox.setAlignment(Pos.CENTER_LEFT);
        starBox.setMouseTransparent(true);
        starBox.setLayoutX(PASSWORD_STAR_LEFT_PADDING);

        Pane starLayer = new Pane(starBox);
        starLayer.setMouseTransparent(true);
        starLayer.minWidthProperty().bind(passwordField.widthProperty());
        starLayer.prefWidthProperty().bind(passwordField.widthProperty());
        starLayer.maxWidthProperty().bind(passwordField.widthProperty());
        starLayer.minHeightProperty().bind(passwordField.heightProperty());
        starLayer.prefHeightProperty().bind(passwordField.heightProperty());
        starLayer.maxHeightProperty().bind(passwordField.heightProperty());
        starBox.layoutYProperty().bind(starLayer.heightProperty().subtract(starBox.heightProperty()).divide(2).add(1));

        StackPane passwordStack = new StackPane(passwordField, starLayer);
        if (trailingNode != null) {
            passwordStack.getChildren().add(trailingNode);
        }
        installPasswordFieldFrameFlash(passwordStack);

        passwordField.textProperty().addListener((obs, oldValue, newValue) ->
            updateAnimatedPasswordStars(starBox, oldValue, newValue));
        updateAnimatedPasswordStars(starBox, "", passwordField.getText());

        return passwordStack;
    }

    private void makePasswordTextTransparent(PasswordField passwordField) {
        String currentStyle = passwordField.getStyle();
        if (currentStyle == null) {
            currentStyle = "";
        }
        String separator = currentStyle.isBlank() || currentStyle.endsWith(";") ? "" : ";";
        passwordField.setStyle(currentStyle
            + separator
            + "-fx-text-fill: transparent;"
            + "-fx-highlight-text-fill: transparent;");
    }

    private void updateAnimatedPasswordStars(HBox starBox, String oldValue, String newValue) {
        int oldLength = oldValue == null ? 0 : oldValue.length();
        int newLength = newValue == null ? 0 : newValue.length();

        starBox.getChildren().clear();
        for (int i = 0; i < newLength; i++) {
            starBox.getChildren().add(createPasswordStarText());
        }

        if (newLength <= oldLength) {
            return;
        }

        for (int i = oldLength; i < newLength; i++) {
            animatePasswordStar((Text) starBox.getChildren().get(i));
        }
    }

    private Text createPasswordStarText() {
        Text star = new Text("*");
        star.setMouseTransparent(true);
        star.setFill(getPasswordStarColor());
        star.setStyle("-fx-font-family: Monospaced; -fx-font-size: 19px; -fx-font-weight: bold;");
        return star;
    }

    private void animatePasswordStar(Text star) {
        Color normalColor = getPasswordStarColor();
        Color glowColor = getPasswordStarGlowColor();
        star.setFill(glowColor);
        star.setEffect(new DropShadow(BlurType.GAUSSIAN, glowColor, 14, 0.55, 0, 0));

        FillTransition transition = new FillTransition(PASSWORD_STAR_GLOW_DURATION, star, glowColor, normalColor);
        transition.setInterpolator(Interpolator.EASE_OUT);
        transition.setOnFinished(event -> {
            star.setFill(normalColor);
            star.setEffect(null);
        });
        transition.play();
    }

    private Color getPasswordStarColor() {
        if (isElegantDarkDesign()) {
            return Color.web("#e2e4e8");
        }
        if (isHolographicInterfaceDesign()) {
            return Color.web("#00d4ff");
        }
        if (isKlingonTacticalDesign()) {
            return Color.web("#ffb4b4", 0.86);
        }
        if (isMatrixTerminalDesign()) {
            return Color.web("#00ff88");
        }
        if (isCustomAppDesign()) {
            return Color.web("#d8e3f0");
        }
        return Color.web("#000000");
    }

    private Color getPasswordStarGlowColor() {
        if (isElegantDarkDesign()) {
            return Color.web("#c8a96e");
        }
        if (isKlingonTacticalDesign()) {
            return Color.web("#ffd200");
        }
        return Color.web("#ffffff");
    }

    private void installButtonHoverGlow(Button button) {
        button.setOnMouseEntered(event -> playButtonHoverGlow(button));
        button.setOnMouseExited(event -> clearButtonHoverGlow(button));
    }

    private void playButtonHoverGlow(Button button) {
        clearButtonHoverGlow(button);

        Color glowColor = Color.web("#9fe7ff");
        DropShadow glow = new DropShadow(BlurType.GAUSSIAN, glowColor, 8, 0.18, 0, 0);
        button.setEffect(glow);

        Timeline timeline = new Timeline(
            new KeyFrame(Duration.ZERO, event -> {
                glow.setRadius(8);
                glow.setSpread(0.18);
            }),
            new KeyFrame(BUTTON_HOVER_GLOW_DURATION.divide(2), event -> {
                glow.setRadius(22);
                glow.setSpread(0.58);
            }),
            new KeyFrame(BUTTON_HOVER_GLOW_DURATION, event -> {
                glow.setRadius(10);
                glow.setSpread(0.14);
            })
        );
        timeline.setOnFinished(event -> {
            if (button.getProperties().get(BUTTON_GLOW_TIMELINE_KEY) == timeline) {
                button.getProperties().remove(BUTTON_GLOW_TIMELINE_KEY);
                button.setEffect(null);
            }
        });
        button.getProperties().put(BUTTON_GLOW_TIMELINE_KEY, timeline);
        timeline.play();
    }

    private void clearButtonHoverGlow(Button button) {
        Object existingTimeline = button.getProperties().remove(BUTTON_GLOW_TIMELINE_KEY);
        if (existingTimeline instanceof Timeline timeline) {
            timeline.stop();
        }
        button.setEffect(null);
    }

    private void installPasswordFieldFrameFlash(StackPane passwordStack) {
        passwordStack.setStyle(createPasswordFieldFrameStyle("transparent"));
        passwordStack.setOnMouseEntered(event -> playPasswordFieldFrameFlash(passwordStack));
        passwordStack.setOnMouseExited(event -> clearPasswordFieldFrameFlash(passwordStack));
    }

    private void playPasswordFieldFrameFlash(StackPane passwordStack) {
        clearPasswordFieldFrameFlash(passwordStack);

        Color glowColor = Color.web("#9fe7ff");
        DropShadow glow = new DropShadow(BlurType.GAUSSIAN, glowColor, 8, 0.18, 0, 0);
        passwordStack.setEffect(glow);

        Timeline timeline = new Timeline(
            new KeyFrame(Duration.ZERO, event -> {
                passwordStack.setStyle(createPasswordFieldFrameStyle("#6fcfff"));
                glow.setRadius(8);
                glow.setSpread(0.18);
            }),
            new KeyFrame(PASSWORD_FIELD_FRAME_FLASH_DURATION.divide(2), event -> {
                passwordStack.setStyle(createPasswordFieldFrameStyle("#baf4ff"));
                glow.setRadius(18);
                glow.setSpread(0.5);
            }),
            new KeyFrame(PASSWORD_FIELD_FRAME_FLASH_DURATION, event -> {
                passwordStack.setStyle(createPasswordFieldFrameStyle("transparent"));
                glow.setRadius(8);
                glow.setSpread(0.12);
            })
        );
        timeline.setOnFinished(event -> {
            if (passwordStack.getProperties().get(PASSWORD_FIELD_FLASH_TIMELINE_KEY) == timeline) {
                passwordStack.getProperties().remove(PASSWORD_FIELD_FLASH_TIMELINE_KEY);
                passwordStack.setStyle(createPasswordFieldFrameStyle("transparent"));
                passwordStack.setEffect(null);
            }
        });
        passwordStack.getProperties().put(PASSWORD_FIELD_FLASH_TIMELINE_KEY, timeline);
        timeline.play();
    }

    private void clearPasswordFieldFrameFlash(StackPane passwordStack) {
        Object existingTimeline = passwordStack.getProperties().remove(PASSWORD_FIELD_FLASH_TIMELINE_KEY);
        if (existingTimeline instanceof Timeline timeline) {
            timeline.stop();
        }
        passwordStack.setStyle(createPasswordFieldFrameStyle("transparent"));
        passwordStack.setEffect(null);
    }

    private String createPasswordFieldFrameStyle(String borderColor) {
        String radius = isKlingonTacticalDesign() ? "0" : "8";
        return "-fx-border-color: " + borderColor + ";"
            + "-fx-border-width: 2;"
            + "-fx-border-radius: " + radius + ";"
            + "-fx-background-radius: " + radius + ";";
    }

    private void installUndecoratedDragSupport(Region titleBar) {
        double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(event -> {
            dragOffset[0] = event.getSceneX();
            dragOffset[1] = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            dialog.setX(event.getScreenX() - dragOffset[0]);
            dialog.setY(event.getScreenY() - dragOffset[1]);
        });
    }

    private void applyElegantWindowShadow(Region root) {
        DropShadow depth = new DropShadow(BlurType.GAUSSIAN, Color.web("#000000AA"), 60, 0, 0, 20);
        DropShadow rim = new DropShadow(BlurType.GAUSSIAN, Color.web("#FFFFFF08"), 1, 1, 0, -1);
        rim.setInput(depth);
        root.setEffect(rim);
    }

    private void setupSetupDialog() {
        dialog.setTitle(I18n.get("masterPassword.setup"));
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(34, 38, 30, 38));
        root.setAlignment(Pos.CENTER);
        
        Label titleLabel = new Label(I18n.get("masterPassword.setup"));
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        styleFieldLabel(titleLabel);
        
        Label infoLabel = new Label(I18n.get("masterPassword.setup.info"));
        styleFieldLabel(infoLabel);
        if (!isCustomAppDesign()) {
            infoLabel.setStyle("-fx-text-fill: #666;");
        }
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);
        
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("masterPassword.password"));
        passwordField.setPrefWidth(200);
        stylePasswordField(passwordField);
        updatePasswordFieldLengthStyle(passwordField, 0);
        
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText(I18n.get("masterPassword.confirm"));
        confirmField.setPrefWidth(200);
        stylePasswordField(confirmField);
        
        Label passwordLabel = new Label(I18n.get("masterPassword.password"));
        styleFieldLabel(passwordLabel);
        Label confirmLabel = new Label(I18n.get("masterPassword.confirm"));
        styleFieldLabel(confirmLabel);

        grid.add(passwordLabel, 0, 0);
        grid.add(passwordField, 1, 0);
        grid.add(confirmLabel, 0, 1);
        grid.add(confirmField, 1, 1);
        
        Label errorLabel = new Label();
        styleErrorLabel(errorLabel);
        errorLabel.setVisible(false);
        
        // Length-based color feedback and strength indicator
        ProgressBar strengthBar = new ProgressBar(0);
        strengthBar.setPrefWidth(200);
        Label strengthLabel = new Label(I18n.get("masterPassword.strength"));
        styleFieldLabel(strengthLabel);
        
        passwordField.textProperty().addListener((obs, old, newVal) -> {
            int len = newVal != null ? newVal.length() : 0;
            updatePasswordFieldLengthStyle(passwordField, len);
            double strength = calculatePasswordStrength(newVal);
            strengthBar.setProgress(strength);
            if (isCustomAppDesign()) {
                strengthBar.setStyle(null);
            } else {
                if (strength < 0.3) {
                    strengthBar.setStyle("-fx-accent: red;");
                } else if (strength < 0.6) {
                    strengthBar.setStyle("-fx-accent: orange;");
                } else {
                    strengthBar.setStyle("-fx-accent: green;");
                }
            }
        });
        
        Button setupButton = new Button(I18n.get("masterPassword.setupButton"));
        setupButton.setDefaultButton(true);
        setupButton.setPrefWidth(100);
        stylePrimaryButton(setupButton);
        
        Button cancelButton = new Button(I18n.get("dialog.cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setPrefWidth(100);
        styleSecondaryButton(cancelButton);
        
        HBox buttonBox = new HBox(10, setupButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER);
        
        HBox strengthBox = new HBox(10, strengthLabel, strengthBar);
        strengthBox.setAlignment(Pos.CENTER);

        // Anonymous usage statistics: opt-in consent asked together with the password setup.
        telemetryConsentCheck = new CheckBox(I18n.get("masterPassword.telemetry.consent"));
        telemetryConsentCheck.setWrapText(true);
        telemetryConsentCheck.setSelected(false);
        telemetryConsentCheck.getStyleClass().add("field-label");
        if (!isCustomAppDesign()) {
            telemetryConsentCheck.setStyle("-fx-text-fill: #f1f5fb;");
        }
        Button telemetryInfoButton = new Button("?");
        styleSecondaryButton(telemetryInfoButton);
        telemetryInfoButton.setMinWidth(Region.USE_PREF_SIZE);
        telemetryInfoButton.setPrefWidth(36);
        telemetryInfoButton.setFocusTraversable(false);
        telemetryInfoButton.setTooltip(new Tooltip(I18n.get("masterPassword.telemetry.info.tooltip")));
        telemetryInfoButton.setOnAction(e -> {
            try {
                // Owner must be this APPLICATION_MODAL dialog, or the guide window would be frozen.
                GuideViewer.show(KorTTYApplication.getInstance(), dialog, "about/anonymous-data.html");
            } catch (RuntimeException ex) {
                logger.warn("Could not open the guide chapter on anonymous data", ex);
            }
        });
        Label telemetryDetailsLabel = new Label(I18n.get("masterPassword.telemetry.details"));
        telemetryDetailsLabel.setWrapText(true);
        telemetryDetailsLabel.getStyleClass().add("field-label");
        if (!isCustomAppDesign()) {
            telemetryDetailsLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        }
        HBox telemetryConsentRow = new HBox(8, telemetryConsentCheck, telemetryInfoButton);
        telemetryConsentRow.setAlignment(Pos.CENTER_LEFT);
        VBox telemetryConsentBox = new VBox(4, telemetryConsentRow, telemetryDetailsLabel);
        telemetryConsentBox.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(titleLabel, infoLabel, grid, strengthBox, telemetryConsentBox, errorLabel, buttonBox);
        
        setupButton.setOnAction(e -> {
            String password = passwordField.getText();
            String confirm = confirmField.getText();
            
            if (password.isEmpty()) {
                errorLabel.setText(I18n.get("masterPassword.error.empty"));
                errorLabel.setVisible(true);
                return;
            }
            
            if (password.length() < PasswordStrengthChecker.MIN_LENGTH) {
                errorLabel.setText(I18n.get("masterPassword.error.weak"));
                errorLabel.setVisible(true);
                return;
            }
            
            if (!password.equals(confirm)) {
                errorLabel.setText(I18n.get("masterPassword.error.mismatch"));
                errorLabel.setVisible(true);
                return;
            }
            
            if (PasswordStrengthChecker.isWeak(password)) {
                Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
                warn.setTitle(I18n.get("masterPassword.weakWarning.title"));
                warn.setHeaderText(I18n.get("masterPassword.weakWarning.header"));
                warn.setContentText(I18n.get("masterPassword.weakWarning.content"));
                ButtonType useAnyway = new ButtonType(I18n.get("masterPassword.useAnyway"), ButtonBar.ButtonData.OK_DONE);
                warn.getButtonTypes().setAll(useAnyway, ButtonType.CANCEL);
                DialogThemeHelper.applyTheme(warn);
                if (warn.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) {
                    return;
                }
            }
            
            try {
                logger.info("Setting up master password...");
                passwordManager.setupPassword(password.toCharArray());
                logger.info("Master password setup successful");
                result = true;
                dialog.close();
            } catch (Exception ex) {
                logger.error("Failed to setup master password", ex);
                errorLabel.setText(I18n.get("error.title") + ": " + ex.getMessage());
                errorLabel.setVisible(true);
            }
        });
        
        cancelButton.setOnAction(e -> {
            result = false;
            dialog.close();
        });
        
        Scene scene = createBrandedScene(root, SETUP_DIALOG_WIDTH, SETUP_DIALOG_HEIGHT);
        dialog.setScene(scene);
        dialog.setMinWidth(SETUP_DIALOG_WIDTH);
        dialog.setMinHeight(SETUP_DIALOG_HEIGHT);
    }

    private Scene createBrandedScene(VBox content, double width, double height) {
        if (isKlingonTacticalDesign()) {
            return createTacticalScene(content, width, height);
        }

        boolean loginScene = width == LOGIN_DIALOG_WIDTH && height == LOGIN_DIALOG_HEIGHT;
        if (loginScene) {
            // Login window: the animated logo fills the whole window full-bleed. Only the setup
            // dialog (loginScene == false) keeps the split form / brand-panel layout below.
            return createFullBleedLoginScene(content, width, height);
        }
        double brandPanelWidth = loginScene ? LOGIN_BRAND_PANEL_WIDTH : SETUP_BRAND_PANEL_WIDTH;
        double logoWidth = loginScene ? LOGIN_LOGO_WIDTH : SETUP_LOGO_WIDTH;

        HBox brandedRoot = new HBox(24);
        brandedRoot.setPrefSize(width, height);
        brandedRoot.setMinSize(width, height);
        brandedRoot.setAlignment(Pos.CENTER);
        brandedRoot.setPadding(new Insets(0, 34, 0, 16));
        brandedRoot.getStyleClass().add("master-password-root");
        if (isMatrixTerminalDesign()) {
            brandedRoot.getStyleClass().add("terminal-root");
        } else if (isHolographicInterfaceDesign()) {
            brandedRoot.getStyleClass().add("holo-root");
        } else if (!isCustomAppDesign()) {
            brandedRoot.setStyle("-fx-background-color: #000000;");
        }
        content.setMaxWidth(width - brandPanelWidth - 72);
        VBox brandBox = createBrandBox(brandPanelWidth, logoWidth, loginScene);
        brandedRoot.getChildren().addAll(content, brandBox);
        Scene scene = loginScene
            ? createLoginSceneWithTopLeftVersion(brandedRoot, width, height)
            : new Scene(brandedRoot, width, height);
        AppDesignStyleSupport.applyToScene(scene);
        return scene;
    }

    /**
     * Login scene where the animated logo video fills the entire window as a full-bleed background.
     * The password form is overlaid in a translucent card on the left, so the logo stays visible on
     * the right. The video is scaled to cover the window (preserving aspect ratio) and the shell is
     * clipped to the window bounds so the overflow is cropped instead of letterboxed.
     */
    private Scene createFullBleedLoginScene(VBox content, double width, double height) {
        StackPane shell = new StackPane();
        shell.setPrefSize(width, height);
        shell.setMinSize(width, height);
        shell.setMaxSize(width, height);
        shell.getStyleClass().add("master-password-root");
        if (!isCustomAppDesign()) {
            shell.setStyle("-fx-background-color: #000000;");
        }

        // Full-bleed animated logo background (covers the window; vertical overflow is clipped).
        Node logo = createLoginLogoNode(width);
        if (logo != null) {
            logo.setMouseTransparent(true);
            shell.getChildren().add(logo);
        }

        // Foreground: the existing password form, capped to a compact card and given a translucent
        // backdrop for legibility, aligned to the left so the looping logo remains visible.
        content.setMaxWidth(360);
        content.setMaxHeight(Region.USE_PREF_SIZE);
        content.setStyle(
            "-fx-background-color: rgba(3, 12, 14, 0.62);"
                + " -fx-background-radius: 16;"
                + " -fx-border-color: rgba(120, 220, 220, 0.18);"
                + " -fx-border-width: 1;"
                + " -fx-border-radius: 16;");
        StackPane.setAlignment(content, Pos.CENTER_LEFT);
        StackPane.setMargin(content, new Insets(0, 0, 0, 56));
        shell.getChildren().add(content);

        Label versionLabel = createLoginWindowVersionLabel();
        StackPane.setAlignment(versionLabel, Pos.TOP_LEFT);
        StackPane.setMargin(versionLabel, new Insets(14, 0, 0, 18));
        shell.getChildren().add(versionLabel);

        // Crop the covering video to the window instead of letting it spill past the bounds.
        shell.setClip(new Rectangle(width, height));

        Scene scene = new Scene(shell, width, height);
        AppDesignStyleSupport.applyToScene(scene);
        return scene;
    }

    private Scene createLoginSceneWithTopLeftVersion(Node content, double width, double height) {
        StackPane shell = new StackPane(content);
        shell.setPrefSize(width, height);
        shell.setMinSize(width, height);

        Label versionLabel = createLoginWindowVersionLabel();
        StackPane.setAlignment(versionLabel, Pos.TOP_LEFT);
        StackPane.setMargin(versionLabel, new Insets(14, 0, 0, 18));
        shell.getChildren().add(versionLabel);

        return new Scene(shell, width, height);
    }

    private Scene createTacticalScene(VBox content, double width, double height) {
        boolean loginScene = width == LOGIN_DIALOG_WIDTH && height == LOGIN_DIALOG_HEIGHT;
        VBox shell = new VBox();
        shell.setPrefSize(width, height);
        shell.setMinSize(width, height);
        shell.getStyleClass().addAll("master-password-root", "tactical-root");

        Region accentBar = new Region();
        accentBar.getStyleClass().add("accent-bar");
        accentBar.setMinHeight(2);
        accentBar.setPrefHeight(2);
        accentBar.setMaxHeight(2);

        HBox header = createTacticalHeader(loginScene);
        HBox body = createTacticalBody(content, width, loginScene);
        HBox footer = createTacticalFooter();
        VBox.setVgrow(body, Priority.ALWAYS);

        shell.getChildren().addAll(accentBar, header, body, footer);
        Scene scene = new Scene(shell, width, height);
        AppDesignStyleSupport.applyToScene(scene);
        return scene;
    }

    private HBox createTacticalHeader(boolean loginScene) {
        HBox header = new HBox(14);
        header.getStyleClass().add("tac-titlebar");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMinHeight(44);
        header.setPrefHeight(44);

        HBox statusBlocks = new HBox(7);
        statusBlocks.getStyleClass().add("tactical-window-blocks");
        statusBlocks.setAlignment(Pos.CENTER_LEFT);
        statusBlocks.setMinWidth(128);
        statusBlocks.setPrefWidth(128);
        statusBlocks.getChildren().addAll(
            createTacticalStatusBlock("wb-red"),
            createTacticalStatusBlock("wb-amber"),
            createTacticalStatusBlock("wb-gold")
        );

        HBox leftCluster = new HBox(12);
        leftCluster.setAlignment(Pos.CENTER_LEFT);
        leftCluster.setMinWidth(loginScene ? 196 : 128);
        leftCluster.setPrefWidth(loginScene ? 196 : 128);
        leftCluster.getChildren().add(statusBlocks);
        if (loginScene) {
            leftCluster.getChildren().add(createLoginWindowVersionLabel());
        }

        Label title = new Label("MASTER PASSWORD");
        title.getStyleClass().addAll("header-tag", "tactical-header-title");
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        Region rightPad = new Region();
        rightPad.setMinWidth(loginScene ? 196 : 128);
        rightPad.setPrefWidth(loginScene ? 196 : 128);

        header.getChildren().addAll(leftCluster, title, rightPad);
        return header;
    }

    private Region createTacticalStatusBlock(String styleClass) {
        Region block = new Region();
        block.getStyleClass().add(styleClass);
        return block;
    }

    private HBox createTacticalBody(VBox content, double width, boolean loginScene) {
        HBox body = new HBox(24);
        body.getStyleClass().add("tactical-body");
        body.setAlignment(Pos.CENTER);
        body.setPadding(new Insets(22, 30, 20, 34));

        double contentWidth = width - TACTICAL_AUTH_PANEL_WIDTH - TACTICAL_BRAND_PANEL_WIDTH - 112;
        content.setPrefWidth(Math.max(300, contentWidth));
        content.setMaxWidth(Math.max(300, contentWidth));
        content.setPadding(new Insets(8, 0, 8, 0));
        content.setAlignment(Pos.CENTER_LEFT);
        content.getStyleClass().add("tactical-form");
        HBox.setHgrow(content, Priority.ALWAYS);

        body.getChildren().addAll(content, createTacticalAuthPanel(), createTacticalBrandBox(loginScene));
        return body;
    }

    private VBox createTacticalAuthPanel() {
        VBox authPanel = new VBox(6);
        authPanel.getStyleClass().add("tactical-auth-panel");
        authPanel.setAlignment(Pos.TOP_CENTER);
        authPanel.setMinWidth(TACTICAL_AUTH_PANEL_WIDTH);
        authPanel.setPrefWidth(TACTICAL_AUTH_PANEL_WIDTH);
        authPanel.setMaxWidth(TACTICAL_AUTH_PANEL_WIDTH);

        HBox tagRow = new HBox(8);
        tagRow.setAlignment(Pos.CENTER);
        Region tagLine = new Region();
        tagLine.getStyleClass().add("tag-line");
        HBox.setHgrow(tagLine, Priority.ALWAYS);
        Label authLabel = new Label("AUTH REQUIRED");
        authLabel.getStyleClass().add("header-tag");
        tagRow.getChildren().addAll(tagLine, authLabel);

        authPanel.getChildren().add(tagRow);
        return authPanel;
    }

    private VBox createTacticalBrandBox(boolean loginScene) {
        VBox wrapper = new VBox(8);
        wrapper.setAlignment(Pos.TOP_CENTER);
        wrapper.setMinWidth(TACTICAL_BRAND_PANEL_WIDTH);
        wrapper.setPrefWidth(TACTICAL_BRAND_PANEL_WIDTH);
        wrapper.setMaxWidth(TACTICAL_BRAND_PANEL_WIDTH);

        VBox logoPanel = new VBox(4);
        logoPanel.getStyleClass().add("logo-panel");
        logoPanel.setAlignment(Pos.CENTER);
        logoPanel.setMinSize(124, 92);
        logoPanel.setPrefSize(124, 92);

        Label mark = new Label(">");
        mark.getStyleClass().add("tactical-brand-mark");
        Label name = new Label("KorTTY");
        name.getStyleClass().add("tactical-brand-name");
        Label channel = new Label("SECURE");
        channel.getStyleClass().add("header-tag");

        Node logoNode = loginScene ? createLoginLogoNode(TACTICAL_LOGIN_LOGO_WIDTH) : createLogoView(96);
        if (logoNode != null) {
            logoPanel.getChildren().add(logoNode);
        } else {
            logoPanel.getChildren().addAll(mark, name);
        }
        logoPanel.getChildren().add(channel);
        wrapper.getChildren().add(logoPanel);
        if (!loginScene) {
            wrapper.getChildren().add(createVersionLabel());
        }
        return wrapper;
    }

    private HBox createTacticalFooter() {
        HBox footer = new HBox();
        footer.getStyleClass().add("tactical-footer");
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setMinHeight(30);
        footer.setPrefHeight(30);

        Label ready = new Label("TACTICAL SYSTEM READY");
        ready.getStyleClass().add("header-tag");
        Label secure = new Label("SECURE CHANNEL");
        secure.getStyleClass().add("header-tag");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        footer.getChildren().addAll(ready, spacer, secure);
        return footer;
    }

    private VBox createBrandBox(double brandPanelWidth, double logoWidth, boolean loginScene) {
        VBox brandBox = new VBox(12);
        brandBox.setAlignment(Pos.CENTER);
        brandBox.setMinWidth(brandPanelWidth);
        brandBox.setPrefWidth(brandPanelWidth);
        brandBox.setMaxWidth(brandPanelWidth);
        brandBox.getStyleClass().add("logo-area");
        if (isHolographicInterfaceDesign()) {
            brandBox.getStyleClass().add("logo-hex");
        }

        Node logoView = loginScene ? createLoginLogoNode(logoWidth) : createLogoView(logoWidth);
        if (logoView != null) {
            brandBox.getChildren().add(logoView);
        }
        if (!loginScene) {
            brandBox.getChildren().add(createVersionLabel());
        }
        return brandBox;
    }

    private ImageView createLogoView(double fitWidth) {
        var logoUrl = getClass().getResource(LOGO_RESOURCE);
        if (logoUrl == null) {
            logoUrl = getClass().getResource(ICON_RESOURCE);
        }
        if (logoUrl == null) {
            return null;
        }
        ImageView logoView = new ImageView(new Image(logoUrl.toExternalForm()));
        logoView.setFitWidth(fitWidth);
        logoView.setPreserveRatio(true);
        logoView.setSmooth(true);
        logoView.setMouseTransparent(true);
        return logoView;
    }

    private Node createLoginLogoNode(double fitWidth) {
        var logoUrl = getClass().getResource(ANIMATED_LOGO_RESOURCE);
        if (logoUrl == null) {
            logger.warn("Animated login logo resource not found: {}", ANIMATED_LOGO_RESOURCE);
            return createLogoView(fitWidth);
        }

        ImageView fallbackLogoView = createLogoView(fitWidth);
        if (fallbackLogoView != null) {
            fallbackLogoView.setVisible(false);
        }

        try {
            String mediaSource = logoUrl.toExternalForm();
            Media media = new Media(mediaSource);
            MediaPlayer mediaPlayer = new MediaPlayer(media);
            mediaPlayer.setMute(true);
            mediaPlayer.setVolume(0.0);
            mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);

            MediaView mediaView = new MediaView(mediaPlayer);
            mediaView.setFitWidth(fitWidth);
            mediaView.setPreserveRatio(true);
            mediaView.setSmooth(true);
            mediaView.setMouseTransparent(true);

            StackPane logoPane = new StackPane();
            logoPane.setAlignment(Pos.CENTER);
            logoPane.setMouseTransparent(true);
            if (fallbackLogoView != null) {
                logoPane.getChildren().add(fallbackLogoView);
            }
            logoPane.getChildren().add(mediaView);

            Runnable fallback = () -> showStaticLogoFallback(mediaSource, media, mediaPlayer, mediaView, fallbackLogoView);
            media.setOnError(fallback);
            mediaPlayer.setOnError(fallback);

            disposeAnimatedLogoPlayer();
            animatedLogoPlayer = mediaPlayer;
            mediaPlayer.play();
            return logoPane;
        } catch (IllegalArgumentException | UnsupportedOperationException | MediaException ex) {
            logger.warn("Could not initialize animated login logo: {}", ANIMATED_LOGO_RESOURCE, ex);
            return createLogoView(fitWidth);
        }
    }

    private void showStaticLogoFallback(
        String mediaSource,
        Media media,
        MediaPlayer mediaPlayer,
        MediaView mediaView,
        ImageView fallbackLogoView
    ) {
        if (!mediaView.isVisible()) {
            return;
        }

        MediaException error = mediaPlayer.getError();
        if (error == null) {
            error = media.getError();
        }
        if (error != null) {
            logger.warn("Could not play animated login logo: {}", mediaSource, error);
        } else {
            logger.warn("Could not play animated login logo: {}", mediaSource);
        }

        mediaPlayer.stop();
        mediaPlayer.dispose();
        if (animatedLogoPlayer == mediaPlayer) {
            animatedLogoPlayer = null;
        }

        mediaView.setVisible(false);
        mediaView.setManaged(false);
        if (fallbackLogoView != null) {
            fallbackLogoView.setVisible(true);
            fallbackLogoView.setManaged(true);
        }
    }

    private void disposeAnimatedLogoPlayer() {
        if (animatedLogoPlayer == null) {
            return;
        }
        animatedLogoPlayer.stop();
        animatedLogoPlayer.dispose();
        animatedLogoPlayer = null;
    }

    private Label createLoginWindowVersionLabel() {
        Label versionLabel = createVersionLabel();
        versionLabel.setMouseTransparent(true);
        versionLabel.setAlignment(Pos.CENTER_LEFT);
        if (isElegantDarkDesign()) {
            versionLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #8b9099;");
        } else if (isKlingonTacticalDesign()) {
            versionLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(204,68,85,0.72);");
        } else if (!isCustomAppDesign()) {
            versionLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #d8e3f0;");
        }
        return versionLabel;
    }

    private Label createVersionLabel() {
        Label versionLabel = new Label(I18n.get("app.version") + " " + KorTTYApplication.getAppVersion());
        versionLabel.getStyleClass().add("version-label");
        if (isCustomAppDesign()) {
            versionLabel.setStyle("-fx-font-size: 12px;");
        } else {
            versionLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #d8e3f0;");
        }
        return versionLabel;
    }
    
    /**
     * Updates the password field border/background color based on length:
     * red = below minimum, green = meets or exceeds minimum.
     */
    private void updatePasswordFieldLengthStyle(PasswordField field, int length) {
        if (isCustomAppDesign()) {
            field.setStyle(null);
            return;
        }
        String basePasswordStyle = "-fx-background-color: #2f9cff;"
            + "-fx-control-inner-background: #2f9cff;"
            + "-fx-text-fill: #000000;"
            + "-fx-prompt-text-fill: rgba(0,0,0,0.65);"
            + "-fx-highlight-fill: #000000;"
            + "-fx-highlight-text-fill: #2f9cff;"
            + "-fx-border-width: 2px;"
            + "-fx-border-radius: 6px;"
            + "-fx-background-radius: 6px;";
        if (length < PasswordStrengthChecker.MIN_LENGTH) {
            field.setStyle(basePasswordStyle + "-fx-border-color: #ff6b6b;");
        } else {
            field.setStyle(basePasswordStyle + "-fx-border-color: #27ae60;");
        }
    }

    private void stylePasswordField(PasswordField passwordField) {
        passwordField.getStyleClass().add("terminal-input");
        if (isElegantDarkDesign()) {
            passwordField.getStyleClass().add("elegant-input");
        } else if (isHolographicInterfaceDesign()) {
            passwordField.getStyleClass().add("holo-input");
        } else if (isKlingonTacticalDesign()) {
            passwordField.getStyleClass().add("tac-input");
        } else if (!isCustomAppDesign()) {
            passwordField.setStyle(
                "-fx-background-color: #2f9cff;"
                    + "-fx-control-inner-background: #2f9cff;"
                    + "-fx-text-fill: #000000;"
                    + "-fx-prompt-text-fill: rgba(0,0,0,0.65);"
                    + "-fx-highlight-fill: #000000;"
                    + "-fx-highlight-text-fill: #2f9cff;"
                    + "-fx-border-color: #79c4ff;"
                    + "-fx-border-width: 1.5px;"
                    + "-fx-border-radius: 6px;"
                    + "-fx-background-radius: 6px;"
            );
        }
    }

    private void stylePrimaryButton(Button button) {
        button.getStyleClass().add("btn-primary");
        if (isHolographicInterfaceDesign()) {
            button.getStyleClass().add("holo-btn-primary");
        } else if (isKlingonTacticalDesign()) {
            button.getStyleClass().add("tac-btn-primary");
            button.setText(button.getText().toUpperCase(Locale.ROOT));
        } else if (!isCustomAppDesign()) {
            button.setStyle(
                "-fx-background-color: #16324a;"
                    + "-fx-border-color: #2f9cff;"
                    + "-fx-border-radius: 6px;"
                    + "-fx-background-radius: 6px;"
                    + "-fx-text-fill: #ffffff;"
                    + "-fx-font-weight: bold;"
                    + "-fx-cursor: hand;"
            );
        }
    }

    private void styleSecondaryButton(Button button) {
        button.getStyleClass().add("btn-secondary");
        if (isHolographicInterfaceDesign()) {
            button.getStyleClass().add("holo-btn-secondary");
        } else if (isKlingonTacticalDesign()) {
            button.getStyleClass().add("tac-btn-secondary");
            button.setText(button.getText().toUpperCase(Locale.ROOT));
        } else if (!isCustomAppDesign()) {
            button.setStyle(
                "-fx-background-color: #101010;"
                    + "-fx-border-color: #404854;"
                    + "-fx-border-radius: 6px;"
                    + "-fx-background-radius: 6px;"
                    + "-fx-text-fill: #e7eef8;"
                    + "-fx-cursor: hand;"
            );
        }
    }

    private void styleFieldLabel(Label label) {
        label.getStyleClass().add("field-label");
        if (!isCustomAppDesign()) {
            label.setStyle("-fx-text-fill: #f1f5fb; -fx-font-weight: bold;");
        }
    }

    private void styleErrorLabel(Label label) {
        styleFieldLabel(label);
        if (!isCustomAppDesign()) {
            label.setStyle("-fx-text-fill: #ff6b6b; -fx-font-weight: bold;");
        }
    }

    private boolean isMatrixTerminalDesign() {
        return matrixTerminalDesign;
    }

    private boolean isHolographicInterfaceDesign() {
        return holographicInterfaceDesign;
    }

    private boolean isKlingonTacticalDesign() {
        return klingonTacticalDesign;
    }

    private boolean isElegantDarkDesign() {
        return elegantDarkDesign;
    }

    private boolean isCustomAppDesign() {
        return customAppDesign;
    }
    
    private double calculatePasswordStrength(String password) {
        if (password == null || password.isEmpty()) {
            return 0;
        }
        
        double score = 0;
        
        // Length
        score += Math.min(password.length() / 16.0, 0.25);
        
        // Has lowercase
        if (password.matches(".*[a-z].*")) {
            score += 0.15;
        }
        
        // Has uppercase
        if (password.matches(".*[A-Z].*")) {
            score += 0.15;
        }
        
        // Has digits
        if (password.matches(".*[0-9].*")) {
            score += 0.15;
        }
        
        // Has special characters
        if (password.matches(".*[^a-zA-Z0-9].*")) {
            score += 0.3;
        }
        
        return Math.min(score, 1.0);
    }
    
    public boolean showAndWait() {
        logger.info("Showing master password dialog, isPasswordSet={}", passwordManager.isPasswordSet());
        dialog.showAndWait();
        logger.info("Master password dialog closed, result={}", result);
        return result;
    }

    /**
     * The telemetry consent choice from the first-run setup dialog: empty in
     * login mode or when the setup was cancelled, otherwise the checkbox state.
     */
    public Optional<Boolean> getTelemetryConsentChoice() {
        if (telemetryConsentCheck == null || !result) {
            return Optional.empty();
        }
        return Optional.of(telemetryConsentCheck.isSelected());
    }
}
