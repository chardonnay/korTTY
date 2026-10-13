package de.kortty.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.function.BooleanSupplier;

/** Pretty JSON view plus an on-disk line map, keeping heap use independent of file size. */
public final class TerminalLogPresentation {
    private TerminalLogPresentation() { }
    public static void formatJson(Path source, Path target, Path map, BooleanSupplier cancelled) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8);
             var mapping = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(map)))) {
            int sourceLine = 0;
            String line;
            while ((line = TerminalLogDocument.boundedLine(reader, 16 * 1024 * 1024)) != null) {
                if (cancelled.getAsBoolean()) { throw new TerminalLogTrimmer.CancelledException(); }
                String stripped = line.strip();
                if (stripped.startsWith("{\"timestamp\"")) {
                    boolean comma = stripped.endsWith(",");
                    String object = comma ? stripped.substring(0, stripped.length() - 1) : stripped;
                    String[] pretty = gson.toJson(JsonParser.parseString(object)).split("\n");
                    for (int i = 0; i < pretty.length; i++) {
                        writer.write("    " + pretty[i] + (comma && i == pretty.length - 1 ? "," : "") + "\n");
                        mapping.writeInt(sourceLine);
                    }
                } else {
                    writer.write(line); writer.write('\n'); mapping.writeInt(sourceLine);
                }
                sourceLine++;
            }
        } catch (JsonParseException e) { throw new IOException("Cannot format invalid JSON log", e); }
    }

    /** Formats XML entry attributes and content, mapping every displayed row to its source entry. */
    public static void formatXml(Path source, Path target, Path map, BooleanSupplier cancelled) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8);
             var mapping = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(map)))) {
            int sourceLine = 0;
            String line;
            while ((line = TerminalLogDocument.boundedLine(reader, 16 * 1024 * 1024)) != null) {
                if (cancelled.getAsBoolean()) { throw new TerminalLogTrimmer.CancelledException(); }
                int first = sourceLine++;
                if (line.stripLeading().startsWith("<entry ")) {
                    StringBuilder entry = new StringBuilder(line);
                    while (!entry.toString().stripTrailing().endsWith("</entry>")) {
                        if (cancelled.getAsBoolean()) { throw new TerminalLogTrimmer.CancelledException(); }
                        String continuation = TerminalLogDocument.boundedLine(reader, 16 * 1024 * 1024);
                        if (continuation == null || entry.length() + continuation.length() > 16 * 1024 * 1024) {
                            throw new IOException("Incomplete or oversized XML entry");
                        }
                        entry.append('\n').append(continuation); sourceLine++;
                    }
                    var fields = TerminalLogDocument.parseEntry(entry.toString(), de.kortty.model.TerminalLogConfig.LogFormat.XML);
                    StringBuilder pretty = new StringBuilder("  <entry");
                    fields.forEach((key, value) -> {
                        if (!key.equals("line")) { pretty.append("\n    ").append(key).append("=\"")
                            .append(TerminalLogger.escapeXml(value)).append('"'); }
                    });
                    pretty.append(">\n    ").append(TerminalLogger.escapeXml(fields.getOrDefault("line", "")))
                        .append("\n  </entry>");
                    for (String part : pretty.toString().split("\n")) {
                        writer.write(part); writer.write('\n'); mapping.writeInt(first);
                    }
                } else {
                    if (line.contains("<!DOCTYPE") || line.contains("<!ENTITY")) { throw new IOException("DTD forbidden"); }
                    writer.write(line); writer.write('\n'); mapping.writeInt(first);
                }
            }
        }
    }

    /** Maps highlighted display rows back to source rows without loading the mapping into memory. */
    public static BitSet sourceSelection(Path map, BitSet displayed) throws IOException {
        if (map == null) { return (BitSet) displayed.clone(); }
        BitSet original = new BitSet();
        try (var channel = FileChannel.open(map, StandardOpenOption.READ)) {
            ByteBuffer integer = ByteBuffer.allocate(Integer.BYTES);
            for (int row = displayed.nextSetBit(0); row >= 0; row = displayed.nextSetBit(row + 1)) {
                integer.clear();
                long offset = (long) row * Integer.BYTES;
                while (integer.hasRemaining()) {
                    if (channel.read(integer, offset + integer.position()) < 0) { throw new IOException("Incomplete display line mapping"); }
                }
                integer.flip(); original.set(integer.getInt());
            }
        }
        return original;
    }
}
