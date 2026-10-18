package de.kortty.ui.sftp;

import de.kortty.ui.sftp.SftpBreadcrumbBar.Crumb;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SftpBreadcrumbBarTest {

    @Test
    void unixPathsStartAtTheRoot() {
        assertThat(SftpBreadcrumbBar.crumbs("/home/demo/www", false)).containsExactly(
            new Crumb("/", "/"),
            new Crumb("home", "/home"),
            new Crumb("demo", "/home/demo"),
            new Crumb("www", "/home/demo/www")).inOrder();
        assertThat(SftpBreadcrumbBar.crumbs("/", false)).containsExactly(new Crumb("/", "/"));
        // Doubled and trailing slashes add no empty folder.
        assertThat(SftpBreadcrumbBar.crumbs("/srv//app/", false)).containsExactly(
            new Crumb("/", "/"), new Crumb("srv", "/srv"), new Crumb("app", "/srv/app")).inOrder();
    }

    @Test
    void theUnexpandedHomeIsOneFolder() {
        // Before the first listing the server path is "~"; SFTP does not expand it.
        assertThat(SftpBreadcrumbBar.crumbs("~", false)).containsExactly(new Crumb("~", "~"));
        assertThat(SftpBreadcrumbBar.crumbs("", false)).isEmpty();
        assertThat(SftpBreadcrumbBar.crumbs(null, false)).isEmpty();
    }

    @Test
    void windowsPathsStartAtTheDriveOrTheShare() {
        assertThat(SftpBreadcrumbBar.crumbs("C:\\Users\\demo", true)).containsExactly(
            new Crumb("C:\\", "C:\\"),
            new Crumb("Users", "C:\\Users"),
            new Crumb("demo", "C:\\Users\\demo")).inOrder();
        assertThat(SftpBreadcrumbBar.crumbs("C:\\", true)).containsExactly(new Crumb("C:\\", "C:\\"));
        assertThat(SftpBreadcrumbBar.crumbs("\\\\nas\\backup\\2026", true)).containsExactly(
            new Crumb("\\\\nas\\backup\\", "\\\\nas\\backup\\"),
            new Crumb("2026", "\\\\nas\\backup\\2026")).inOrder();
    }
}
