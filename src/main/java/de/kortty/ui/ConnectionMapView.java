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

    private List<ServerConnection> connections = List.of();
    private Function<String, String> groupColor = group -> null;
    private ToIntFunction<ServerConnection> openTabs = connection -> 0;
    private boolean readOnly;
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
        zoomLabel.setMinWidth(Region.USE_PREF_SIZE);
        for (javafx.scene.control.Control control : List.of(jumpLayer, tunnelLayer, zoomOut, zoomIn, fit)) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
        Label hint = new Label(I18n.get("connectionManager.map.hint"));
        hint.setStyle("-fx-opacity: 0.7; -fx-font-size: 0.9em;");
        hint.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, jumpLayer, tunnelLayer, spacer, hint, zoomOut, zoomLabel, zoomIn, fit);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 8));
        bar.getStyleClass().add("cm-map-bar");
        setTop(bar);
        setCenter(scroller);
    }

    void setOnSelect(Consumer<ServerConnection> handler) { this.onSelect = handler; }

    void setOnOpen(Consumer<ServerConnection> handler) { this.onOpen = handler; }

    /** Hands a connection dragged into another zone and that zone's folder path to {@code handler}. */
    void setOnMove(BiConsumer<ServerConnection, String> handler) { this.onMove = handler; }

    /**
     * Shows {@code shown} on the map.
     *
     * @param color    the tab color a folder shows ({@code #RRGGBB}) or null, by folder path
     * @param tabs     how many tabs a connection has open
     * @param readOnly true for teamwork connections, which cannot be moved
     */
    void show(List<ServerConnection> shown, Function<String, String> color, ToIntFunction<ServerConnection> tabs,
              boolean readOnly) {
        this.connections = List.copyOf(shown);
        this.groupColor = color != null ? color : group -> null;
        this.openTabs = tabs != null ? tabs : connection -> 0;
        this.readOnly = readOnly;
        rebuild();
    }

    /** Highlights {@code connections} as the selection. */
    void setSelection(Collection<ServerConnection> connections) {
        selected.clear();
        selected.addAll(connections);
        nodeViews.forEach((connection, view) -> styleNode(view, selected.contains(connection), false));
    }

    private void rebuild() {
        nodeViews.clear();
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
            entries.add(new ConnectionMapLayout.Entry(id, connection.getGroup(), connection.getHost(),
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
        String color = external ? null : de.kortty.core.ConnectionColorSupport.normalizeHex(groupColor.apply(zone.key()));
        String stroke = color != null ? color : NEUTRAL;
        String fill = color != null ? color + "14" : "rgba(128, 128, 128, 0.05)";
        Region area = new Region();
        area.setStyle("-fx-border-color: " + stroke + "; -fx-border-style: segments(7, 5); -fx-border-width: 1.5;"
            + " -fx-border-radius: 14; -fx-background-color: " + fill + "; -fx-background-radius: 14;");
        area.resizeRelocate(zone.x(), zone.y(), zone.width(), zone.height());
        area.setManaged(false);

        String title = external ? I18n.get("connectionManager.map.external")
            : zone.key().isEmpty() ? I18n.get("connectionManager.noFolder")
            : zone.key().replace(GroupPath.SEPARATOR, " / ");
        Label label = new Label(title, ToolbarIcons.create(external ? "jump" : "folder", themeText.textFillProperty(), 15, color));
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
            openTabs.applyAsInt(connection)), name);
        if (!de.kortty.policy.ServerAccessPolicy.isAllowed(connection)) {
            view.getChildren().add(ToolbarIcons.create("blocked", name.textFillProperty(), 14));
            view.setOpacity(0.55);
        }
        view.setAccessibleText(connection.getName() + ", " + ConnectionRowGraphics.address(connection));
        Tooltip.install(view, new Tooltip(tooltip(connection)));
        view.setCursor(javafx.scene.Cursor.HAND);
        nodeViews.put(connection, view);
        styleNode(view, selected.contains(connection), false);
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
            if (readOnly || onMove == null || event.getButton() != MouseButton.PRIMARY) {
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
            view.setOpacity(target != null && !target.key().equals(node.zone())
                && !ConnectionMapLayout.EXTERNAL_ZONE.equals(target.key()) ? 0.85 : 1);
        });
        view.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            event.consume();
            if (!dragging[0]) {
                return;
            }
            dragging[0] = false;
            ConnectionMapLayout.Zone target = layout.zoneAt(view.getLayoutX() + ConnectionMapLayout.NODE_WIDTH / 2,
                view.getLayoutY() + ConnectionMapLayout.NODE_HEIGHT / 2);
            if (target != null && !target.key().equals(node.zone())
                    && !ConnectionMapLayout.EXTERNAL_ZONE.equals(target.key()) && onMove != null) {
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
