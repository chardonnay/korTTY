package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * One of the user's own KI-Agent design combinations as stored in {@code global-settings.xml}; see
 * {@link de.kortty.agentdesign.AgentDesignCombination}. Built-in combinations are never stored.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class AgentDesignCombinationEntry {

    @XmlAttribute(required = true)
    private String id;

    @XmlAttribute
    private String name;

    @XmlAttribute
    private String history;

    @XmlAttribute
    private String status;

    @XmlAttribute
    private String approval;

    @XmlAttribute
    private String arrangement;

    public AgentDesignCombinationEntry() {
    }

    public AgentDesignCombinationEntry(de.kortty.agentdesign.AgentDesignCombination combination) {
        this.id = combination.id();
        this.name = combination.name();
        this.history = combination.historyId();
        this.status = combination.statusId();
        this.approval = combination.approvalId();
        this.arrangement = combination.arrangementId();
    }

    /** The stored combination, or {@code null} for an entry without an id (a hand-edited file). */
    public de.kortty.agentdesign.AgentDesignCombination toCombination() {
        if (id == null || id.isBlank()) {
            return null;
        }
        return new de.kortty.agentdesign.AgentDesignCombination(
            id.trim(), name, false, history, status, approval, arrangement);
    }

    public String getId() {
        return id;
    }
}
