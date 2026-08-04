package de.kortty.ui;

import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Font-family lists for the pickers that offer a terminal, editor or journal font. Every list
 * contains all installed families — the well-known monospace ones are only sorted to the front,
 * never used as a filter, because "is this family monospaced?" cannot be answered reliably from
 * JavaFX and a filter would hide fonts the user deliberately installed.
 */
public final class MonospaceFontFamilies {

    /** Sorted to the front when installed; the order is the one the pickers have always shown. */
    private static final List<String> PREFERRED = List.of(
        "Monospaced",
        "Courier New",
        "Consolas",
        "Monaco",
        "Menlo",
        "Source Code Pro",
        "JetBrains Mono",
        "Fira Code",
        "SF Mono",
        "DejaVu Sans Mono",
        "Liberation Mono",
        "Ubuntu Mono");

    private MonospaceFontFamilies() {
    }

    /** All installed families, common monospace ones first. */
    public static List<String> monospaceFirst() {
        return monospaceFirst(Font.getFamilies());
    }

    /** Testable variant taking the installed families explicitly. */
    static List<String> monospaceFirst(List<String> installed) {
        List<String> system = installed != null ? installed : List.of();
        Set<String> preferred = new LinkedHashSet<>(PREFERRED);
        List<String> result = new ArrayList<>(preferred.size() + system.size());
        for (String family : preferred) {
            if (system.contains(family)) {
                result.add(family);
            }
        }
        for (String family : system) {
            if (!preferred.contains(family)) {
                result.add(family);
            }
        }
        return result;
    }
}
