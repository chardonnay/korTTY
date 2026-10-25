package de.kortty.ui;

import de.kortty.model.GroupPath;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.transform.Scale;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * The Connection Manager's map view: a zone per folder in its tab color, a node per connection,
 * this computer on the left, jump routes as orange and tunnels as teal dashed lines. A click selects
 * a node, a double-click connects, and dragging a node into another zone moves the connection into
 * that folder. Arranged automatically by {@link ConnectionMapLayout}; nothing about the map is
 * stored. FX thread only.
 */
final class ConnectionMapView extends BorderPane {

    private static final String JUMP_COLOR = "#dc9825";
    private static final String TUNNEL_COLOR = "#13a8ad";
    private static final String NEUTRAL = "rgba(128, 128, 128, 0.55)";
    private static final double MIN_ZOOM = 0.4;
    private static final double MAX_ZOOM = 1.8;
    private static final double DRAG_THRESHOLD = 4;

    private final Pane content = new Pane();
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final ScrollPane scroller;
    private final ToggleButton jumpLayer = new ToggleButton(I18n.get("connectionManager.map.layer.jump"));
    private final ToggleButton tunnelLayer = new ToggleButton(I18n.get("connectionManager.map.layer.tunnel"));
    private final Label zoomLabel = new Label("100 %");
    /** The text colour of the theme, which glyph contours and edge labels follow. */
    private final Label themeText = new Label();
    private final Map<ServerConnection, HBox> nodeViews = new HashMap<>();
    private final Set<ServerConnection> selected = new HashSet<>();
    private final Map<HBox, Boolean> teamworkViews = new HashMap<>();

    private List<ServerConnection> connections = List.of();
    private Function<String, String> groupColor = group -> null;
    private ToIntFunction<ServerConnection> openTabs = connection -> 0;
    /** Teamwork connections: in zones of their own, dashed, never moved. */
    private java.util.function.Predicate<ServerConnection> shared = connection -> false;
    /** The OS badge drawn on a node's tile, or null. */
    private Function<ServerConnection, Node> badges = connection -> null;
    private ConnectionMapLayout.Result layout;
    /** Whether the map was already zoomed out once to fit a narrow window; after that the zoom is the user's. */
    private boolean autoFitted;
    private Consumer<ServerConnection> onSelect;
    private Consumer<ServerConnection> onOpen;
    private BiConsumer<ServerConnection, String> onMove;

