package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import javafx.geometry.Orientation;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;

/**
 * The leaves of a saved split layout gained optional fields: the connection a pane split to another
 * server runs, and the session-only working directory and scrollback reference. Projects saved
 * before them must still open, and the new fields must survive a save and a load.
 */
class SplitPaneStateXmlCompatTest {

    private static JAXBContext context() throws Exception {
        return JAXBContext.newInstance(
                SessionState.class,
                ConnectionSettings.class,
                SplitPaneState.class,
                TerminalTimestampEntry.class);
    }

    @Test
    void aLayoutSavedBeforeTheLeafFieldsStillLoads() throws Exception {
        String saved = """
                <session>
                    <tabType>TERMINAL</tabType>
                    <sessionId>session-1</sessionId>
                    <connectionId>connection-1</connectionId>
                    <splitPaneState>
                        <orientation>HORIZONTAL</orientation>
                        <dividerPosition>0.4</dividerPosition>
                        <leftChild><widgetIndex>0</widgetIndex></leftChild>
                        <rightChild>
                            <orientation>VERTICAL</orientation>
                            <dividerPosition>0.7</dividerPosition>
                            <leftChild><widgetIndex>1</widgetIndex></leftChild>
                            <rightChild><widgetIndex>2</widgetIndex></rightChild>
                        </rightChild>
                    </splitPaneState>
                </session>
                """;

        SessionState session = (SessionState) context().createUnmarshaller().unmarshal(new StringReader(saved));
        SplitPaneState layout = session.getSplitPaneState();

        assertThat(layout.isSplit()).isTrue();
        assertThat(layout.getOrientationEnum()).isEqualTo(Orientation.HORIZONTAL);
        assertThat(layout.getDividerPosition()).isEqualTo(0.4);
        assertThat(layout.getLeftChild().isLeaf()).isTrue();
        assertThat(layout.getLeftChild().getConnectionId()).isNull();
        assertThat(layout.getLeftChild().getCurrentDirectory()).isNull();
        assertThat(layout.getLeftChild().getScrollbackRef()).isNull();
        assertThat(layout.getRightChild().getOrientationEnum()).isEqualTo(Orientation.VERTICAL);
        assertThat(layout.getRightChild().getRightChild().getWidgetIndex()).isEqualTo(2);
    }

    @Test
    void theLeafFieldsRoundTrip() throws Exception {
        SplitPaneState onServerB = SplitPaneState.createLeaf(1, "connection-b");
        onServerB.setCurrentDirectory("/home/me/project");
        onServerB.setScrollbackRef("3f2a9c4e-0d1b-4c8e-9a77-2b5f6e1d0c3a");
        SessionState session = new SessionState("session-1", "connection-1");
        session.setSplitPaneState(SplitPaneState.createSplit(Orientation.VERTICAL, 0.25,
                SplitPaneState.createLeaf(0), onServerB));

        StringWriter writer = new StringWriter();
        context().createMarshaller().marshal(session, writer);
        SessionState reloaded = (SessionState) context().createUnmarshaller()
                .unmarshal(new StringReader(writer.toString()));
        SplitPaneState leaf = reloaded.getSplitPaneState().getRightChild();

        assertThat(writer.toString()).contains("<connectionId>connection-b</connectionId>");
        assertThat(leaf.getWidgetIndex()).isEqualTo(1);
        assertThat(leaf.getConnectionId()).isEqualTo("connection-b");
        assertThat(leaf.getCurrentDirectory()).isEqualTo("/home/me/project");
        assertThat(leaf.getScrollbackRef()).isEqualTo("3f2a9c4e-0d1b-4c8e-9a77-2b5f6e1d0c3a");
        assertThat(reloaded.getSplitPaneState().getDividerPosition()).isEqualTo(0.25);
    }

    @Test
    void aPaneOnTheTabsConnectionWritesNoLeafFields() throws Exception {
        SessionState session = new SessionState("session-1", "connection-1");
        session.setSplitPaneState(SplitPaneState.createSplit(Orientation.HORIZONTAL, 0.5,
                SplitPaneState.createLeaf(0), SplitPaneState.createLeaf(1)));

        StringWriter writer = new StringWriter();
        context().createMarshaller().marshal(session, writer);
        String xml = writer.toString();
        String layout = xml.substring(xml.indexOf("<splitPaneState>"));

        assertThat(layout).doesNotContain("connectionId");
        assertThat(layout).doesNotContain("currentDirectory");
        assertThat(layout).doesNotContain("scrollbackRef");
    }

    @Test
    void anOrientationThatNamesNoneReadsAsNone() {
        SplitPaneState state = SplitPaneState.createSplit(Orientation.HORIZONTAL, 0.5,
                SplitPaneState.createLeaf(0), SplitPaneState.createLeaf(1));
        state.setOrientation("DIAGONAL");

        assertThat(state.getOrientationEnum()).isNull();
    }

    @Test
    void theWorkingDirectoryNeverReachesALogLine() {
        SplitPaneState leaf = SplitPaneState.createLeaf(3, "connection-b");
        leaf.setCurrentDirectory("/home/me/secret-project");

        assertThat(leaf.toString()).contains("connection-b");
        assertThat(leaf.toString()).doesNotContain("secret-project");
    }
}
