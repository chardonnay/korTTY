package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * The wire format of korTTY's {@code org.freedesktop.Notifications} client: the {@code Notify} call it
 * sends, the replies and signals it reads back, and which signals may run a notification's click
 * action.
 */
class NotificationsDBusTest {

    private static final String SERVER = ":1.17";

    /** A signal of the notification server, as the bus delivers it (with the sender filled in). */
    private static DBusWire.Message signal(String sender, String member, String signature, byte[] body) {
        byte[] bytes = DBusWire.message(DBusWire.TYPE_SIGNAL, DBusWire.FLAG_NO_REPLY_EXPECTED, 9, List.of(
            new DBusWire.HeaderField(DBusWire.FIELD_PATH, "o", NotificationsDBusConnection.OBJECT_PATH),
            new DBusWire.HeaderField(DBusWire.FIELD_INTERFACE, "s", NotificationsDBusConnection.SERVICE),
            new DBusWire.HeaderField(DBusWire.FIELD_MEMBER, "s", member),
            new DBusWire.HeaderField(DBusWire.FIELD_SENDER, "s", sender),
            new DBusWire.HeaderField(DBusWire.FIELD_SIGNATURE, "g", signature)), body);
        return DBusWire.parse(bytes);
    }

    private static DBusWire.Message actionInvoked(String sender, int id, String action) {
        DBusWire.Marshaller body = new DBusWire.Marshaller();
        body.putUInt32(id);
        body.putString(action);
        return signal(sender, "ActionInvoked", "us", body.toArray());
    }

    private static DBusWire.Message closed(int id) {
        DBusWire.Marshaller body = new DBusWire.Marshaller();
        body.putUInt32(id);
        body.putUInt32(2);
        return signal(SERVER, "NotificationClosed", "uu", body.toArray());
    }

