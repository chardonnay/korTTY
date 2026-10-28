package de.kortty.ui;

import de.kortty.model.GroupPath;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Connection Manager's inspector: everything about the selected connection without opening the
 * editor (address, sign-in, route, security, usage), its main actions, and quick edits of its name
 * and tag; or the selected folder with its own actions. Teamwork connections are shown read-only.
 * The dialog works out the texts ({@link Facts}); this pane only lays them out. FX thread only.
 */
final class ConnectionInspectorPane extends ScrollPane {

    /** What the dialog knows about a connection beyond the connection itself. */
    record Facts(boolean favorite, int openTabs, boolean readOnly, String groupColor, String auth,
                 String isolation, String hostKey, String jumpName, String lastUsed, String source,
                 int[] usageDays, String lastUsedRelative, Node badge, String badgeName) { }

    /** What the dialog knows about a folder. */
    record FolderFacts(int connections, String color, String isolation, boolean hostKeyOff) { }

    private final VBox body = new VBox(18);
    /** The theme's text colour, which the glyph contours follow. */
    private final Label themeText = new Label();

    private Consumer<ServerConnection> onConnect;
    private Consumer<ServerConnection> onEdit;
    private Consumer<ServerConnection> onDuplicate;
    private Consumer<ServerConnection> onToggleFavorite;
    private Consumer<ServerConnection> onSftp;
    private BiConsumer<ServerConnection, String> onRename;
    private BiConsumer<ServerConnection, String> onRetag;
    private Consumer<GroupPath> onRenameGroup;
    private Consumer<GroupPath> onEditGroupColor;
    private Consumer<GroupPath> onCreateSubgroup;

    ConnectionInspectorPane() {
        getStyleClass().add("cm-inspector");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        body.setPadding(new Insets(16, 16, 16, 16));
        themeText.setManaged(false);
        themeText.setVisible(false);
        setContent(new StackPane(themeText, body));
        body.setMinWidth(0);
        showEmpty();
    }

    void setOnConnect(Consumer<ServerConnection> handler) { onConnect = handler; }
    void setOnEdit(Consumer<ServerConnection> handler) { onEdit = handler; }
    void setOnDuplicate(Consumer<ServerConnection> handler) { onDuplicate = handler; }
    void setOnToggleFavorite(Consumer<ServerConnection> handler) { onToggleFavorite = handler; }
    void setOnSftp(Consumer<ServerConnection> handler) { onSftp = handler; }
    void setOnRename(BiConsumer<ServerConnection, String> handler) { onRename = handler; }
    void setOnRetag(BiConsumer<ServerConnection, String> handler) { onRetag = handler; }
    void setOnRenameGroup(Consumer<GroupPath> handler) { onRenameGroup = handler; }
    void setOnEditGroupColor(Consumer<GroupPath> handler) { onEditGroupColor = handler; }
    void setOnCreateSubgroup(Consumer<GroupPath> handler) { onCreateSubgroup = handler; }

    /** Shows the hint to select something. */
    void showEmpty() {
        Label hint = new Label(I18n.get("connectionManager.inspector.empty"));
        hint.setWrapText(true);
        hint.setStyle("-fx-opacity: 0.7;");
        body.getChildren().setAll(hint);
    }

