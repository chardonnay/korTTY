package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named, ordered list of {@link HighlightRule}s that a terminal pane shows its output through.
 *
 * <p>Only user-defined sets are stored ({@code GlobalSettings.highlightRuleSets}). The built-in sets
 * come from {@code de.kortty.core.highlight.HighlightBuiltinSets}, use ids under the reserved
 * {@code builtin.} prefix and are never written to disk, so a later release can improve them without
 * a migration. Panes, connections and the global default refer to a set by its {@link #getId() id},
 * which therefore stays stable across renames.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "HighlightRuleSet")
public class HighlightRuleSet {

    @XmlElement
    private String id;

    @XmlElement
    private String name;

    /** Rules in priority order: the first rule that claims a character wins. */
    @XmlElementWrapper(name = "rules")
    @XmlElement(name = "rule")
    private List<HighlightRule> rules = new ArrayList<>();

    public HighlightRuleSet() {
        this.id = UUID.randomUUID().toString();
    }

    public HighlightRuleSet(String id, String name, List<HighlightRule> rules) {
        this.id = id;
        this.name = name;
        setRules(rules);
    }

    /** A deep copy that keeps every id. */
    public HighlightRuleSet(HighlightRuleSet other) {
        this();
        if (other != null) {
            this.id = other.id;
            this.name = other.name;
            this.rules = new ArrayList<>();
            for (HighlightRule rule : other.getRules()) {
                if (rule != null) {
                    this.rules.add(new HighlightRule(rule));
                }
            }
        }
    }

    /**
     * A deep copy under a fresh set id and fresh rule ids — what "Duplicate" makes of a built-in or a
     * user set, so the copy never shares an id with its source.
     */
    public HighlightRuleSet duplicate(String newName) {
        HighlightRuleSet copy = new HighlightRuleSet(this);
        copy.id = UUID.randomUUID().toString();
        copy.name = newName;
        for (HighlightRule rule : copy.rules) {
            rule.setId(UUID.randomUUID().toString());
        }
        return copy;
    }

    public String getId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The live, mutable rule list; never {@code null}. */
    public List<HighlightRule> getRules() {
        if (rules == null) {
            rules = new ArrayList<>();
        }
        return rules;
    }

    public void setRules(List<HighlightRule> rules) {
        this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
    }
}