    @Test
    void theNotifyCallCarriesTheDefaultActionAndTheDesktopEntry() {
        byte[] wire = NotificationsDBusConnection.notifyCall(5, "korTTY", "/opt/kortty/lib/korTTY.png",
            "claude needs a decision", "a <b> & c", true, "kortty-korTTY", 8_000);

        DBusWire.Message call = DBusWire.parse(wire);
        assertThat(call.type()).isEqualTo(DBusWire.TYPE_METHOD_CALL);
        assertThat(call.serial()).isEqualTo(5);
        assertThat(call.path()).isEqualTo("/org/freedesktop/Notifications");
        assertThat(call.iface()).isEqualTo("org.freedesktop.Notifications");
        assertThat(call.member()).isEqualTo("Notify");
        assertThat(call.signature()).isEqualTo("susssasa{sv}i");

        DBusWire.Unmarshaller body = new DBusWire.Unmarshaller(call.body());
        assertThat(body.readString()).isEqualTo("korTTY");
        assertThat(body.readUInt32()).isEqualTo(0);
        assertThat(body.readString()).isEqualTo("/opt/kortty/lib/korTTY.png");
        assertThat(body.readString()).isEqualTo("claude needs a decision");
        assertThat(body.readString()).isEqualTo("a &lt;b&gt; &amp; c");
        assertThat(body.readStringArray()).containsExactly("default", "Open").inOrder();

        String hints = new String(call.body(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(hints).contains("urgency");
        assertThat(hints).contains("desktop-entry");
        assertThat(hints).contains("kortty-korTTY");
        // The expire timeout is the last uint32 of the body.
        assertThat(DBusWire.readUInt32(call.body(), call.body().length - 4)).isEqualTo(8_000);
    }

    @Test
    void aNotificationWithoutClickActionOrDesktopEntryHasNeither() {
        DBusWire.Message call = DBusWire.parse(NotificationsDBusConnection.notifyCall(6, "korTTY", "icon", "t", "b",
            false, null, 8_000));
        DBusWire.Unmarshaller body = new DBusWire.Unmarshaller(call.body());
        body.readString();
        body.readUInt32();
        body.readString();
        body.readString();
        body.readString();
        assertThat(body.readStringArray()).isEmpty();
        assertThat(new String(call.body(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("desktop-entry");
    }

    @Test
    void theMatchRuleListensToTheNotificationServerOnly() {
        DBusWire.Message call = DBusWire.parse(NotificationsDBusConnection.addMatchCall(3));
        assertThat(call.member()).isEqualTo("AddMatch");
        assertThat(call.iface()).isEqualTo("org.freedesktop.DBus");
        assertThat(new DBusWire.Unmarshaller(call.body()).readString())
            .isEqualTo("type='signal',sender='org.freedesktop.Notifications',"
                + "path='/org/freedesktop/Notifications',interface='org.freedesktop.Notifications'");
    }

    @Test
    void repliesAreParsedWithTheirReplySerialSenderAndBody() {
        DBusWire.Marshaller capabilities = new DBusWire.Marshaller();
        DBusWire.Marshaller list = new DBusWire.Marshaller();
        list.putString("body");
        list.putString("actions");
        capabilities.putUInt32(list.size());
        capabilities.putBytes(list.toArray());
        byte[] wire = DBusWire.message(DBusWire.TYPE_METHOD_RETURN, (byte) 0, 44, List.of(
            new DBusWire.HeaderField(DBusWire.FIELD_REPLY_SERIAL, "u", "12"),
            new DBusWire.HeaderField(DBusWire.FIELD_DESTINATION, "s", ":1.3"),
            new DBusWire.HeaderField(DBusWire.FIELD_SENDER, "s", SERVER),
            new DBusWire.HeaderField(DBusWire.FIELD_SIGNATURE, "g", "as")), capabilities.toArray());

        DBusWire.Message reply = DBusWire.parse(wire);

        assertThat(reply.type()).isEqualTo(DBusWire.TYPE_METHOD_RETURN);
        assertThat(reply.serial()).isEqualTo(44);
        assertThat(reply.replySerial()).isEqualTo(12);
        assertThat(reply.sender()).isEqualTo(SERVER);
        assertThat(NotificationsDBusConnection.capabilitiesOf(reply)).containsExactly("body", "actions").inOrder();
        assertThat(DBusWire.messageLength(wire)).isEqualTo(wire.length);
    }

    @Test
    void malformedMessagesAreRejectedNotMisread() {
        byte[] wire = NotificationsDBusConnection.addMatchCall(3);
        byte[] truncated = java.util.Arrays.copyOf(wire, wire.length - 5);
        assertThrows(IllegalArgumentException.class, () -> DBusWire.parse(truncated));

        byte[] bigEndian = wire.clone();
        bigEndian[0] = 'B';
        assertThrows(IllegalArgumentException.class, () -> DBusWire.messageLength(bigEndian));

        byte[] huge = wire.clone();
        huge[7] = 0x7f;
        assertThrows(IllegalArgumentException.class, () -> DBusWire.messageLength(huge));

        assertThrows(IllegalArgumentException.class,
            () -> new DBusWire.Unmarshaller(new byte[] {(byte) 0xff, 0, 0, 0}).readString());
        assertThat(NotificationsDBusConnection.capabilitiesOf(null)).isEmpty();
    }

    @Test
    void aClickOnTheNotificationRunsItsActionOnce() {
        NotificationsDBusConnection.Activations activations = new NotificationsDBusConnection.Activations(8);
        activations.serverName(SERVER);
        AtomicInteger clicks = new AtomicInteger();
        activations.register(7, clicks::incrementAndGet);

        Runnable first = activations.onSignal(actionInvoked(SERVER, 7, "default"));
        Runnable second = activations.onSignal(actionInvoked(SERVER, 7, "default"));

        assertThat(first).isNotNull();
        first.run();
        assertThat(clicks.get()).isEqualTo(1);
        assertThat(second).isNull();
        assertThat(activations.size()).isEqualTo(0);
    }

    @Test
    void onlyTheNotificationServerCanClickAndOnlyTheDefaultActionCounts() {
        NotificationsDBusConnection.Activations activations = new NotificationsDBusConnection.Activations(8);
        activations.register(7, () -> { });

        assertWithMessage("before the server answered once, nothing is trusted")
            .that(activations.onSignal(actionInvoked(SERVER, 7, "default"))).isNull();
        activations.serverName(SERVER);
        assertThat(activations.onSignal(actionInvoked(":1.99", 7, "default"))).isNull();
        assertThat(activations.onSignal(actionInvoked(SERVER, 7, "snooze"))).isNull();
        assertThat(activations.onSignal(actionInvoked(SERVER, 8, "default"))).isNull();
        assertThat(activations.size()).isEqualTo(1);
    }

    @Test
    void aClosedNotificationForgetsItsActionAndOldOnesAreDropped() {
        NotificationsDBusConnection.Activations activations = new NotificationsDBusConnection.Activations(2);
        activations.serverName(SERVER);
        activations.register(1, () -> { });
        activations.register(2, () -> { });
        activations.register(3, () -> { });
        assertThat(activations.size()).isEqualTo(2);
        assertThat(activations.onSignal(actionInvoked(SERVER, 1, "default"))).isNull();

        assertThat(activations.onSignal(closed(2))).isNull();
        assertThat(activations.size()).isEqualTo(1);
        assertThat(activations.onSignal(actionInvoked(SERVER, 3, "default"))).isNotNull();
    }
}
