package de.kortty.ui;

import de.kortty.core.AnalysisCategoryVisuals;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.control.Label;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Renders the category glyphs through the same JavaFX SVGPath the progress rows use. */
public final class AnalysisCategoryIconRender {

    private AnalysisCategoryIconRender() {
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "build/smoke/analysis-category-icons.png";
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                VBox rows = new VBox(14);
                rows.setStyle("-fx-background-color: #0f1b28; -fx-padding: 18;");
                for (String category : List.of("security", "optimization", "design", "dependencies")) {
                    HBox row = new HBox(20);
                    row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    for (double size : new double[]{16, 28, 64}) {
                        SVGPath path = new SVGPath();
                        path.setContent(AnalysisCategoryVisuals.iconPath(category));
                        path.setFillRule(FillRule.EVEN_ODD);
                        path.setFill(Color.web(AnalysisCategoryVisuals.colorHex(category)));
                        path.setScaleX(size / 16.0);
                        path.setScaleY(size / 16.0);
                        Group holder = new Group(path);
                        HBox cell = new HBox(holder);
                        cell.setMinSize(size + 12, size + 12);
                        cell.setPrefSize(size + 12, size + 12);
                        cell.setAlignment(javafx.geometry.Pos.CENTER);
                        row.getChildren().add(cell);
                    }
                    Label name = new Label(category);
                    name.setFont(Font.font(15));
                    name.setStyle("-fx-text-fill: #dbe6f0;");
                    row.getChildren().add(name);
                    rows.getChildren().add(row);
                }
                Scene scene = new Scene(rows);
                SnapshotParameters params = new SnapshotParameters();
                params.setFill(Color.web("#0f1b28"));
                WritableImage image = rows.snapshot(params, null);
                File file = new File(out);
                file.getParentFile().mkdirs();
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
                System.out.println("wrote " + file.getAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        System.exit(0);
    }
}
