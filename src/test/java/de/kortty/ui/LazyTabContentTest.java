package de.kortty.ui;

import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.layout.Region;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pure: a TabPane would initialize the JavaFX toolkit, so the selection is flipped through Tab's
 * package-private setter — the same call TabPane's selection model makes.
 */
class LazyTabContentTest {

    private static void select(Tab tab, boolean selected) throws Exception {
        Method setter = Tab.class.getDeclaredMethod("setSelected", boolean.class);
        setter.setAccessible(true);
        setter.invoke(tab, selected);
    }

    @Test
    void contentIsBuiltOnceOnFirstSelection() throws Exception {
        Tab tab = new Tab("second");
        AtomicInteger builds = new AtomicInteger();
        Region content = new Region();
        LazyTabContent.defer(tab, () -> {
            builds.incrementAndGet();
            return content;
        });
        assertThat(tab.getContent()).isNull();
        assertThat(LazyTabContent.isPending(tab)).isTrue();
        assertThat(builds.get()).isEqualTo(0);

        select(tab, true);
        assertThat(tab.getContent()).isSameInstanceAs(content);
        assertThat(builds.get()).isEqualTo(1);
        assertThat(LazyTabContent.isPending(tab)).isFalse();

        select(tab, false);
        select(tab, true);
        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    void alreadySelectedTabIsBuiltImmediately() throws Exception {
        Tab tab = new Tab("only");
        select(tab, true);
        Region content = new Region();
        LazyTabContent.defer(tab, () -> content);
        assertThat(tab.getContent()).isSameInstanceAs(content);
        assertThat(LazyTabContent.isPending(tab)).isFalse();
    }

    @Test
    void ensureContentBuildsWithoutSelectionAndIsIdempotent() throws Exception {
        Tab tab = new Tab("lazy");
        AtomicInteger builds = new AtomicInteger();
        LazyTabContent.defer(tab, () -> {
            builds.incrementAndGet();
            return new Region();
        });
        Node built = LazyTabContent.ensureContent(tab);
        assertThat(built).isNotNull();
        assertThat(LazyTabContent.ensureContent(tab)).isSameInstanceAs(built);
        select(tab, true);
        assertThat(builds.get()).isEqualTo(1);
        assertThat(LazyTabContent.ensureContent(null)).isNull();
    }
}