    /** Shows {@code connection}. */
    void showConnection(ServerConnection connection, Facts facts) {
        body.getChildren().setAll(header(connection, facts), actions(connection, facts));
        if (facts.readOnly()) {
            Label note = new Label(I18n.get("connectionManager.inspector.readOnly", facts.source()),
                ToolbarIcons.create("teamwork", themeText.textFillProperty(), 15));
            note.setWrapText(true);
            note.setStyle("-fx-opacity: 0.8;");
            body.getChildren().add(note);
        }

        boolean local = !connection.isNetworkConnection();
        GridPane connectionFacts = facts();
        Label host = row(connectionFacts, "connectionManager.inspector.host",
            connection.getHost() != null && !local ? connection.getHost() : "–");
        host.setStyle("-fx-font-family: '" + monoFamily() + "';");
        row(connectionFacts, "connectionManager.inspector.user",
            !connection.isSerial() && connection.getUsername() != null && !connection.getUsername().isBlank()
                ? connection.getUsername() : "–");
        row(connectionFacts, "connectionManager.inspector.port", local ? "–" : String.valueOf(connection.getPort()));
        if (connection.isSerial()) {
            de.kortty.model.SerialSettings serial = connection.getSerialSettings();
            Label serialPort = row(connectionFacts, "connectionManager.inspector.serialPort",
                serial.getPortName() != null ? serial.getPortName() : "–");
            serialPort.setStyle("-fx-font-family: '" + monoFamily() + "';");
            row(connectionFacts, "connectionManager.inspector.serialLine", serial.frameSummary());
        }
        row(connectionFacts, "connectionManager.inspector.protocol", protocolName(connection));
        row(connectionFacts, "connectionManager.inspector.auth", facts.auth());
        if (facts.badgeName() != null) {
            row(connectionFacts, "connectionManager.inspector.os", facts.badgeName());
        }
        body.getChildren().add(section("connectionManager.inspector.section.connection", connectionFacts));

        body.getChildren().add(section("connectionManager.inspector.section.route", route(connection, facts)));
        body.getChildren().add(section("connectionManager.inspector.section.security", security(connection, facts)));
        body.getChildren().add(section("connectionManager.inspector.section.usage", usage(connection, facts)));

        if (!facts.readOnly()) {
            TextField nameField = new TextField(connection.getName());
            commitOnEnterOrBlur(nameField, connection.getName(), value -> {
                if (!value.isBlank() && onRename != null) {
                    onRename.accept(connection, value.trim());
                }
            });
            TextField tagField = new TextField(connection.getTag() != null ? connection.getTag() : "");
            tagField.setPromptText(I18n.get("connectionManager.inspector.tagPrompt"));
            commitOnEnterOrBlur(tagField, connection.getTag() != null ? connection.getTag() : "", value -> {
                if (onRetag != null) {
                    onRetag.accept(connection, value.trim().isEmpty() ? null : value.trim());
                }
            });
            GridPane quick = facts();
            quick.add(caption("connectionManager.inspector.name"), 0, 0);
            quick.add(nameField, 1, 0);
            quick.add(caption("connectionManager.inspector.tag"), 0, 1);
            quick.add(tagField, 1, 1);
            GridPane.setHgrow(nameField, Priority.ALWAYS);
            GridPane.setHgrow(tagField, Priority.ALWAYS);
            if (badgePicker != null) {
                Node picker = badgePicker.apply(connection);
                if (picker != null) {
                    quick.add(caption("connectionManager.inspector.os"), 0, 2);
                    quick.add(picker, 1, 2);
                }
            }
            body.getChildren().add(section("connectionManager.inspector.section.quickEdit", quick));
        }
        setVvalue(0);
    }

    private static String monoFamily;

    /** The bundled Noto Sans Mono for addresses, loaded once; the platform's monospace font if it cannot load. */
    private static String monoFamily() {
        if (monoFamily == null) {
            try (var in = ConnectionInspectorPane.class.getResourceAsStream("/fonts/noto/NotoSansMono-Regular.ttf")) {
                javafx.scene.text.Font font = in != null ? javafx.scene.text.Font.loadFont(in, 12) : null;
                monoFamily = font != null ? font.getFamily() : "Monospaced";
            } catch (java.io.IOException e) {
                monoFamily = "Monospaced";
            }
        }
        return monoFamily;
    }

    /** Builds the OS badge picker of the quick edits for a connection; null leaves it out. */
    private java.util.function.Function<ServerConnection, Node> badgePicker;

    void setBadgePicker(java.util.function.Function<ServerConnection, Node> picker) { badgePicker = picker; }

