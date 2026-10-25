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
                 String isolation, String hostKey, String jumpName, String lastUsed, String source) { }

    /** What the dialog knows about a folder. */
    record FolderFacts(int connections, String color, String isolation, boolean hostKeyOff) { }

    private final VBox body = new VBox(14);
    /** The theme's text colour, which the glyph contours follow. */
    private final Label themeText = new Label();

    private Consumer<ServerConnection> onConnect;
    private Consumer<ServerConnection> onEdit;
    private Consumer<ServerConnection> onDuplicate;
    private Consumer<ServerConnection> onToggleFavorite;
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
        showEmpty();
    }

    void setOnConnect(Consumer<ServerConnection> handler) { onConnect = handler; }
    void setOnEdit(Consumer<ServerConnection> handler) { onEdit = handler; }
    void setOnDuplicate(Consumer<ServerConnection> handler) { onDuplicate = handler; }
    void setOnToggleFavorite(Consumer<ServerConnection> handler) { onToggleFavorite = handler; }
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
        Label name = new Label(connection.getName());
        name.setStyle("-fx-font-size: 1.35em; -fx-font-weight: bold;");
        name.setWrapText(true);
        Label address = new Label(ConnectionRowGraphics.address(connection));
        address.setStyle("-fx-opacity: 0.72;");
        address.setWrapText(true);
        StackPane tile = ConnectionRowGraphics.protocolTile(connection, themeText.textFillProperty(), 26, facts.openTabs());
        HBox header = new HBox(12, tile, new VBox(3, name, address));
        header.setAlignment(Pos.CENTER_LEFT);

        HBox chips = new HBox(6);
        chips.setAlignment(Pos.CENTER_LEFT);
        String group = connection.getGroup();
        chips.getChildren().add(chip("folder", group != null && !group.isBlank()
            ? group.replace(GroupPath.SEPARATOR, " / ") : I18n.get("connectionManager.noFolder"), facts.groupColor()));
        if (connection.getTag() != null && !connection.getTag().isBlank()) {
            chips.getChildren().add(ConnectionRowGraphics.tagChip(connection.getTag(), themeText.textFillProperty()));
        }

        Button connect = new Button(I18n.get("quickConnect.connect"),
            ToolbarIcons.create("connect", new javafx.beans.property.SimpleObjectProperty<>(javafx.scene.paint.Color.WHITE), 16));
        connect.getStyleClass().add("cm-primary");
        connect.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(connect, Priority.ALWAYS);
        connect.setOnAction(e -> run(onConnect, connection));
        HBox actions = new HBox(6, connect);
        actions.setAlignment(Pos.CENTER_LEFT);
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

        body.getChildren().setAll(header, chips, actions);
        if (facts.readOnly()) {
            Label note = new Label(I18n.get("connectionManager.inspector.readOnly", facts.source()),
                ToolbarIcons.create("teamwork", themeText.textFillProperty(), 15));
            note.setWrapText(true);
            note.setStyle("-fx-opacity: 0.8;");
            body.getChildren().add(note);
        }

        GridPane connectionFacts = facts();
        row(connectionFacts, "connectionManager.inspector.host",
            connection.getHost() != null && connection.getProtocol() != de.kortty.model.ConnectionProtocol.LOCAL_SHELL
                ? connection.getHost() : "–");
        row(connectionFacts, "connectionManager.inspector.user",
            connection.getUsername() != null && !connection.getUsername().isBlank() ? connection.getUsername() : "–");
        row(connectionFacts, "connectionManager.inspector.port",
            connection.getProtocol() == de.kortty.model.ConnectionProtocol.LOCAL_SHELL ? "–" : String.valueOf(connection.getPort()));
        row(connectionFacts, "connectionManager.inspector.protocol", protocolName(connection));
        row(connectionFacts, "connectionManager.inspector.auth", facts.auth());
        body.getChildren().add(section("connectionManager.inspector.section.connection", connectionFacts));

        GridPane route = facts();
        row(route, "connectionManager.inspector.jump", facts.jumpName() != null
            ? facts.jumpName() : I18n.get("connectionManager.inspector.direct"));
        List<SSHTunnel> tunnels = ConnectionRowGraphics.enabledTunnels(connection);
        row(route, "connectionManager.inspector.tunnels", tunnels.isEmpty() ? "–"
            : String.join("\n", tunnels.stream().map(ConnectionRowGraphics::describeTunnel).toList()));
        body.getChildren().add(section("connectionManager.inspector.section.route", route));

        GridPane security = facts();
        row(security, "connectionManager.inspector.isolation", facts.isolation());
        row(security, "connectionManager.inspector.incognito", I18n.get(connection.isIncognito()
            ? "connectionManager.inspector.yes" : "connectionManager.inspector.no"));
        row(security, "connectionManager.inspector.hostKey", facts.hostKey());
        body.getChildren().add(section("connectionManager.inspector.section.security", security));

        GridPane usage = facts();
        row(usage, "connectionManager.inspector.lastUsed", facts.lastUsed());
        row(usage, "connectionManager.inspector.uses", String.valueOf(connection.getUsageCount()));
        if (facts.openTabs() > 0) {
            row(usage, "connectionManager.inspector.openTabs", String.valueOf(facts.openTabs()));
        }
        body.getChildren().add(section("connectionManager.inspector.section.usage", usage));

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
            body.getChildren().add(section("connectionManager.inspector.section.quickEdit", quick));
        }
        setVvalue(0);
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

    private static void row(GridPane grid, String key, String value) {
        int row = grid.getRowCount();
        Label text = new Label(value != null ? value : "–");
        text.setWrapText(true);
        grid.add(caption(key), 0, row);
        grid.add(text, 1, row);
        GridPane.setHgrow(text, Priority.ALWAYS);
    }

    private static Node section(String key, Node content) {
        Label title = new Label(I18n.get(key));
        title.setStyle("-fx-font-size: 0.82em; -fx-font-weight: bold; -fx-opacity: 0.7;");
        return new VBox(6, title, content);
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
