package de.kortty.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Inline SVG glyphs for the file browser sidebar and the SFTP manager.
 * Tree icons use a 16x16 grid (matching the original folder/file glyphs),
 * toolbar icons a 24x24 grid (matching {@link ButtonIcons}). All glyphs are
 * rendered with the even-odd fill rule so overlapping subpaths become cut-outs.
 */
final class FileBrowserIcons {

    /** Broad file categories used to pick a tree icon. */
    enum IconKind {
        FOLDER, FOLDER_OPEN, CODE, IMAGE, ARCHIVE, DOC, EXECUTABLE, DEFAULT
    }

    // 16x16 tree glyphs
    static final String FOLDER = "M1 5 L1 14 L15 14 L15 4 L8 4 L7 2 L1 2 Z";
    static final String FOLDER_OPEN =
        "M1 3 H6 L7 5 H15 V6.5 H3.6 L1.6 12.5 H1 Z M3.9 7.5 H15.9 L13.9 13.9 H1.9 Z";
    static final String FILE = "M3 1 L10 1 L14 5 L14 15 L3 15 Z";
    static final String FILE_CODE =
        "M5 4 L1.5 8 L5 12 L6.2 10.9 L3.7 8 L6.2 5.1 Z M11 4 L14.5 8 L11 12 L9.8 10.9 L12.3 8 L9.8 5.1 Z";
    static final String FILE_IMAGE =
        "M1.5 13 L6 6.5 L9 10.2 L11 7.8 L14.5 13 Z M9.8 4.6 a1.7 1.7 0 1 0 3.4 0 a1.7 1.7 0 1 0 -3.4 0 Z";
    static final String FILE_ARCHIVE =
        "M2 2.5 H14 V6 H2 Z M3 7 H13 V13.8 H3 Z M6.3 8.3 H9.7 V10 H6.3 Z";
    static final String FILE_DOC =
        "M2.5 3.5 H13.5 V5 H2.5 Z M2.5 7 H13.5 V8.5 H2.5 Z M2.5 10.5 H10 V12 H2.5 Z";
    static final String FILE_EXECUTABLE =
        "M2.5 4 L6 8 L2.5 12 L3.7 13.1 L8.3 8 L3.7 2.9 Z M9 11.3 H13.8 V12.9 H9 Z";
    static final String LINK_BADGE =
        "M9.4 9.4 H15.4 V15.4 H13.6 V12.5 L10.9 15.2 L9.6 13.9 L12.3 11.2 H9.4 Z";

    // 24x24 toolbar glyphs (used with ButtonIcons-style flat buttons)
    static final String BACK = "M15.41 7.41 L14 6 L8 12 L14 18 L15.41 16.59 L10.83 12 Z";
    static final String FORWARD = "M8.59 7.41 L10 6 L16 12 L10 18 L8.59 16.59 L13.17 12 Z";
    static final String UP =
        "M11 20 H13 V8.83 L16.59 12.41 L18 11 L12 5 L6 11 L7.41 12.41 L11 8.83 Z";
    static final String HOME = "M12 3 L2 12 H5 V21 H10 V14 H14 V21 H19 V12 H22 Z";
    static final String NEW_FOLDER =
        "M2 6 C2 4.9 2.9 4 4 4 H9 L11 6 H20 C21.1 6 22 6.9 22 8 V18 C22 19.1 21.1 20 20 20 H4 "
        + "C2.9 20 2 19.1 2 18 Z M11 9 H13 V12 H16 V14 H13 V17 H11 V14 H8 V12 H11 Z";
    static final String NEW_FILE =
        "M6 2 H14 L19 7 V22 H6 Z M13 3.5 V8 H17.5 Z "
        + "M11.5 11 H13.5 V14 H16.5 V16 H13.5 V19 H11.5 V16 H8.5 V14 H11.5 Z";
    static final String EYE =
        "M12 5 C7 5 2.7 7.9 1 12 C2.7 16.1 7 19 12 19 C17 19 21.3 16.1 23 12 C21.3 7.9 17 5 12 5 Z "
        + "M7.8 12 a4.2 4.2 0 1 0 8.4 0 a4.2 4.2 0 1 0 -8.4 0 Z "
        + "M10.2 12 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0 Z";
    static final String EYE_OFF =
        "M12 5 C7 5 2.7 7.9 1 12 C2.7 16.1 7 19 12 19 C17 19 21.3 16.1 23 12 C21.3 7.9 17 5 12 5 Z "
        + "M7.8 12 a4.2 4.2 0 1 0 8.4 0 a4.2 4.2 0 1 0 -8.4 0 Z "
        + "M3.3 4.7 L4.7 3.3 L20.7 19.3 L19.3 20.7 Z";
    static final String UPLOAD =
        "M11 16 H13 V6.83 L16.59 10.41 L18 9 L12 3 L6 9 L7.41 10.41 L11 6.83 Z M5 18 H19 V20 H5 Z";
    static final String LOCK =
        "M6 10 V8 A6 6 0 0 1 18 8 V10 H19 V21 H5 V10 Z M8 10 H16 V8 A4 4 0 0 0 8 8 Z "
        + "M11 14 H13 V18 H11 Z";
    static final String ARCHIVE_BOX =
        "M3 4 H21 V8 H3 Z M4 9 H20 V20 H4 Z M9.5 11 H14.5 V13 H9.5 Z";

