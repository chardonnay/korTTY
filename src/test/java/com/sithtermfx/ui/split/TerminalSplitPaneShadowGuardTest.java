package com.sithtermfx.ui.split;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.ui.KorttyTermWidget;
import javafx.geometry.Orientation;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * korTTY's {@code com.sithtermfx.ui.split.TerminalSplitPane} is a copy of the SithTermFX class with
 * the same name, and it wins only because korTTY's own classes come first on the classpath: nothing
 * excludes the vendor class from {@code sithtermfx-ui}. If that order ever flipped, korTTY would
 * still compile against its own source, but the vendor class would load at runtime, and every
 * korTTY-only call ({@code getWidgetOverlayHost}, {@code paneOverlay}, {@code closeSplitPane}, the
 * prepared-connector split, the focused-pane tracking, the pane focus keys, the input mirror) would
 * fail with {@code NoSuchMethodError} or silently lose korTTY's fixes. This test fails first.
 *
 * <p>Toolkit-free: reflection loads the classes without initializing any JavaFX control.
 */
public class TerminalSplitPaneShadowGuardTest {

    @Test
    public void loadedClassDeclaresTheKorttyOnlyMethods() throws NoSuchMethodException {
        Method overlayHost = TerminalSplitPane.class.getDeclaredMethod("getWidgetOverlayHost", SithTermFxWidget.class);
        assertThat(overlayHost.getReturnType()).isEqualTo(StackPane.class);

        Method closeSplitPane = TerminalSplitPane.class.getDeclaredMethod("closeSplitPane", SithTermFxWidget.class);
        assertThat(closeSplitPane.getReturnType()).isEqualTo(boolean.class);

        Method preparedSplit = TerminalSplitPane.class.getDeclaredMethod("splitWidget",
            SithTermFxWidget.class, SplitRequest.SplitMode.class, Orientation.class, TtyConnector.class);
        assertThat(preparedSplit.getReturnType()).isEqualTo(SithTermFxWidget.class);

        TerminalSplitPane.class.getDeclaredMethod("setFocusedWidgetInternal", SithTermFxWidget.class);
        TerminalSplitPane.class.getDeclaredMethod("setMirrorTargetGuard", Predicate.class);
        TerminalSplitPane.class.getDeclaredMethod("setMirrorInputRule", Function.class);
        Method heldTargets = TerminalSplitPane.class.getDeclaredMethod("countHeldMirrorTargets");
        assertThat(heldTargets.getReturnType()).isEqualTo(int.class);
        TerminalSplitPane.class.getDeclaredMethod("setInputMirror", InputMirror.class);
        Method encodeKeyFor = TerminalSplitPane.class.getDeclaredMethod("encodeKeyFor",
            SithTermFxWidget.class, KeyEvent.class);
        assertThat(encodeKeyFor.getReturnType()).isEqualTo(byte[].class);

        Method paneOverlay = TerminalSplitPane.class.getDeclaredMethod("paneOverlay",
            SithTermFxWidget.class, TerminalSplitPane.PaneOverlayLayer.class);
        assertThat(paneOverlay.getReturnType()).isEqualTo(Pane.class);

        Method focusNeighbor = TerminalSplitPane.class.getDeclaredMethod("focusNeighbor",
            de.kortty.ui.PaneNavigator.PaneDirection.class);
        assertThat(focusNeighbor.getReturnType()).isEqualTo(boolean.class);
        Method focusNext = TerminalSplitPane.class.getDeclaredMethod("focusNext", boolean.class);
        assertThat(focusNext.getReturnType()).isEqualTo(boolean.class);
    }

    @Test
    public void loadedClassComesFromKorttysOwnClassesNotTheSithTermFxJar() {
        String splitPaneSource = codeSourceOf(TerminalSplitPane.class);
        assertWithMessage("TerminalSplitPane must load from the same place as korTTY's own classes")
            .that(splitPaneSource).isEqualTo(codeSourceOf(KorttyTermWidget.class));
        assertThat(splitPaneSource).doesNotContain("sithtermfx-ui");
    }

    private static String codeSourceOf(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        assertWithMessage("code source of " + type.getName()).that(source).isNotNull();
        assertWithMessage("code source location of " + type.getName()).that(source.getLocation()).isNotNull();
        return source.getLocation().toString();
    }
}
