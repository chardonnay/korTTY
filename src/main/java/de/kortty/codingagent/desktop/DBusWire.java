package de.kortty.codingagent.desktop;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The little-endian D-Bus wire format, as far as korTTY speaks it: building method calls and signals
 * ({@link Marshaller}, {@link #message}) and reading the messages a bus sends back
 * ({@link #parse}, {@link Unmarshaller}). Pure — no socket here — so every byte is unit-tested.
 *
 * <p>Parsing is defensive: every length is checked against the bytes actually present, and a
 * malformed message is an {@link IllegalArgumentException}, never an index error or an endless loop.
 */
final class DBusWire {

    static final byte LITTLE_ENDIAN = 'l';
    static final byte PROTOCOL_VERSION = 1;
    static final byte TYPE_METHOD_CALL = 1;
    static final byte TYPE_METHOD_RETURN = 2;
    static final byte TYPE_ERROR = 3;
    static final byte TYPE_SIGNAL = 4;
    static final byte FLAG_NO_REPLY_EXPECTED = 1;

    static final byte FIELD_PATH = 1;
    static final byte FIELD_INTERFACE = 2;
    static final byte FIELD_MEMBER = 3;
    static final byte FIELD_ERROR_NAME = 4;
    static final byte FIELD_REPLY_SERIAL = 5;
    static final byte FIELD_DESTINATION = 6;
    static final byte FIELD_SENDER = 7;
    static final byte FIELD_SIGNATURE = 8;

    static final String BUS_PATH = "/org/freedesktop/DBus";
    static final String BUS_NAME = "org.freedesktop.DBus";

    /** The fixed part of every message header: endianness, type, flags, version, three uint32. */
    static final int FIXED_HEADER_BYTES = 16;
    /** The largest message korTTY accepts from the bus; the protocol allows 128 MiB. */
    static final int MAX_MESSAGE_BYTES = 1 << 20;

    private DBusWire() {
    }

    /**
     * One {@code a(yv)} header field: the field code and its variant ({@code s}, {@code o}, {@code g}
     * or {@code u}, whose value is the decimal number).
     */
    record HeaderField(byte code, String signature, String value) {
    }

    /**
     * One message read from the bus.
     *
     * @param type the message type ({@link #TYPE_SIGNAL}, {@link #TYPE_METHOD_RETURN}, …)
     * @param serial the sender's serial of this message
     * @param replySerial the serial this message answers, {@code 0} when it answers none
     * @param sender the unique bus name of the sender, empty when the header carries none
     * @param body the raw body, to be read with an {@link Unmarshaller} after {@code signature}
     */
    record Message(byte type, int serial, int replySerial, String path, String iface, String member,
                   String errorName, String sender, String signature, byte[] body) {
    }

    /** The marshalled {@code org.freedesktop.DBus.Hello} method call. */
    static byte[] helloMessage(int serial) {
        return methodCall(serial, BUS_NAME, BUS_PATH, BUS_NAME, "Hello", "", new byte[0]);
    }

    /** A method call that expects a reply; {@code signature} describes {@code body} (empty for none). */
    static byte[] methodCall(int serial, String destination, String path, String iface, String member,
                             String signature, byte[] body) {
        List<HeaderField> fields = new ArrayList<>(5);
        fields.add(new HeaderField(FIELD_PATH, "o", path));
        fields.add(new HeaderField(FIELD_DESTINATION, "s", destination));
        fields.add(new HeaderField(FIELD_INTERFACE, "s", iface));
        fields.add(new HeaderField(FIELD_MEMBER, "s", member));
        if (signature != null && !signature.isEmpty()) {
            fields.add(new HeaderField(FIELD_SIGNATURE, "g", signature));
        }
        return message(TYPE_METHOD_CALL, (byte) 0, serial, fields, body);
    }

    /** Assembles one message from its header fields and its already marshalled body. */
    static byte[] message(byte type, byte flags, int serial, List<HeaderField> fields, byte[] body) {
        Marshaller headerFields = new Marshaller();
        for (HeaderField field : fields) {
            headerFields.align(8);
            headerFields.putByte(field.code());
            headerFields.putSignature(field.signature());
            if ("g".equals(field.signature())) {
                headerFields.putSignature(field.value());
            } else if ("u".equals(field.signature())) {
                headerFields.putUInt32(Integer.parseUnsignedInt(field.value()));
            } else {
                headerFields.putString(field.value());
            }
        }
        Marshaller out = new Marshaller();
        out.putByte(LITTLE_ENDIAN);
        out.putByte(type);
        out.putByte(flags);
        out.putByte(PROTOCOL_VERSION);
        out.putUInt32(body.length);
        out.putUInt32(serial);
        out.putUInt32(headerFields.size());
        out.putBytes(headerFields.toArray());
        out.align(8);
        out.putBytes(body);
        return out.toArray();
    }

    /**
     * The total length of the message whose first {@link #FIXED_HEADER_BYTES} bytes are in
     * {@code header}: the fixed header, the header fields padded to eight bytes and the body.
     *
     * @throws IllegalArgumentException for a big-endian message or one beyond {@link #MAX_MESSAGE_BYTES}
     */
    static int messageLength(byte[] header) {
        if (header.length < FIXED_HEADER_BYTES) {
            throw new IllegalArgumentException("short D-Bus header");
        }
        if (header[0] != LITTLE_ENDIAN) {
            throw new IllegalArgumentException("unsupported D-Bus byte order " + header[0]);
        }
        long bodyLength = Integer.toUnsignedLong(readUInt32(header, 4));
        long fieldsLength = Integer.toUnsignedLong(readUInt32(header, 12));
        long total = FIXED_HEADER_BYTES + fieldsLength + padding((int) (fieldsLength % 8), 8) + bodyLength;
        if (total > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("D-Bus message of " + total + " bytes exceeds the limit");
        }
        return (int) total;
    }

    /** Parses one complete message as {@link #messageLength} measured it. */
    static Message parse(byte[] bytes) {
        int total = messageLength(bytes);
        if (bytes.length < total) {
            throw new IllegalArgumentException("truncated D-Bus message");
        }
        byte type = bytes[1];
        int bodyLength = readUInt32(bytes, 4);
        int serial = readUInt32(bytes, 8);
        int fieldsLength = readUInt32(bytes, 12);
        Unmarshaller fields = new Unmarshaller(bytes, FIXED_HEADER_BYTES, FIXED_HEADER_BYTES + fieldsLength);
        int replySerial = 0;
        String path = "";
        String iface = "";
        String member = "";
        String errorName = "";
        String sender = "";
        String signature = "";
        while (fields.remaining() > 0) {
            fields.align(8);
            if (fields.remaining() == 0) {
                break;
            }
            byte code = fields.readByte();
            String variant = fields.readSignature();
            switch (variant) {
                case "s", "o" -> {
                    String value = fields.readString();
                    switch (code) {
                        case FIELD_PATH -> path = value;
                        case FIELD_INTERFACE -> iface = value;
                        case FIELD_MEMBER -> member = value;
                        case FIELD_ERROR_NAME -> errorName = value;
                        case FIELD_SENDER -> sender = value;
                        default -> {
                            // Destination and unknown string fields are not needed.
                        }
                    }
                }
                case "g" -> {
                    String value = fields.readSignature();
                    if (code == FIELD_SIGNATURE) {
                        signature = value;
                    }
                }
                case "u" -> {
                    int value = fields.readUInt32();
                    if (code == FIELD_REPLY_SERIAL) {
                        replySerial = value;
                    }
                }
                default -> throw new IllegalArgumentException("unexpected header field type " + variant);
            }
        }
        int bodyStart = FIXED_HEADER_BYTES + fieldsLength + padding(fieldsLength % 8, 8);
        byte[] body = new byte[bodyLength];
        System.arraycopy(bytes, bodyStart, body, 0, bodyLength);
        return new Message(type, serial, replySerial, path, iface, member, errorName, sender, signature, body);
    }

    static int readUInt32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
            | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16)
            | ((bytes[offset + 3] & 0xff) << 24);
    }

    static int padding(int size, int alignment) {
        int remainder = size % alignment;
        return remainder == 0 ? 0 : alignment - remainder;
    }

    /**
     * Little-endian D-Bus marshaller. Every buffer this class fills starts at an eight-byte aligned
     * position of the message, so padding computed from the buffer offset matches the wire position.
     */
    static final class Marshaller {

        private byte[] data = new byte[128];
        private int size;

        void align(int alignment) {
            while (size % alignment != 0) {
                putByte((byte) 0);
            }
        }

        void putByte(byte value) {
            ensure(1);
            data[size++] = value;
        }

        void putBytes(byte[] values) {
            ensure(values.length);
            System.arraycopy(values, 0, data, size, values.length);
            size += values.length;
        }

        void putUInt32(int value) {
            align(4);
            ensure(4);
            data[size++] = (byte) value;
            data[size++] = (byte) (value >>> 8);
            data[size++] = (byte) (value >>> 16);
            data[size++] = (byte) (value >>> 24);
        }

        void putInt64(long value) {
            align(8);
            ensure(8);
            for (int i = 0; i < 8; i++) {
                data[size++] = (byte) (value >>> (8 * i));
            }
        }

        void putString(String text) {
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            putUInt32(utf8.length);
            putBytes(utf8);
            putByte((byte) 0);
        }

        void putSignature(String signature) {
            byte[] ascii = signature.getBytes(StandardCharsets.US_ASCII);
            putByte((byte) ascii.length);
            putBytes(ascii);
            putByte((byte) 0);
        }

        int size() {
            return size;
        }

        byte[] toArray() {
            byte[] copy = new byte[size];
            System.arraycopy(data, 0, copy, 0, size);
            return copy;
        }

        private void ensure(int additional) {
            if (size + additional <= data.length) {
                return;
            }
            int capacity = data.length;
            while (capacity < size + additional) {
                capacity *= 2;
            }
            byte[] grown = new byte[capacity];
            System.arraycopy(data, 0, grown, 0, size);
            data = grown;
        }
    }

    /**
     * Reads little-endian values from {@code bytes[start, end)}; alignment is relative to
     * {@code start}, which is eight-byte aligned on the wire for both the header fields and the body.
     */
    static final class Unmarshaller {

        private final byte[] bytes;
        private final int start;
        private final int end;
        private int position;

        Unmarshaller(byte[] bytes) {
            this(bytes, 0, bytes.length);
        }

        Unmarshaller(byte[] bytes, int start, int end) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            if (start < 0 || end > bytes.length || start > end) {
                throw new IllegalArgumentException("D-Bus region out of bounds");
            }
            this.start = start;
            this.end = end;
            this.position = start;
        }

        int remaining() {
            return end - position;
        }

        void align(int alignment) {
            int skip = padding(position - start, alignment);
            require(Math.min(skip, remaining()));
            position += Math.min(skip, remaining());
        }

        byte readByte() {
            require(1);
            return bytes[position++];
        }

        int readUInt32() {
            align(4);
            require(4);
            int value = DBusWire.readUInt32(bytes, position);
            position += 4;
            return value;
        }

        String readString() {
            int length = readUInt32();
            if (length < 0) {
                throw new IllegalArgumentException("negative D-Bus string length");
            }
            require(length + 1);
            String value = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length + 1;
            return value;
        }

        String readSignature() {
            int length = readByte() & 0xff;
            require(length + 1);
            String value = new String(bytes, position, length, StandardCharsets.US_ASCII);
            position += length + 1;
            return value;
        }

        /** Reads an {@code as}: the byte length of the elements, then each string. */
        List<String> readStringArray() {
            int length = readUInt32();
            if (length < 0) {
                throw new IllegalArgumentException("negative D-Bus array length");
            }
            require(length);
            int arrayEnd = position + length;
            List<String> values = new ArrayList<>();
            while (position < arrayEnd) {
                values.add(readString());
            }
            if (position != arrayEnd) {
                throw new IllegalArgumentException("D-Bus array length mismatch");
            }
            return values;
        }

        private void require(int count) {
            if (count < 0 || position + count > end) {
                throw new IllegalArgumentException("truncated D-Bus value");
            }
        }
    }
}
