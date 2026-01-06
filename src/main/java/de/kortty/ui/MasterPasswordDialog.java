package de.kortty.ui;

import de.kortty.security.MasterPasswordManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
        dialog.setTitle("KorTTY - Anmeldung");
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);
        
        Label titleLabel = new Label("Master-Passwort eingeben");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Master-Passwort");
        passwordField.setPrefWidth(250);
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setVisible(false);
        
        Button loginButton = new Button("Anmelden");
        loginButton.setDefaultButton(true);
        loginButton.setPrefWidth(100);
        
        Button cancelButton = new Button("Abbrechen");
        cancelButton.setCancelButton(true);
        cancelButton.setPrefWidth(100);
        
        HBox buttonBox = new HBox(10, loginButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER);
        
        root.getChildren().addAll(titleLabel, passwordField, errorLabel, buttonBox);
        
        loginButton.setOnAction(e -> {
            String password = passwordField.getText();
            if (password.isEmpty()) {
                errorLabel.setText("Bitte Passwort eingeben");
                errorLabel.setVisible(true);
                return;
            }
            
            try {
                if (passwordManager.verifyPassword(password.toCharArray())) {
                    result = true;
                    dialog.close();
                } else {
                    errorLabel.setText("Falsches Passwort");
                    errorLabel.setVisible(true);
                    passwordField.clear();
                    passwordField.requestFocus();
                }
            } catch (Exception ex) {
                errorLabel.setText("Fehler: " + ex.getMessage());
                errorLabel.setVisible(true);
            }
        });
        
        cancelButton.setOnAction(e -> {
            result = false;
            dialog.close();
        });
        
        Scene scene = new Scene(root);
        dialog.setScene(scene);
        dialog.sizeToScene();
    }
    
    private void setupSetupDialog() {
        dialog.setTitle("KorTTY - Master-Passwort einrichten");
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);
        
        Label titleLabel = new Label("Master-Passwort einrichten");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        Label infoLabel = new Label(
                "Dieses Passwort wird verwendet, um Ihre\ngespeicherten Verbindungspasswörter zu schützen."
        );
        infoLabel.setStyle("-fx-text-fill: #666;");
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);
        
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Passwort");
        passwordField.setPrefWidth(200);
        
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText("Passwort bestätigen");
        confirmField.setPrefWidth(200);
        
        grid.add(new Label("Passwort:"), 0, 0);
        grid.add(passwordField, 1, 0);
        grid.add(new Label("Bestätigen:"), 0, 1);
        grid.add(confirmField, 1, 1);
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setVisible(false);
        
        // Password strength indicator
        ProgressBar strengthBar = new ProgressBar(0);
        strengthBar.setPrefWidth(200);
        Label strengthLabel = new Label("Passwortstärke");
        
        passwordField.textProperty().addListener((obs, old, newVal) -> {
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
        
        Button setupButton = new Button("Einrichten");
        setupButton.setDefaultButton(true);
        setupButton.setPrefWidth(100);
        
        Button cancelButton = new Button("Abbrechen");
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
                errorLabel.setText("Bitte Passwort eingeben");
                errorLabel.setVisible(true);
                return;
            }
            
            if (password.length() < 6) {
                errorLabel.setText("Passwort muss mindestens 6 Zeichen haben");
                errorLabel.setVisible(true);
                return;
            }
            
            if (!password.equals(confirm)) {
                errorLabel.setText("Passwörter stimmen nicht überein");
                errorLabel.setVisible(true);
                return;
            }
            
            try {
                logger.info("Setting up master password...");
                passwordManager.setupPassword(password.toCharArray());
                logger.info("Master password setup successful");
                result = true;
                dialog.close();
            } catch (Exception ex) {
                logger.error("Failed to setup master password", ex);
                errorLabel.setText("Fehler: " + ex.getMessage());
                errorLabel.setVisible(true);
            }
        });
        
        cancelButton.setOnAction(e -> {
            result = false;
            dialog.close();
        });
        
        Scene scene = new Scene(root);
        dialog.setScene(scene);
        dialog.sizeToScene();
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