    ConnectionMapView() {
        getStyleClass().add("cm-map");
        themeText.setManaged(false);
        themeText.setVisible(false);
        content.getChildren().add(themeText);
        content.getTransforms().add(scale);
        scroller = new ScrollPane(new Group(content));
        scroller.setPannable(true);
        scroller.getStyleClass().add("cm-map-scroller");
        scroller.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (event.isShortcutDown() || event.isControlDown()) {
                zoom(event.getDeltaY() > 0 ? 1.1 : 1 / 1.1);
                event.consume();
            }
        });
        // A wider viewport packs more zones into each row.
        scroller.viewportBoundsProperty().addListener((obs, old, bounds) -> {
            if (old == null || Math.abs(old.getWidth() - bounds.getWidth()) > 40) {
                rebuild();
            }
        });

        jumpLayer.setSelected(true);
        tunnelLayer.setSelected(true);
        jumpLayer.setGraphic(ToolbarIcons.create("jump", jumpLayer.textFillProperty(), 15));
        tunnelLayer.setGraphic(ToolbarIcons.create("tunnel", tunnelLayer.textFillProperty(), 15));
        jumpLayer.setOnAction(e -> rebuild());
        tunnelLayer.setOnAction(e -> rebuild());
        Button zoomOut = new Button("−");
        zoomOut.setAccessibleText(I18n.get("connectionManager.map.zoomOut"));
        zoomOut.setTooltip(new Tooltip(I18n.get("connectionManager.map.zoomOut")));
        zoomOut.setOnAction(e -> zoom(1 / 1.15));
        Button zoomIn = new Button("+");
        zoomIn.setAccessibleText(I18n.get("connectionManager.map.zoomIn"));
        zoomIn.setTooltip(new Tooltip(I18n.get("connectionManager.map.zoomIn")));
        zoomIn.setOnAction(e -> zoom(1.15));
        Button fit = new Button(I18n.get("connectionManager.map.fit"));
        fit.setOnAction(e -> fit());
        Button pdf = new Button(I18n.get("connectionManager.map.pdf"));
        pdf.setGraphic(ToolbarIcons.create("exportPdf", pdf.textFillProperty(), 16));
        pdf.setTooltip(new Tooltip(I18n.get("connectionManager.map.pdf.tooltip")));
        pdf.setOnAction(e -> exportPdf());
        zoomLabel.setMinWidth(Region.USE_PREF_SIZE);
        zoomOut.setMinWidth(Region.USE_PREF_SIZE);
        zoomIn.setMinWidth(Region.USE_PREF_SIZE);
        // In a narrow middle column the layer toggles, Fit and PDF shorten to their icon and an ellipsis instead
        // of widening the whole Connection Manager; their tooltips keep the full name.
        jumpLayer.setTooltip(new Tooltip(jumpLayer.getText()));
        tunnelLayer.setTooltip(new Tooltip(tunnelLayer.getText()));
        fit.setTooltip(new Tooltip(fit.getText()));
        Label hint = new Label(I18n.get("connectionManager.map.hint"));
        hint.setStyle("-fx-opacity: 0.7; -fx-font-size: 0.9em;");
        hint.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, jumpLayer, tunnelLayer, spacer, hint, zoomOut, zoomLabel, zoomIn, fit, pdf);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 8));
        bar.getStyleClass().add("cm-map-bar");
        setTop(bar);
        setCenter(scroller);
    }

    void setOnSelect(Consumer<ServerConnection> handler) { this.onSelect = handler; }

    /** What the PDF's subtitle names as the map's content, e.g. the library entry it shows. */
    private java.util.function.Supplier<String> pdfScope = () -> "";

    void setPdfScope(java.util.function.Supplier<String> scope) { this.pdfScope = scope; }

    void setOnOpen(Consumer<ServerConnection> handler) { this.onOpen = handler; }

    /** Hands a connection dragged into another zone and that zone's folder path to {@code handler}. */
    void setOnMove(BiConsumer<ServerConnection, String> handler) { this.onMove = handler; }

    /**
     * Shows {@code shown} on the map.
     *
     * @param color    the tab color a folder shows ({@code #RRGGBB}) or null, by folder path
     * @param tabs     how many tabs a connection has open
     * @param shared   which connections are teamwork connections, shown in zones of their own and never moved
     */
    void show(List<ServerConnection> shown, Function<String, String> color, ToIntFunction<ServerConnection> tabs,
              java.util.function.Predicate<ServerConnection> shared, Function<ServerConnection, Node> badges) {
        this.badges = badges != null ? badges : connection -> null;
        this.connections = List.copyOf(shown);
        this.groupColor = color != null ? color : group -> null;
        this.openTabs = tabs != null ? tabs : connection -> 0;
        this.shared = shared != null ? shared : connection -> false;
        rebuild();
    }

    /** Zones of teamwork folders start with this, so they never merge with a local folder of the same name. */
    static final String TEAMWORK_ZONE = "\u0001teamwork";

    /** The zone a connection belongs to: its folder, or a teamwork zone for a shared connection. */
    private String zoneKey(ServerConnection connection) {
        String group = connection.getGroup() != null ? connection.getGroup().trim() : "";
        if (!shared.test(connection)) {
            return group;
        }
        return group.isEmpty() ? TEAMWORK_ZONE : TEAMWORK_ZONE + GroupPath.SEPARATOR + group;
    }

    /** Highlights {@code connections} as the selection. */
    void setSelection(Collection<ServerConnection> connections) {
        selected.clear();
        selected.addAll(connections);
        nodeViews.forEach((connection, view) -> styleNode(view, selected.contains(connection),
            Boolean.TRUE.equals(teamworkViews.get(view))));
    }

    private void rebuild() {
        nodeViews.clear();
        teamworkViews.clear();
        content.getChildren().setAll(themeText);
        Map<String, ServerConnection> byId = new HashMap<>();
        List<ConnectionMapLayout.Entry> entries = new ArrayList<>();
        for (ServerConnection connection : connections) {
            if (connection == null || connection.isPlaceholder()) {
                continue;
            }
            String id = connection.getId() + "#" + entries.size();
            byId.put(id, connection);
            var jump = connection.getJumpServer();
            boolean jumps = ConnectionRowGraphics.jumpTarget(connection) != null;
            entries.add(new ConnectionMapLayout.Entry(id, zoneKey(connection), connection.getHost(),
                connection.getPort(), jumps ? jump.getHost() : null, jumps ? jump.getPort() : 0,
                ConnectionRowGraphics.tunnelSummary(connection)));
        }
        double width = scroller.getViewportBounds() != null && scroller.getViewportBounds().getWidth() > 0
            ? scroller.getViewportBounds().getWidth() / scale.getX() : 1100;
        layout = ConnectionMapLayout.layout(entries, width - 16);
        content.setPrefSize(layout.width(), layout.height());
        content.setMinSize(layout.width(), layout.height());

        double viewport = scroller.getViewportBounds() != null ? scroller.getViewportBounds().getWidth() : 0;
        // Judged on the first real picture: not while the map is hidden behind the list or still empty.
        if (!autoFitted && viewport > 0 && isVisible() && !entries.isEmpty()) {
            autoFitted = true;
            if (layout.width() * scale.getX() > viewport) {
                setZoom(Math.max(0.7, viewport / layout.width()));
                rebuild();
                return;
            }
        }
        for (ConnectionMapLayout.Zone zone : layout.zones()) {
            content.getChildren().add(zoneView(zone));
        }
        ConnectionMapLayout.Node me = layout.node(ConnectionMapLayout.ME);
        int tunnelRow = 0;
        for (ConnectionMapLayout.Edge edge : layout.edges()) {
            boolean jump = edge.kind() == ConnectionMapLayout.EdgeKind.JUMP;
            if ((jump && !jumpLayer.isSelected()) || (!jump && !tunnelLayer.isSelected())) {
                continue;
            }
            ConnectionMapLayout.Node from = layout.node(edge.from());
            ConnectionMapLayout.Node to = layout.node(edge.to());
            if (from != null && to != null) {
                content.getChildren().add(edgeView(from, to, jump));
            }
            if (!jump && edge.label() != null && me != null && to != null) {
                // Tunnels are listed under this computer, where every one of them starts.
                ServerConnection target = byId.get(edge.to());
                Label label = new Label((target != null ? target.getName() + ": " : "") + edge.label(),
                    ToolbarIcons.create("tunnel", themeText.textFillProperty(), 13));
                label.setStyle("-fx-font-size: 0.85em;");
                label.setMaxWidth(ConnectionMapLayout.ORIGIN_X - ConnectionMapLayout.ME_X - 16);
                label.setMouseTransparent(true);
                label.relocate(me.x(), me.y() + ConnectionMapLayout.NODE_HEIGHT + 12 + 20 * tunnelRow++);
                content.getChildren().add(label);
            }
        }
        for (ConnectionMapLayout.Node node : layout.nodes()) {
            Node view;
            if (ConnectionMapLayout.ME.equals(node.id())) {
                view = meView(node);
            } else if (node.external()) {
                view = externalView(node);
            } else {
                ServerConnection connection = byId.get(node.id());
                view = connectionView(node, connection);
            }
            content.getChildren().add(view);
        }
    }

    private Node zoneView(ConnectionMapLayout.Zone zone) {
        boolean external = ConnectionMapLayout.EXTERNAL_ZONE.equals(zone.key());
        boolean teamwork = zone.key().startsWith(TEAMWORK_ZONE);
        String color = external || teamwork ? null
            : de.kortty.core.ConnectionColorSupport.normalizeHex(groupColor.apply(zone.key()));
        String stroke = color != null ? color : NEUTRAL;
        String fill = color != null ? color + "14" : "rgba(128, 128, 128, 0.05)";
        Region area = new Region();
        area.setStyle("-fx-border-color: " + stroke + "; -fx-border-style: segments(7, 5); -fx-border-width: 1.5;"
            + " -fx-border-radius: 14; -fx-background-color: " + fill + "; -fx-background-radius: 14;");
        area.resizeRelocate(zone.x(), zone.y(), zone.width(), zone.height());
        area.setManaged(false);

        String title = external ? I18n.get("connectionManager.map.external")
            : teamwork ? zoneTitle(zone.key())
            : zone.key().isEmpty() ? I18n.get("connectionManager.noFolder")
            : zone.key().replace(GroupPath.SEPARATOR, " / ");
        Label label = new Label(title, ToolbarIcons.create(external ? "jump" : teamwork ? "teamwork" : "folder",
            themeText.textFillProperty(), 15, color));
        label.setStyle("-fx-font-weight: bold;");
        label.setMaxWidth(zone.width() - 2 * ConnectionMapLayout.ZONE_PADDING);
        // Managed, so the pane sizes it once its font is known; it keeps the place relocate gives it.
        label.relocate(zone.x() + ConnectionMapLayout.ZONE_PADDING, zone.y() + 6);
        Pane zonePane = new Pane(area, label);
        zonePane.setManaged(false);
        zonePane.setPickOnBounds(false);
        area.setMouseTransparent(true);
        return zonePane;
    }

    private Node edgeView(ConnectionMapLayout.Node from, ConnectionMapLayout.Node to, boolean jump) {
        double width = ConnectionMapLayout.NODE_WIDTH;
        double dx = to.centerX() - from.centerX();
        double startY = from.centerY();
        double endY = to.centerY();
        double startX;
        double endX;
        double control1;
        double control2;
        if (Math.abs(dx) < width) {
            // One above the other: out of the right side and back into it.
            startX = from.x() + width;
            endX = to.x() + width;
            double bulge = 36 + Math.abs(endY - startY) / 6;
            control1 = startX + bulge;
            control2 = endX + bulge;
        } else if (dx > 0) {
            startX = from.x() + width;
            endX = to.x();
            double bend = Math.max(40, (endX - startX) / 2.2);
            control1 = startX + bend;
            control2 = endX - bend;
        } else {
            startX = from.x();
            endX = to.x() + width;
            double bend = Math.max(40, (startX - endX) / 2.2);
            control1 = startX - bend;
            control2 = endX + bend;
        }
        CubicCurve curve = new CubicCurve(startX, startY, control1, startY, control2, endY, endX, endY);
        curve.setFill(null);
        curve.setStroke(Color.web(jump ? JUMP_COLOR : TUNNEL_COLOR));
        curve.setStrokeWidth(1.8);
        curve.getStrokeDashArray().setAll(jump ? List.of(7.0, 5.0) : List.of(3.0, 5.0));
        curve.setManaged(false);
        curve.setMouseTransparent(true);
        return curve;
    }

    /** "Teamwork" or "Teamwork / folder" for a teamwork zone, else the folder path. */
    static String zoneTitle(String key) {
        if (key.startsWith(TEAMWORK_ZONE)) {
            String folder = key.substring(TEAMWORK_ZONE.length());
            String teamwork = I18n.get("connectionManager.tab.teamwork");
            return folder.isEmpty() ? teamwork : teamwork + folder.replace(GroupPath.SEPARATOR, " / ");
        }
        return key.isEmpty() ? I18n.get("connectionManager.noFolder") : key.replace(GroupPath.SEPARATOR, " / ");
    }

    private Node meView(ConnectionMapLayout.Node node) {
        Label name = new Label(I18n.get("connectionManager.map.you"));
        name.setStyle("-fx-font-weight: bold;");
        HBox view = nodeBox(node, ToolbarIcons.create("localShell", name.textFillProperty(), 17), name);
        styleNode(view, false, false);
        return view;
    }

    private Node externalView(ConnectionMapLayout.Node node) {
        Label name = new Label(node.label());
        HBox view = nodeBox(node, ToolbarIcons.create("jump", name.textFillProperty(), 17), name);
        styleNode(view, false, true);
        Tooltip.install(view, new Tooltip(I18n.get("connectionManager.map.externalTooltip", node.label())));
        return view;
    }

    private Node connectionView(ConnectionMapLayout.Node node, ServerConnection connection) {
        Label name = new Label(connection.getName());
        name.setStyle("-fx-font-weight: bold;");
        name.setMinWidth(0);
        HBox view = nodeBox(node, ConnectionRowGraphics.protocolTile(connection, name.textFillProperty(), 15,
            openTabs.applyAsInt(connection), badges.apply(connection)), name);
        if (!de.kortty.policy.ServerAccessPolicy.isAllowed(connection)) {
            view.getChildren().add(ToolbarIcons.create("blocked", name.textFillProperty(), 14));
            view.setOpacity(0.55);
        }
        boolean teamwork = shared.test(connection);
        if (teamwork) {
            Node mark = ConnectionRowGraphics.mark("teamwork", ConnectionRowGraphics.muted(name.textFillProperty(), 0.55),
                I18n.get("connectionManager.row.teamwork"));
            view.getChildren().add(mark);
        }
        view.setAccessibleText(connection.getName() + ", " + ConnectionRowGraphics.address(connection));
        Tooltip.install(view, new Tooltip(tooltip(connection)));
        view.setCursor(javafx.scene.Cursor.HAND);
        nodeViews.put(connection, view);
        teamworkViews.put(view, teamwork);
        styleNode(view, selected.contains(connection), teamwork);
        installInteraction(view, node, connection);
        return view;
    }

    private HBox nodeBox(ConnectionMapLayout.Node node, Node icon, Label name) {
        HBox view = new HBox(8, icon, name);
        view.setAlignment(Pos.CENTER_LEFT);
        view.setPadding(new Insets(0, 10, 0, 6));
        view.setManaged(false);
        view.resizeRelocate(node.x(), node.y(), ConnectionMapLayout.NODE_WIDTH, ConnectionMapLayout.NODE_HEIGHT);
        HBox.setHgrow(name, Priority.ALWAYS);
        return view;
    }

    private static void styleNode(HBox view, boolean isSelected, boolean dashed) {
        String border = isSelected ? "#368af5" : "rgba(128, 128, 128, 0.5)";
        String background = isSelected ? "rgba(54, 138, 245, 0.20)" : "rgba(128, 128, 128, 0.12)";
        view.setStyle("-fx-background-color: " + background + "; -fx-background-radius: 9; -fx-border-color: "
            + border + "; -fx-border-radius: 9; -fx-border-width: " + (isSelected ? 2 : 1)
            + (dashed ? "; -fx-border-style: segments(5, 4)" : "") + ";");
    }

    private void installInteraction(HBox view, ConnectionMapLayout.Node node, ServerConnection connection) {
        double[] press = new double[4];
        boolean[] dragging = {false};
        view.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            Point2D local = content.sceneToLocal(event.getSceneX(), event.getSceneY());
            press[0] = local.getX();
            press[1] = local.getY();
            press[2] = view.getLayoutX();
            press[3] = view.getLayoutY();
            dragging[0] = false;
            event.consume();
        });
        view.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            event.consume();
            if (shared.test(connection) || onMove == null || event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            Point2D local = content.sceneToLocal(event.getSceneX(), event.getSceneY());
            double dx = local.getX() - press[0];
            double dy = local.getY() - press[1];
            if (!dragging[0] && Math.hypot(dx, dy) < DRAG_THRESHOLD) {
                return;
            }
            dragging[0] = true;
            view.toFront();
            view.relocate(press[2] + dx, press[3] + dy);
            ConnectionMapLayout.Zone target = layout.zoneAt(view.getLayoutX() + ConnectionMapLayout.NODE_WIDTH / 2,
                view.getLayoutY() + ConnectionMapLayout.NODE_HEIGHT / 2);
            view.setOpacity(movableInto(target, node) ? 0.85 : 1);
        });
        view.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            event.consume();
            if (!dragging[0]) {
                return;
            }
            dragging[0] = false;
            ConnectionMapLayout.Zone target = layout.zoneAt(view.getLayoutX() + ConnectionMapLayout.NODE_WIDTH / 2,
                view.getLayoutY() + ConnectionMapLayout.NODE_HEIGHT / 2);
            if (movableInto(target, node) && onMove != null) {
                onMove.accept(connection, target.key().isEmpty() ? null : target.key());
            } else {
                view.relocate(press[2], press[3]);
                view.setOpacity(1);
            }
        });
        view.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
                return;
            }
            if (event.getClickCount() == 2 && onOpen != null) {
                onOpen.accept(connection);
            } else if (onSelect != null) {
                onSelect.accept(connection);
            }
            event.consume();
        });
    }

    /** A local connection moves only into another local folder's zone. */
    private static boolean movableInto(ConnectionMapLayout.Zone target, ConnectionMapLayout.Node node) {
        return target != null && !target.key().equals(node.zone())
            && !ConnectionMapLayout.EXTERNAL_ZONE.equals(target.key()) && !target.key().startsWith(TEAMWORK_ZONE);
    }

    private static String tooltip(ServerConnection connection) {
        StringBuilder text = new StringBuilder(connection.getName()).append('\n')
            .append(ConnectionRowGraphics.address(connection));
        String jump = ConnectionRowGraphics.jumpTarget(connection);
        if (jump != null) {
            text.append('\n').append(I18n.get("connectionManager.row.jump", jump));
        }
        for (SSHTunnel tunnel : ConnectionRowGraphics.enabledTunnels(connection)) {
            text.append('\n').append(ConnectionRowGraphics.describeTunnel(tunnel));
        }
        return text.toString();
    }

    // ----- PDF export -----

    /** Asks for orientation and title, then where to save, and writes the map as a vector PDF in the background. */
    private void exportPdf() {
        if (connections.isEmpty()) {
            return;
        }
        Dialog<ButtonType> options = new Dialog<>();
        DialogThemeHelper.applyTheme(options);
        options.initOwner(getScene() != null ? getScene().getWindow() : null);
        options.setTitle(I18n.get("connectionManager.map.pdf.title"));
        options.setHeaderText(I18n.get("connectionManager.map.pdf.header"));
        javafx.scene.control.ToggleGroup orientation = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.RadioButton landscape = new javafx.scene.control.RadioButton(
            I18n.get("connectionManager.map.pdf.landscape"));
        javafx.scene.control.RadioButton portrait = new javafx.scene.control.RadioButton(
            I18n.get("connectionManager.map.pdf.portrait"));
        landscape.setToggleGroup(orientation);
        portrait.setToggleGroup(orientation);
        landscape.setSelected(true);
        javafx.scene.control.TextField title = new javafx.scene.control.TextField(I18n.get("connectionManager.map.pdf.defaultTitle"));
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));
        grid.add(new Label(I18n.get("connectionManager.map.pdf.orientation")), 0, 0);
        grid.add(new HBox(14, landscape, portrait), 1, 0);
        grid.add(new Label(I18n.get("connectionManager.map.pdf.titleLabel")), 0, 1);
        grid.add(title, 1, 1);
        javafx.scene.layout.GridPane.setHgrow(title, Priority.ALWAYS);
        title.setPrefColumnCount(28);
        options.getDialogPane().setContent(grid);
        ButtonType export = new ButtonType(I18n.get("connectionManager.map.pdf.export"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        options.getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        if (options.showAndWait().orElse(ButtonType.CANCEL) != export) {
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(I18n.get("connectionManager.map.pdf.title"));
        chooser.setInitialFileName("korTTY-" + I18n.get("connectionManager.view.map").toLowerCase(Locale.ROOT) + ".pdf");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File file = chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (file == null) {
            return;
        }
        ConnectionMapPdfWriter.Request request = pdfRequest(title.getText() == null || title.getText().isBlank()
            ? I18n.get("connectionManager.map.pdf.defaultTitle") : title.getText().trim(), landscape.isSelected());
        javafx.concurrent.Task<Void> write = new javafx.concurrent.Task<>() {
            @Override
            protected Void call() throws Exception {
                ConnectionMapPdfWriter.write(file.toPath(), request);
                return null;
            }
        };
        write.setOnSucceeded(e -> info(javafx.scene.control.Alert.AlertType.INFORMATION,
            I18n.get("connectionManager.map.pdf.done", file.getAbsolutePath())));
        write.setOnFailed(e -> info(javafx.scene.control.Alert.AlertType.ERROR,
            I18n.get("connectionManager.map.pdf.failed", String.valueOf(write.getException() != null
                ? write.getException().getMessage() : ""))));
        Thread thread = new Thread(write, "connection-map-pdf");
        thread.setDaemon(true);
        thread.start();
    }

    /** The map as the PDF writer needs it: the same entries as on screen, the badges as images. FX thread. */
    ConnectionMapPdfWriter.Request pdfRequest(String title, boolean landscape) {
        List<ConnectionMapLayout.Entry> entries = new ArrayList<>();
        Map<String, ConnectionMapPdfWriter.MapNode> nodes = new HashMap<>();
        for (ServerConnection connection : connections) {
            if (connection == null || connection.isPlaceholder()) {
                continue;
            }
            String id = connection.getId() + "#" + entries.size();
            var jump = connection.getJumpServer();
            boolean jumps = ConnectionRowGraphics.jumpTarget(connection) != null;
            entries.add(new ConnectionMapLayout.Entry(id, zoneKey(connection), connection.getHost(),
                connection.getPort(), jumps ? jump.getHost() : null, jumps ? jump.getPort() : 0,
                tunnelLayer.isSelected() ? ConnectionRowGraphics.tunnelSummary(connection) : null));
            nodes.put(id, new ConnectionMapPdfWriter.MapNode(connection.getName(), ConnectionRowGraphics.address(connection),
                shared.test(connection), badgeImage(badges.apply(connection))));
        }
        String subtitle = I18n.get("connectionManager.map.pdf.subtitle", pdfScope.get(), nodes.size(),
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)));
        return new ConnectionMapPdfWriter.Request(title, subtitle, landscape, entries, nodes,
            ConnectionMapView::zoneTitle,
            key -> key.startsWith(TEAMWORK_ZONE) || ConnectionMapLayout.EXTERNAL_ZONE.equals(key) ? null
                : de.kortty.core.ConnectionColorSupport.normalizeHex(groupColor.apply(key)),
            new ConnectionMapPdfWriter.Labels(I18n.get("connectionManager.map.you"),
                I18n.get("connectionManager.map.layer.jump"), I18n.get("connectionManager.map.layer.tunnel"),
                I18n.get("connectionManager.tab.teamwork"), I18n.get("connectionManager.map.external"),
                I18n.get("connectionManager.noFolder")));
    }

    /** A badge node drawn into an image for the PDF, or null. */
    private static java.awt.image.BufferedImage badgeImage(Node badge) {
        if (badge == null) {
            return null;
        }
        javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        parameters.setTransform(javafx.scene.transform.Transform.scale(4, 4));
        // A badge outside any scene is styled and laid out for the snapshot by a throwaway group.
        Group holder = new Group(badge);
        new javafx.scene.Scene(holder);
        holder.applyCss();
        holder.layout();
        return javafx.embed.swing.SwingFXUtils.fromFXImage(holder.snapshot(parameters, null), null);
    }

    private void info(javafx.scene.control.Alert.AlertType type, String text) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(type, text, ButtonType.OK);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(getScene() != null ? getScene().getWindow() : null);
        alert.setHeaderText(null);
        alert.show();
    }

    private void zoom(double factor) {
        setZoom(scale.getX() * factor);
    }

    private void setZoom(double value) {
        double zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
        scale.setX(zoom);
        scale.setY(zoom);
        zoomLabel.setText(Math.round(zoom * 100) + " %");
    }

    /** Scales the map so that all of it fits the visible area, at most to 100 %. */
    private void fit() {
        if (layout == null || scroller.getViewportBounds() == null) {
            return;
        }
        double zoom = Math.min(scroller.getViewportBounds().getWidth() / layout.width(),
            scroller.getViewportBounds().getHeight() / layout.height());
        setZoom(Math.min(1, zoom));
    }
}
