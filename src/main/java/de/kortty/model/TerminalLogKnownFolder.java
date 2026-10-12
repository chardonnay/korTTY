package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * A folder terminal logs were written into, as the global settings remember it for the Logfile
 * Manager: the folder, the retention its last logger ran with ({@code 0} = none known), and when
 * it was last used. Quick Connect sessions and connections deleted since are only findable
 * through this list.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class TerminalLogKnownFolder {

    @XmlAttribute(required = true)
    private String path;

    @XmlAttribute
    private int retentionDays;

    @XmlAttribute
    private String lastUsed;

    /** True when the user added it by hand rather than a logger having written there. */
    @XmlAttribute
    private boolean manual;

    public TerminalLogKnownFolder() {
    }

    public TerminalLogKnownFolder(String path, int retentionDays, String lastUsed, boolean manual) {
        this.path = path;
        this.retentionDays = retentionDays;
        this.lastUsed = lastUsed;
        this.manual = manual;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(0, retentionDays);
    }

    public String getLastUsed() {
        return lastUsed;
    }

    public void setLastUsed(String lastUsed) {
        this.lastUsed = lastUsed;
    }

    public boolean isManual() {
        return manual;
    }

    public void setManual(boolean manual) {
        this.manual = manual;
    }
}
