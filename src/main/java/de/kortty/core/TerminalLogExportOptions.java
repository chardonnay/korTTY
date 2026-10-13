package de.kortty.core;

import de.kortty.model.TerminalLogConfig.LogFormat;
import java.util.Arrays;
import java.util.Objects;

/** Export format and archive are independent; GPG can additionally wrap every archive. */
public final class TerminalLogExportOptions implements AutoCloseable {
    public enum Archive {
        NONE(""), GZIP(".gz"), ZSTD(".zst"), BZIP2(".bz2"), ZIP(".zip");
        public final String suffix;
        Archive(String suffix) { this.suffix = suffix; }
        public TerminalLogNaming.Compression compression() {
            return this == ZIP ? TerminalLogNaming.Compression.NONE : TerminalLogNaming.Compression.valueOf(name());
        }
    }

    private final LogFormat format;
    private final Archive archive;
    private final char[] password;
    private final SessionJournalExportProtection.Protection gpg;

    public TerminalLogExportOptions(LogFormat format, Archive archive, char[] password,
                                    SessionJournalExportProtection.Protection gpg) {
        this.format = format;
        this.archive = Objects.requireNonNull(archive);
        if (password != null && password.length > 0 && archive != Archive.ZIP) {
            throw new IllegalArgumentException("Passwords require ZIP");
        }
        this.password = password == null ? null : password.clone();
        this.gpg = gpg != null ? gpg : SessionJournalExportProtection.Protection.NONE;
    }
    public LogFormat format() { return format; }
    public Archive archive() { return archive; }
    char[] password() { return password; }
    public SessionJournalExportProtection.Protection gpg() { return gpg; }
    public String suffix() { return archive.suffix + (gpg.usesGpg() ? ".gpg" : ""); }
    @Override public void close() { if (password != null) { Arrays.fill(password, '\0'); } }
}
