package de.kortty.ui.sftp;

import java.util.Optional;

/**
 * The nine Unix permission bits behind the checkboxes of "Set Owner/Permissions": owner, group and
 * others, each read, write and execute, in the order {@code ls -l} prints them. Only the three-digit
 * octal form is accepted, the same that the dialog lets through to {@code chmod}.
 */
public final class PermissionBits {

    /** Number of bits: three classes (owner, group, others) times read, write, execute. */
    public static final int COUNT = 9;

    private static final String SYMBOLS = "rwx";

    private PermissionBits() {
    }

    /**
     * The bits of a mode such as {@code 755}: index 0 is owner read, 8 others execute. Empty unless
     * {@code octal} is exactly three octal digits (surrounding blanks allowed).
     */
    public static Optional<boolean[]> parseOctal(String octal) {
        String value = octal == null ? "" : octal.trim();
        if (!value.matches("[0-7]{3}")) {
            return Optional.empty();
        }
        boolean[] bits = new boolean[COUNT];
        for (int digit = 0; digit < 3; digit++) {
            int classBits = value.charAt(digit) - '0';
            bits[digit * 3] = (classBits & 4) != 0;
            bits[digit * 3 + 1] = (classBits & 2) != 0;
            bits[digit * 3 + 2] = (classBits & 1) != 0;
        }
        return Optional.of(bits);
    }

    /** The three octal digits of {@code bits}, such as {@code 644}. */
    public static String toOctal(boolean[] bits) {
        requireNine(bits);
        StringBuilder octal = new StringBuilder(3);
        for (int digit = 0; digit < 3; digit++) {
            int value = (bits[digit * 3] ? 4 : 0) + (bits[digit * 3 + 1] ? 2 : 0) + (bits[digit * 3 + 2] ? 1 : 0);
            octal.append(value);
        }
        return octal.toString();
    }

    /** The {@code ls -l} form of {@code bits}, such as {@code rwxr-xr-x}. */
    public static String toSymbolic(boolean[] bits) {
        requireNine(bits);
        StringBuilder symbolic = new StringBuilder(COUNT);
        for (int index = 0; index < COUNT; index++) {
            symbolic.append(bits[index] ? SYMBOLS.charAt(index % 3) : '-');
        }
        return symbolic.toString();
    }

    private static void requireNine(boolean[] bits) {
        if (bits == null || bits.length != COUNT) {
            throw new IllegalArgumentException("expected " + COUNT + " permission bits");
        }
    }
}
