package de.kortty.ui;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.GroupPath;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows of the Connection Manager: a connection's protocol glyph, name, address, tag and the
 * marks for jump server, tunnels, isolation, incognito and relaxed host-key checks, and a folder's
 * glyph in its tab color. Every text and glyph contour follows the colour it is given (a cell's
 * text fill), so the rows read in the Normal design and in every AtlantaFX design. FX thread only.
 */
final class ConnectionRowGraphics {

    /** A connection with at least one open tab. */
    static final String OPEN_COLOR = "#3fb68b";

    private ConnectionRowGraphics() { }

    /**
     * The toolbar glyph for the connection's protocol: a server for SSH, waves for Mosh, a laptop for a
     * local shell, a DE-9 plug for a serial port.
     */
    static String glyphFor(ServerConnection connection) {
        ConnectionProtocol protocol = connection != null ? connection.getProtocol() : null;
        if (protocol == ConnectionProtocol.LOCAL_SHELL) {
            return "localShell";
        }
        if (protocol == ConnectionProtocol.SERIAL) {
            return "serial";
        }
        if (protocol == ConnectionProtocol.MOSH || protocol == ConnectionProtocol.MOSH_CLIENT) {
            return "mosh";
        }
        return "server";
    }

    /** {@code user@host}, with {@code :port} when it is not 22, a local shell's command, or {@code COM3 · 9600 8N1}. */
    static String address(ServerConnection connection) {
        if (connection == null) {
            return "";
        }
        if (connection.getProtocol() == ConnectionProtocol.LOCAL_SHELL) {
            String command = connection.getLocalShellCommand();
            return command != null && !command.isBlank() ? command : I18n.get("protocol.localShell");
        }
        if (connection.isSerial()) {
            de.kortty.model.SerialSettings serial = connection.getSerialSettings();
            String port = serial.getPortName() != null ? serial.getPortName() : I18n.get("protocol.serial");
            return port + " · " + serial.frameSummary();
        }
        String host = connection.getHost() != null ? connection.getHost() : "";
        String user = connection.getUsername();
        String address = user != null && !user.isBlank() ? user + "@" + host : host;
        return connection.getPort() > 0 && connection.getPort() != 22 ? address + ":" + connection.getPort() : address;
    }

    /** The connection's enabled tunnels. */
    static List<SSHTunnel> enabledTunnels(ServerConnection connection) {
        List<SSHTunnel> tunnels = new ArrayList<>();
        if (connection != null && connection.getSshTunnels() != null) {
            for (SSHTunnel tunnel : connection.getSshTunnels()) {
                if (tunnel != null && tunnel.isEnabled()) {
                    tunnels.add(tunnel);
                }
            }
        }
        return tunnels;
    }

    /** {@code L 15432 → db:5432}, {@code R 8080 → localhost:80} or {@code D 1080 (SOCKS)}. */
    static String describeTunnel(SSHTunnel tunnel) {
        if (tunnel == null || tunnel.getType() == null) {
            return "";
        }
        return switch (tunnel.getType()) {
            case LOCAL -> "L " + tunnel.getLocalPort() + " → " + tunnel.getRemoteHost() + ":" + tunnel.getRemotePort();
            case REMOTE -> "R " + tunnel.getRemotePort() + " → " + tunnel.getLocalHost() + ":" + tunnel.getLocalPort();
            case DYNAMIC -> "D " + tunnel.getLocalPort() + " (SOCKS)";
        };
    }

    /** The first enabled tunnel and how many more there are, or null without any. */
    static String tunnelSummary(ServerConnection connection) {
        List<SSHTunnel> tunnels = enabledTunnels(connection);
        if (tunnels.isEmpty()) {
            return null;
        }
        String first = describeTunnel(tunnels.get(0));
        return tunnels.size() == 1 ? first : first + " +" + (tunnels.size() - 1);
    }

    /** The jump server's {@code host:port} when the connection uses an enabled one, else null. */
    static String jumpTarget(ServerConnection connection) {
        if (connection == null || connection.getJumpServer() == null || !connection.getJumpServer().isEnabled()) {
            return null;
        }
        String host = connection.getJumpServer().getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        int port = connection.getJumpServer().getPort();
        return port > 0 && port != 22 ? host.trim() + ":" + port : host.trim();
    }