    /** Tile with the protocol (and OS badge), the name and the folder, tag and teamwork chips. */
    private Node header(ServerConnection connection, Facts facts) {
        StackPane tile = ConnectionRowGraphics.protocolTile(connection, themeText.textFillProperty(), 30, facts.openTabs());
        tile.setMinSize(58, 58);
        tile.setPrefSize(58, 58);
        tile.setMaxSize(58, 58);
        if (facts.badge() != null) {
            StackPane.setAlignment(facts.badge(), Pos.BOTTOM_LEFT);
            StackPane.setMargin(facts.badge(), new Insets(0, 0, -4, -4));
            tile.getChildren().add(facts.badge());
        }
        Label name = new Label(connection.getName());
        name.setStyle("-fx-font-size: 1.4em; -fx-font-weight: bold;");
        name.setWrapText(true);
        javafx.scene.layout.FlowPane chips = new javafx.scene.layout.FlowPane(6, 6);
        String group = connection.getGroup();
        chips.getChildren().add(dotChip(group != null && !group.isBlank()
            ? group.replace(GroupPath.SEPARATOR, " / ") : I18n.get("connectionManager.noFolder"), facts.groupColor(), "folder"));
        if (connection.getTag() != null && !connection.getTag().isBlank()) {
            chips.getChildren().add(ConnectionRowGraphics.tagChip(connection.getTag(), themeText.textFillProperty()));
        }
        if (facts.readOnly()) {
            chips.getChildren().add(dotChip(I18n.get("connectionManager.tab.teamwork"), null, "teamwork"));
        }
        VBox text = new VBox(6, name, chips);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox header = new HBox(14, tile, text);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /** Connect, then SFTP, edit, duplicate and the favorite star as icon buttons. */
    private Node actions(ServerConnection connection, Facts facts) {
        Button connect = new Button(I18n.get("quickConnect.connect"),
            ToolbarIcons.create("connect", new javafx.beans.property.SimpleObjectProperty<>(javafx.scene.paint.Color.WHITE), 16));
        connect.getStyleClass().add("cm-primary");
        // Its label stays readable; the inspector's minimum width leaves room for the icon buttons beside it.
        connect.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        connect.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(connect, Priority.ALWAYS);
        connect.setOnAction(e -> run(onConnect, connection));
        HBox actions = new HBox(6, connect);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (onSftp != null && connection.isNetworkConnection()) {
            actions.getChildren().add(iconButton("sftp", I18n.get("connectionManager.inspector.sftp"), () -> run(onSftp, connection)));
        }
        if (!facts.readOnly()) {
            actions.getChildren().addAll(
                iconButton("edit", I18n.get("connectionManager.edit"), () -> run(onEdit, connection)),
                iconButton("duplicate", I18n.get("connectionManager.duplicate"), () -> run(onDuplicate, connection)));
        }
        ToggleButton favorite = new ToggleButton(I18n.get("connectionManager.favorite"),
            ToolbarIcons.create("star", themeText.textFillProperty(), 16));
        favorite.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        favorite.setSelected(facts.favorite());
        favorite.setTooltip(new Tooltip(I18n.get("connectionManager.favorite.toggle")));
        favorite.getStyleClass().add("cm-icon-button");
        favorite.setOnAction(e -> run(onToggleFavorite, connection));
        actions.getChildren().add(favorite);
        return actions;
    }

    /** This computer, the jump server and the connection as a chain, the tunnels below it. */
    private Node route(ServerConnection connection, Facts facts) {
        HBox chain = new HBox(8);
        chain.setAlignment(Pos.CENTER_LEFT);
        chain.getChildren().add(hop("localShell", I18n.get("connectionManager.map.you")));
        boolean viaJump = facts.jumpName() != null;
        chain.getChildren().add(link(viaJump));
        if (viaJump) {
            // The chain names the jump server short; its address is in the tooltip.
            int bracket = facts.jumpName().indexOf(" (");
            Label jump = hop("jump", bracket > 0 ? facts.jumpName().substring(0, bracket) : facts.jumpName());
            jump.setTooltip(new Tooltip(facts.jumpName()));
            chain.getChildren().addAll(jump, link(true));
        }
        Label target = hop(ConnectionRowGraphics.glyphFor(connection), connection.getName());
        target.setStyle("-fx-font-weight: bold;");
        chain.getChildren().add(target);
        VBox route = new VBox(6, chain);
        for (SSHTunnel tunnel : ConnectionRowGraphics.enabledTunnels(connection)) {
            Label line = new Label(ConnectionRowGraphics.describeTunnel(tunnel),
                ToolbarIcons.create("tunnel", themeText.textFillProperty(), 14));
            line.setStyle("-fx-opacity: 0.85;");
            route.getChildren().add(line);
        }
        return route;
    }

    private Label hop(String glyph, String text) {
        Label hop = new Label(text, ToolbarIcons.create(glyph, themeText.textFillProperty(), 16));
        // A narrow inspector shortens the names instead of growing wider than its column.
        hop.setMinWidth(40);
        hop.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        hop.setGraphicTextGap(6);
        return hop;
    }

    /** A dashed orange line for a hop through a jump server, a quiet solid one for a direct connection. */
    private static javafx.scene.layout.Region link(boolean jump) {
        javafx.scene.layout.Region link = new javafx.scene.layout.Region();
        link.setMinWidth(16);
        link.setPrefHeight(2);
        link.setMaxHeight(2);
        HBox.setHgrow(link, Priority.ALWAYS);
        link.setStyle(jump
            ? "-fx-border-color: #dc9825 transparent transparent transparent; -fx-border-width: 2 0 0 0;"
                + " -fx-border-style: segments(5, 4);"
            : "-fx-border-color: rgba(128, 128, 128, 0.55) transparent transparent transparent; -fx-border-width: 1.5 0 0 0;");
        return link;
    }

    /** Isolation, host-key check and incognito as chips. */
    private Node security(ServerConnection connection, Facts facts) {
        javafx.scene.layout.FlowPane chips = new javafx.scene.layout.FlowPane(6, 6);
        chips.getChildren().add(iconChip("isolation",
            I18n.get("connectionManager.inspector.isolationChip", facts.isolation())));
        chips.getChildren().add(iconChip("hostKey", I18n.get("connectionManager.inspector.hostKeyChip", facts.hostKey())));
        if (connection.isIncognito()) {
            chips.getChildren().add(iconChip("incognito", I18n.get("connectionManager.row.incognito")));
        }
        // A chip wider than the column is shortened with an ellipsis instead of being cut off at the edge.
        for (Node chip : chips.getChildren()) {
            Label label = (Label) chip;
            label.maxWidthProperty().bind(chips.widthProperty());
            label.setTooltip(new Tooltip(label.getText()));
        }
        return chips;
    }

    /** Bars for the connections of each of the last days, then how often and how recently. */
    private Node usage(ServerConnection connection, Facts facts) {
        HBox bars = new HBox(4);
        bars.setAlignment(Pos.BOTTOM_LEFT);
        bars.setMinHeight(42);
        bars.setPrefHeight(42);
        int[] days = facts.usageDays() != null ? facts.usageDays() : new int[0];
        int max = 1;
        for (int day : days) {
            max = Math.max(max, day);
        }
        for (int i = 0; i < days.length; i++) {
            javafx.scene.layout.Region bar = new javafx.scene.layout.Region();
            double height = days[i] == 0 ? 3 : 6 + 36.0 * days[i] / max;
            bar.setMinHeight(height);
            bar.setPrefHeight(height);
            bar.setMaxHeight(height);
            bar.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(bar, Priority.ALWAYS);
            bar.setStyle(days[i] == 0
                ? "-fx-background-color: rgba(128, 128, 128, 0.28); -fx-background-radius: 2;"
                : "-fx-background-color: rgba(54, 138, 245, 0.8); -fx-background-radius: 2;");
            Tooltip.install(bar, new Tooltip(I18n.get("connectionManager.inspector.dayBar",
                java.time.LocalDate.now().minusDays(days.length - 1L - i)
                    .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)),
                days[i])));
            bars.getChildren().add(bar);
        }
        String summary = connection.getUsageCount() == 0 && connection.getLastUsed() <= 0
            ? I18n.get("connectionManager.inspector.usageNever")
            : I18n.get("connectionManager.inspector.usageSummary", connection.getUsageCount(), facts.lastUsedRelative());
        Label text = new Label(facts.openTabs() > 0
            ? summary + " · " + I18n.get("connectionManager.row.open", facts.openTabs()) : summary);
        text.setWrapText(true);
        text.setStyle("-fx-opacity: 0.85;");
        if (facts.lastUsed() != null) {
            text.setTooltip(new Tooltip(facts.lastUsed()));
        }
        return days.length > 0 ? new VBox(8, bars, text) : text;
    }

    /** A chip with a coloured dot (grey without a colour) or a glyph, as in the header. */
    private Node dotChip(String text, String color, String glyph) {
        Node lead;
        if (color != null) {
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4, javafx.scene.paint.Color.web(color));
            lead = dot;
        } else {
            lead = ToolbarIcons.create(glyph, themeText.textFillProperty(), 13);
        }
        Label label = new Label(text, lead);
        label.setGraphicTextGap(6);
        label.setStyle("-fx-border-color: rgba(128, 128, 128, 0.45); -fx-border-radius: 6; -fx-padding: 2 8 2 7;"
            + " -fx-font-size: 0.92em;");
        label.setMinWidth(0);
        return label;
    }

    private Node iconChip(String glyph, String text) {
        Label label = new Label(text, ToolbarIcons.create(glyph, themeText.textFillProperty(), 15));
        label.setGraphicTextGap(7);
        label.setStyle("-fx-border-color: rgba(128, 128, 128, 0.45); -fx-border-radius: 7; -fx-padding: 4 9 4 7;");
        label.setMinWidth(0);
        return label;
    }

    /** Shows the folder {@code group}. */
    void showGroup(GroupPath group, FolderFacts facts, boolean readOnly) {
        Label name = new Label(group.getName());
        name.setStyle("-fx-font-size: 1.35em; -fx-font-weight: bold;");
        name.setWrapText(true);
        Label path = new Label(group.getPath().replace(GroupPath.SEPARATOR, " / "));
        path.setStyle("-fx-opacity: 0.72;");
        StackPane tile = new StackPane(ToolbarIcons.create("folder", themeText.textFillProperty(), 26, facts.color()));
        tile.getStyleClass().add("cm-tile");
        tile.setMinSize(38, 38);
        HBox header = new HBox(12, tile, new VBox(3, name, path));
        header.setAlignment(Pos.CENTER_LEFT);
        Label count = new Label(I18n.get("connectionManager.inspector.folderCount", facts.connections()));
        body.getChildren().setAll(header, count);
        if (!readOnly) {
            HBox actions = new HBox(6,
                iconButton("rename", I18n.get("connManager.renameFolder"), () -> runGroup(onRenameGroup, group)),
                iconButton("colors", I18n.get("connManager.group.tabColor"), () -> runGroup(onEditGroupColor, group)),
                iconButton("folderAdd", I18n.get("connManager.createSubfolder"), () -> runGroup(onCreateSubgroup, group)));
            body.getChildren().add(actions);
        }
        GridPane settings = facts();
        row(settings, "connectionManager.inspector.tabColor", facts.color() != null ? facts.color()
            : I18n.get("connectionManager.inspector.none"));
        row(settings, "connectionManager.inspector.isolation", facts.isolation());
        row(settings, "connectionManager.inspector.hostKey", I18n.get(facts.hostKeyOff()
            ? "connectionManager.inspector.hostKey.off" : "connectionManager.inspector.hostKey.on"));
        body.getChildren().add(section("connectionManager.inspector.section.folder", settings));
        setVvalue(0);
    }

    static String protocolName(ServerConnection connection) {
        if (connection.getProtocol() == null) {
            return I18n.get("protocol.sshTcp");
        }
        return switch (connection.getProtocol()) {
            case MOSH -> I18n.get("protocol.mosh");
            case MOSH_CLIENT -> I18n.get("protocol.moshClient");
            case LOCAL_SHELL -> I18n.get("protocol.localShell");
            case SERIAL -> I18n.get("protocol.serial");
            default -> I18n.get("protocol.sshTcp");
        };
    }

    private Node chip(String glyph, String text, String color) {
        Label label = new Label(text, ToolbarIcons.create(glyph, themeText.textFillProperty(), 13, color));
        label.setStyle("-fx-border-color: rgba(128, 128, 128, 0.45); -fx-border-radius: 5; -fx-padding: 1 6 1 5;"
            + " -fx-font-size: 0.9em;");
        label.setMinWidth(0);
        return label;
    }

    private Button iconButton(String glyph, String text, Runnable action) {
        Button button = new Button(text, ToolbarIcons.create(glyph, themeText.textFillProperty(), 16));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setTooltip(new Tooltip(text));
        button.setAccessibleText(text);
        button.getStyleClass().add("cm-icon-button");
        button.setOnAction(e -> action.run());
        return button;
    }

    private static GridPane facts() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        return grid;
    }

    private static Label caption(String key) {
        Label caption = new Label(I18n.get(key));
        caption.setStyle("-fx-opacity: 0.7;");
        caption.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return caption;
    }

    private static Label row(GridPane grid, String key, String value) {
        int row = grid.getRowCount();
        Label text = new Label(value != null ? value : "–");
        text.setWrapText(true);
        grid.add(caption(key), 0, row);
        grid.add(text, 1, row);
        GridPane.setHgrow(text, Priority.ALWAYS);
        return text;
    }

    private static Node section(String key, Node content) {
        Label title = new Label(I18n.get(key).toUpperCase(java.util.Locale.ROOT));
        title.setStyle("-fx-font-size: 0.8em; -fx-font-weight: bold; -fx-opacity: 0.6;");
        return new VBox(8, title, content);
    }

    private static void commitOnEnterOrBlur(TextField field, String original, Consumer<String> commit) {
        String[] last = {original};
        Runnable apply = () -> {
            String value = field.getText() != null ? field.getText() : "";
            if (!Objects.equals(value.trim(), last[0] != null ? last[0].trim() : "")) {
                last[0] = value;
                commit.accept(value);
            }
        };
        field.setOnAction(e -> apply.run());
        // Leaving the field keeps the edit, but not when another selection replaced the inspector's content.
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && field.getScene() != null) {
                apply.run();
            }
        });
    }

    private static void run(Consumer<ServerConnection> handler, ServerConnection connection) {
        if (handler != null) {
            handler.accept(connection);
        }
    }

    private static void runGroup(Consumer<GroupPath> handler, GroupPath group) {
        if (handler != null) {
            handler.accept(group);
        }
    }
}