    private static final Set<String> CODE_EXTENSIONS = Set.of(
        "java", "kt", "kts", "py", "js", "ts", "jsx", "tsx", "mjs", "sh", "bash", "zsh",
        "c", "h", "cpp", "hpp", "cc", "cs", "rs", "go", "rb", "php", "swift", "css",
        "scss", "html", "htm", "sql", "pl", "lua", "gradle", "bat", "ps1");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "bmp", "tif", "tiff", "heic");
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
        "zip", "tar", "gz", "tgz", "7z", "rar", "jar", "war", "bz2", "xz", "zst",
        "dmg", "iso", "deb", "rpm");
    private static final Set<String> DOC_EXTENSIONS = Set.of(
        "txt", "md", "pdf", "doc", "docx", "odt", "rtf", "log", "properties", "json",
        "yaml", "yml", "xml", "csv", "tsv", "ini", "conf", "toml");

    private static final Map<IconKind, String> TREE_PATHS = Map.of(
        IconKind.FOLDER, FOLDER,
        IconKind.FOLDER_OPEN, FOLDER_OPEN,
        IconKind.CODE, FILE_CODE,
        IconKind.IMAGE, FILE_IMAGE,
        IconKind.ARCHIVE, FILE_ARCHIVE,
        IconKind.DOC, FILE_DOC,
        IconKind.EXECUTABLE, FILE_EXECUTABLE,
        IconKind.DEFAULT, FILE);

    private FileBrowserIcons() {
    }

    /** Categorizes a plain file by its extension; directories use {@link #kindFor(String, boolean, boolean, boolean)}. */
    static IconKind kindFor(String fileName) {
        String extension = extensionOf(fileName);
        if (CODE_EXTENSIONS.contains(extension)) {
            return IconKind.CODE;
        }
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return IconKind.IMAGE;
        }
        if (ARCHIVE_EXTENSIONS.contains(extension)) {
            return IconKind.ARCHIVE;
        }
        if (DOC_EXTENSIONS.contains(extension)) {
            return IconKind.DOC;
        }
        return IconKind.DEFAULT;
    }

    static IconKind kindFor(String fileName, boolean directory, boolean expanded, boolean executable) {
        if (directory) {
            return expanded ? IconKind.FOLDER_OPEN : IconKind.FOLDER;
        }
        IconKind byExtension = kindFor(fileName);
        if (byExtension == IconKind.DEFAULT && executable) {
            return IconKind.EXECUTABLE;
        }
        return byExtension;
    }

    static String treeIconPath(IconKind kind) {
        return TREE_PATHS.get(kind);
    }

    /** Builds a 16-grid tree icon; {@code symlink} adds a corner arrow badge. */
    static Node treeIcon(IconKind kind, String colorHex, boolean dimmed, boolean symlink, String badgePlateHex) {
        SVGPath icon = glyph(treeIconPath(kind), colorHex);
        icon.setScaleX(0.9);
        icon.setScaleY(0.9);
        if (!symlink) {
            icon.setOpacity(dimmed ? 0.75 : 1.0);
            return icon;
        }
        SVGPath plate = glyph("M8.4 8.4 H16 V16 H8.4 Z", badgePlateHex);
        SVGPath badge = glyph(LINK_BADGE, colorHex);
        Group group = new Group(icon, plate, badge);
        group.setOpacity(dimmed ? 0.75 : 1.0);
        return group;
    }

    /** Styles a toolbar button flat and installs a 24-grid glyph tinted with {@code colorHex}. */
    static void applyToolbarIcon(ButtonBase button, String pathData, String colorHex) {
        SVGPath icon = glyph(pathData, colorHex);
        icon.setScaleX(0.6);
        icon.setScaleY(0.6);
        button.setGraphic(icon);
    }

    /** Re-tints every toolbar glyph below {@code root}, e.g. after an app-design change. */
    static void retintGlyphs(Node root, String colorHex) {
        if (root instanceof SVGPath svg) {
            svg.setFill(Color.web(colorHex));
        } else if (root instanceof ButtonBase button && button.getGraphic() != null) {
            retintGlyphs(button.getGraphic(), colorHex);
        } else if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                retintGlyphs(child, colorHex);
            }
        }
    }

    private static SVGPath glyph(String pathData, String colorHex) {
        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.setFillRule(FillRule.EVEN_ODD);
        path.setFill(Color.web(colorHex));
        return path;
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