    /**
     * When something happened, said the way people say it: just now, so many minutes or hours ago,
     * yesterday, so many days ago, else the date. {@code never} for 0.
     */
    static String relativeTime(long millis, long now) {
        if (millis <= 0) {
            return I18n.get("connectionManager.inspector.never");
        }
        long minutes = Math.max(0, (now - millis) / 60_000L);
        if (minutes < 1) {
            return I18n.get("connectionManager.time.now");
        }
        if (minutes < 60) {
            return I18n.get("connectionManager.time.minutes", minutes);
        }
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        java.time.LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long days = java.time.temporal.ChronoUnit.DAYS.between(day, today);
        if (days == 0) {
            return I18n.get("connectionManager.time.hours", Math.max(1, minutes / 60));
        }
        if (days == 1) {
            return I18n.get("connectionManager.time.yesterday");
        }
        if (days < 7) {
            return I18n.get("connectionManager.time.days", days);
        }
        return day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM));
    }

    /** The colour {@code fill} at {@code opacity}, for muted text and glyph contours. */
    static ObservableValue<Paint> muted(ObservableValue<? extends Paint> fill, double opacity) {
        return Bindings.createObjectBinding(() -> {
            Paint paint = fill.getValue();
            return paint instanceof Color color ? color.deriveColor(0, 1, 1, opacity) : paint;
        }, fill);
    }

    /** A label whose text colour follows {@code fill}. */
    static Label label(String text, ObservableValue<? extends Paint> fill, String style) {
        Label label = new Label(text);
        label.textFillProperty().bind(fill);
        if (style != null) {
            label.setStyle(style);
        }
        return label;
    }

    /** A small glyph with a tooltip that names it, which screen readers read as well. */
    static Node mark(String glyph, ObservableValue<? extends Paint> fill, String text) {
        StackPane mark = new StackPane(ToolbarIcons.create(glyph, fill, 14));
        mark.setAccessibleText(text);
        Tooltip.install(mark, new Tooltip(text));
        return mark;
    }

    /** The protocol glyph on a small tile, with a green dot while the connection has an open tab. */
    static StackPane protocolTile(ServerConnection connection, ObservableValue<? extends Paint> fill,
                                  double size, int openTabs) {
        return protocolTile(connection, fill, size, openTabs, null);
    }

    /** The protocol tile with the connection's OS badge on its lower left corner; null for none. */
    static StackPane protocolTile(ServerConnection connection, ObservableValue<? extends Paint> fill,
                                  double size, int openTabs, Node badge) {
        Node glyph = ToolbarIcons.create(glyphFor(connection), fill, size);
        StackPane tile = new StackPane(glyph);
        tile.getStyleClass().add("cm-tile");
        double box = size + 12;
        tile.setMinSize(box, box);
        tile.setPrefSize(box, box);
        tile.setMaxSize(box, box);
        if (openTabs > 0) {
            Circle dot = new Circle(4, Color.web(OPEN_COLOR));
            dot.setStroke(Color.web("#00000040"));
            StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(dot, new Insets(0, -2, -2, 0));
            tile.getChildren().add(dot);
        }
        if (badge != null) {
            StackPane.setAlignment(badge, Pos.BOTTOM_LEFT);
            StackPane.setMargin(badge, new Insets(0, 0, -3, -3));
            tile.getChildren().add(badge);
        }
        return tile;
    }

    /** A tag shown as a small outlined chip with the tag glyph. */
    static Node tagChip(String tag, ObservableValue<? extends Paint> fill) {
        HBox chip = new HBox(4, ToolbarIcons.create("tag", fill, 12), label(tag, fill, "-fx-font-size: 0.85em;"));
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("cm-chip");
        chip.setStyle("-fx-border-color: rgba(128, 128, 128, 0.45); -fx-border-radius: 5; -fx-padding: 1 6 1 5;");
        chip.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return chip;
    }

    /**
     * A connection's row: tile, name over address, tag chip and marks, a filled star for a favorite.
     *
     * @param openTabs the connection's open tabs in every window
     */
    static HBox connectionRow(ServerConnection connection, ObservableValue<? extends Paint> fill,
                              boolean favorite, int openTabs, boolean teamwork, Node badge) {
        ObservableValue<Paint> dim = muted(fill, 0.62);
        Label name = label(connection.getName(), fill, "-fx-font-weight: bold;");
        Label address = label(address(connection), dim, "-fx-font-size: 0.85em;");
        VBox text = new VBox(1, name, address);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox row = new HBox(9, protocolTile(connection, fill, 16, openTabs, badge), text);
        row.setAlignment(Pos.CENTER_LEFT);
        // Chips and marks keep their own height instead of stretching to the two lines of text.
        row.setFillHeight(false);
        row.getStyleClass().add("cm-row");
        if (connection.getTag() != null && !connection.getTag().isBlank()) {
            row.getChildren().add(tagChip(connection.getTag(), dim));
        }
        row.getChildren().addAll(marks(connection, dim));
        if (favorite) {
            row.getChildren().add(mark("star", fill, I18n.get("connectionManager.favorite")));
        }
        if (teamwork) {
            // Subtle on purpose: a dimmed people glyph tells a shared entry from a local one.
            Node shared = mark("teamwork", muted(fill, 0.55), I18n.get("connectionManager.row.teamwork"));
            shared.setOpacity(0.8);
            row.getChildren().add(shared);
        }
        if (openTabs > 0) {
            row.getChildren().add(label(I18n.get("connectionManager.row.open", openTabs), dim, "-fx-font-size: 0.85em;"));
        }
        return row;
    }

    /** The marks after a connection's name: jump server, tunnels, isolation, incognito, relaxed host-key check. */
    static List<Node> marks(ServerConnection connection, ObservableValue<? extends Paint> fill) {
        List<Node> marks = new ArrayList<>();
        String jump = jumpTarget(connection);
        if (jump != null) {
            marks.add(mark("jump", fill, I18n.get("connectionManager.row.jump", jump)));
        }
        int tunnels = enabledTunnels(connection).size();
        if (tunnels > 0) {
            marks.add(mark("tunnel", fill, I18n.get("connectionManager.row.tunnels", tunnels)));
        }
        if (connection.getIsolationLevel() != null) {
            marks.add(mark("isolation", fill, I18n.get("connectionManager.row.isolation",
                IsolationConnectionSupport.levelName(connection.getIsolationLevel()))));
        }
        if (connection.isIncognito()) {
            marks.add(mark("incognito", fill, I18n.get("connectionManager.row.incognito")));
        }
        if (Boolean.TRUE.equals(connection.getDisableHostKeyCheck())) {
            marks.add(mark("hostKey", fill, I18n.get("connectionManager.row.hostKeyOff")));
        }
        return marks;
    }

    /** A folder's row: the folder glyph with its tab color as core, its name and how many connections it holds. */
    static HBox groupRow(GroupPath group, String displayName, String color, int connections,
                         ObservableValue<? extends Paint> fill, String glyph) {
        HBox row = new HBox(8, ToolbarIcons.create(glyph, fill, 17, color),
            label(displayName, fill, "-fx-font-weight: bold;"),
            label(String.valueOf(connections), muted(fill, 0.55), "-fx-font-size: 0.85em;"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setFillHeight(false);
        row.getStyleClass().add("cm-row");
        row.setAccessibleText(group != null ? group.getPath() : displayName);
        return row;
    }

    /** Where a parent folder's glyph is centred in its row: the 17-unit folder glyph comes first. */
    private static final double PARENT_GLYPH_CENTER = 8.5;

    /**
     * Draws the tree's connector lines into {@code row}: an elbow from the parent folder's glyph to this
     * entry, straight down past the entry when siblings follow, and the through lines of the folders
     * further up that still have entries below. The lines sit left of the row, in the tree's indent;
     * they reach over the cell's padding so the lines of neighbouring rows meet. Top-level entries get none.
     *
     * @param indent the tree's folder indent ({@code -fx-indent}), which the parent's glyph is offset by
     */
    static void addConnector(HBox row, javafx.scene.control.TreeItem<?> item, javafx.scene.control.TreeItem<?> root,
                             double indent, ObservableValue<Insets> padding, ObservableValue<? extends Paint> fill) {
        javafx.scene.control.TreeItem<?> parent = item != null ? item.getParent() : null;
        if (parent == null || parent == root) {
            return;
        }
        ObservableValue<Paint> stroke = muted(fill, 0.32);
        javafx.beans.binding.DoubleBinding top = javafx.beans.binding.Bindings.createDoubleBinding(
            () -> padding.getValue() != null ? -padding.getValue().getTop() : 0, padding);
        javafx.beans.binding.DoubleBinding bottom = javafx.beans.binding.Bindings.createDoubleBinding(
            () -> row.getHeight() + (padding.getValue() != null ? padding.getValue().getBottom() : 0),
            row.heightProperty(), padding);
        javafx.beans.binding.DoubleBinding middle = row.heightProperty().divide(2.0);
        javafx.scene.layout.Pane lines = new javafx.scene.layout.Pane();
        lines.setManaged(false);
        lines.setMouseTransparent(true);

        boolean last = parent.getChildren().indexOf(item) == parent.getChildren().size() - 1;
        double x = -indent + PARENT_GLYPH_CENTER;
        lines.getChildren().add(line(x, top, x, last ? middle : bottom, stroke));
        // A folder's elbow stops before its disclosure arrow, an entry's reaches its tile.
        lines.getChildren().add(line(x, middle, item.isLeaf() ? -3 : -indent + 10, middle, stroke));
        javafx.scene.control.TreeItem<?> ancestor = parent;
        for (int level = 2; ancestor.getParent() != null && ancestor.getParent() != root; level++) {
            javafx.scene.control.TreeItem<?> up = ancestor.getParent();
            if (up.getChildren().indexOf(ancestor) < up.getChildren().size() - 1) {
                double through = -level * indent + PARENT_GLYPH_CENTER;
                lines.getChildren().add(line(through, top, through, bottom, stroke));
            }
            ancestor = up;
        }
        row.getChildren().add(0, lines);
    }

    private static javafx.scene.shape.Line line(double startX, ObservableValue<? extends Number> startY,
                                                double endX, ObservableValue<? extends Number> endY,
                                                ObservableValue<Paint> stroke) {
        javafx.scene.shape.Line line = new javafx.scene.shape.Line();
        line.setStartX(startX);
        line.setEndX(endX);
        line.startYProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> startY.getValue().doubleValue(), startY));
        line.endYProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> endY.getValue().doubleValue(), endY));
        line.strokeProperty().bind(stroke);
        line.setStrokeWidth(1);
        return line;
    }
}
