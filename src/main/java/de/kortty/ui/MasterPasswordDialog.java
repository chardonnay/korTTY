package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.security.MasterPasswordManager;
import de.kortty.security.PasswordStrengthChecker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialog for setting up or entering the master password.
 */
public class MasterPasswordDialog {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterPasswordDialog.class);
    private static final double LOGIN_DIALOG_WIDTH = 680;
    private static final double LOGIN_DIALOG_HEIGHT = 300;
    private static final double SETUP_DIALOG_WIDTH = 780;
    private static final double SETUP_DIALOG_HEIGHT = 520;
    private static final double BRAND_PANEL_WIDTH = 190;
    private static final double LOGO_WIDTH = 128;
    
    private final Stage dialog;
    private final MasterPasswordManager passwordManager;
    private boolean result = false;
    
    public MasterPasswordDialog(Stage owner, MasterPasswordManager passwordManager) {
        this.passwordManager = passwordManager;
        
        dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        // Don't set owner if it's not showing yet
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
        }
        dialog.initStyle(StageStyle.UTILITY);
        dialog.setResizable(false);
        dialog.setOnCloseRequest(e -> {
            result = false;
        });
        
        if (passwordManager.isPasswordSet()) {
            setupLoginDialog();
        } else {
            setupSetupDialog();
        }
    }
    
    private void setupLoginDialog() {
        dialog.setTitle(I18n.get("masterPassword.title"));
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(34, 38, 30, 38));
        root.setAlignment(Pos.CENTER);
        
        Label titleLabel = new Label(I18n.get("masterPassword.enter"));
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("masterPassword.password"));
        passwordField.setPrefWidth(250);
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setVisible(false);
        
        Button loginButton = new Button(I18n.get("masterPassword.loginButton"));
        loginButton.setDefaultButton(true);
        loginButton.setPrefWidth(100);
        
        Button cancelButton = new Button(I18n.get("dialog.cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setPrefWidth(100);
        
        HBox buttonBox = new HBox(10, loginButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER);
        
        root.getChildren().addAll(titleLabel, passwordField, errorLabel, buttonBox);
        
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
        
        Scene scene = createBrandedScene(root, LOGIN_DIALOG_WIDTH, LOGIN_DIALOG_HEIGHT);
        dialog.setScene(scene);
        dialog.setMinWidth(LOGIN_DIALOG_WIDTH);
        dialog.setMinHeight(LOGIN_DIALOG_HEIGHT);
    }
    
    private void setupSetupDialog() {
        dialog.setTitle(I18n.get("masterPassword.setup"));
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(34, 38, 30, 38));
        root.setAlignment(Pos.CENTER);
        
        Label titleLabel = new Label(I18n.get("masterPassword.setup"));
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        Label infoLabel = new Label(I18n.get("masterPassword.setup.info"));
        infoLabel.setStyle("-fx-text-fill: #666;");
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);
        
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("masterPassword.password"));
        passwordField.setPrefWidth(200);
        updatePasswordFieldLengthStyle(passwordField, 0);
        
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText(I18n.get("masterPassword.confirm"));
        confirmField.setPrefWidth(200);
        
        grid.add(new Label(I18n.get("masterPassword.password")), 0, 0);
        grid.add(passwordField, 1, 0);
        grid.add(new Label(I18n.get("masterPassword.confirm")), 0, 1);
        grid.add(confirmField, 1, 1);
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setVisible(false);
        
        // Length-based color feedback and strength indicator
        ProgressBar strengthBar = new ProgressBar(0);
        strengthBar.setPrefWidth(200);
        Label strengthLabel = new Label(I18n.get("masterPassword.strength"));
        
        passwordField.textProperty().addListener((obs, old, newVal) -> {
            int len = newVal != null ? newVal.length() : 0;
            updatePasswordFieldLengthStyle(passwordField, len);
            double strength = calculatePasswordStrength(newVal);
            strengthBar.setProgress(strength);
            if (strength < 0.3) {
                strengthBar.setStyle("-fx-accent: red;");
            } else if (strength < 0.6) {
                strengthBar.setStyle("-fx-accent: orange;");
            } else {
                strengthBar.setStyle("-fx-accent: green;");
            }
        });
        
        Button setupButton = new Button(I18n.get("masterPassword.setupButton"));
        setupButton.setDefaultButton(true);
        setupButton.setPrefWidth(100);
        
        Button cancelButton = new Button(I18n.get("dialog.cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setPrefWidth(100);
        
        HBox buttonBox = new HBox(10, setupButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER);
        
        HBox strengthBox = new HBox(10, strengthLabel, strengthBar);
        strengthBox.setAlignment(Pos.CENTER);
        
        root.getChildren().addAll(titleLabel, infoLabel, grid, strengthBox, errorLabel, buttonBox);
        
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
        HBox brandedRoot = new HBox(24);
        brandedRoot.setPrefSize(width, height);
        brandedRoot.setMinSize(width, height);
        brandedRoot.setAlignment(Pos.CENTER);
        brandedRoot.setPadding(new Insets(0, 34, 0, 16));
        brandedRoot.getStyleClass().add("master-password-root");
        brandedRoot.setStyle("-fx-background-color: #ECEFF3;");
        content.setMaxWidth(width - BRAND_PANEL_WIDTH - 72);
        VBox brandBox = createBrandBox();
        brandedRoot.getChildren().addAll(content, brandBox);
        return new Scene(brandedRoot, width, height);
    }

    private VBox createBrandBox() {
        VBox brandBox = new VBox(12);
        brandBox.setAlignment(Pos.CENTER);
        brandBox.setMinWidth(BRAND_PANEL_WIDTH);
        brandBox.setPrefWidth(BRAND_PANEL_WIDTH);
        brandBox.setMaxWidth(BRAND_PANEL_WIDTH);

        ImageView logoView = createLogoView();
        Label versionLabel = createVersionLabel();
        if (logoView != null) {
            brandBox.getChildren().add(logoView);
        }
        brandBox.getChildren().add(versionLabel);
        return brandBox;
    }

    private ImageView createLogoView() {
        var logoUrl = getClass().getResource("/icon/kortty_icon.png");
        if (logoUrl == null) {
            return null;
        }
        ImageView logoView = new ImageView(new Image(logoUrl.toExternalForm()));
        logoView.setFitWidth(LOGO_WIDTH);
        logoView.setPreserveRatio(true);
        logoView.setMouseTransparent(true);
        return logoView;
    }

    private Label createVersionLabel() {
        Label versionLabel = new Label(I18n.get("app.version") + " " + KorTTYApplication.getAppVersion());
        versionLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #777;");
        return versionLabel;
    }
    
    /**
     * Updates the password field border/background color based on length:
     * red = below minimum, green = meets or exceeds minimum.
     */
    private void updatePasswordFieldLengthStyle(PasswordField field, int length) {
        if (length < PasswordStrengthChecker.MIN_LENGTH) {
            field.setStyle("-fx-border-color: #e74c3c; -fx-border-width: 2px; -fx-border-radius: 3px;");
        } else {
            field.setStyle("-fx-border-color: #27ae60; -fx-border-width: 2px; -fx-border-radius: 3px;");
        }
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
}
