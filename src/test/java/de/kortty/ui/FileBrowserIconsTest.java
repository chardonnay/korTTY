package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.ui.FileBrowserIcons.IconKind;

class FileBrowserIconsTest {

    @Test
    void categorizesRepresentativeExtensions() {
        assertThat(FileBrowserIcons.kindFor("Main.java")).isEqualTo(IconKind.CODE);
        assertThat(FileBrowserIcons.kindFor("deploy.sh")).isEqualTo(IconKind.CODE);
        assertThat(FileBrowserIcons.kindFor("photo.JPG")).isEqualTo(IconKind.IMAGE);
        assertThat(FileBrowserIcons.kindFor("backup.tar")).isEqualTo(IconKind.ARCHIVE);
        assertThat(FileBrowserIcons.kindFor("app.jar")).isEqualTo(IconKind.ARCHIVE);
        assertThat(FileBrowserIcons.kindFor("notes.md")).isEqualTo(IconKind.DOC);
        assertThat(FileBrowserIcons.kindFor("config.yaml")).isEqualTo(IconKind.DOC);
        assertThat(FileBrowserIcons.kindFor("mystery.xyz")).isEqualTo(IconKind.DEFAULT);
    }

    @Test
    void treatsDotfilesAndExtensionlessNamesAsDefault() {
        assertThat(FileBrowserIcons.kindFor(".gitignore")).isEqualTo(IconKind.DEFAULT);
        assertThat(FileBrowserIcons.kindFor("Makefile")).isEqualTo(IconKind.DEFAULT);
        assertThat(FileBrowserIcons.kindFor("trailingdot.")).isEqualTo(IconKind.DEFAULT);
        assertThat(FileBrowserIcons.kindFor(null)).isEqualTo(IconKind.DEFAULT);
    }

    @Test
    void directoriesUseFolderGlyphs() {
        assertThat(FileBrowserIcons.kindFor("src", true, false, false)).isEqualTo(IconKind.FOLDER);
        assertThat(FileBrowserIcons.kindFor("src", true, true, false)).isEqualTo(IconKind.FOLDER_OPEN);
    }

    @Test
    void executableBitOnlyAffectsUncategorizedFiles() {
        assertThat(FileBrowserIcons.kindFor("run", false, false, true)).isEqualTo(IconKind.EXECUTABLE);
        assertThat(FileBrowserIcons.kindFor("run.sh", false, false, true)).isEqualTo(IconKind.CODE);
        assertThat(FileBrowserIcons.kindFor("run", false, false, false)).isEqualTo(IconKind.DEFAULT);
    }

    @Test
    void everyKindHasAGlyphPath() {
        for (IconKind kind : IconKind.values()) {
            assertThat(FileBrowserIcons.treeIconPath(kind)).isNotEmpty();
        }
    }

    @Test
    void extractsLowercaseExtensions() {
        assertThat(FileBrowserIcons.extensionOf("A.TXT")).isEqualTo("txt");
        assertThat(FileBrowserIcons.extensionOf("archive.tar.gz")).isEqualTo("gz");
        assertThat(FileBrowserIcons.extensionOf(".hidden")).isEmpty();
        assertThat(FileBrowserIcons.extensionOf("noext")).isEmpty();
    }
}
