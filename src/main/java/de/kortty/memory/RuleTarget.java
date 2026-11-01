package de.kortty.memory;

import java.util.Locale;

/**
 * Where a confirmed rule is saved: the global memory, the memory of one connection, an AI skill the user
 * already has, a new korTTY skill, or {@link None} when the policy and the settings allow no place at all.
 */
public sealed interface RuleTarget permits RuleTarget.Global, RuleTarget.Connection, RuleTarget.Skill,
    RuleTarget.NewSkill, RuleTarget.None {

    static RuleTarget global() {
        return new Global();
    }

    static RuleTarget connection(String connectionId) {
        return new Connection(connectionId);
    }

    static RuleTarget skill(String skillId) {
        return new Skill(skillId);
    }

    static RuleTarget newSkill(String topic, String name) {
        return new NewSkill(topic, name);
    }

    static RuleTarget none() {
        return new None();
    }

    /** The global memory file. */
    record Global() implements RuleTarget {
    }

    /** The memory file of one connection, by its stable id. */
    record Connection(String connectionId) implements RuleTarget {

        public Connection {
            if (connectionId == null || connectionId.isBlank()) {
                throw new IllegalArgumentException("A connection target needs a connection id.");
            }
            connectionId = connectionId.strip();
        }
    }

    /** The learned rules of an existing skill, by its id. The skill's content is never changed. */
    record Skill(String skillId) implements RuleTarget {

        public Skill {
            if (skillId == null || skillId.isBlank()) {
                throw new IllegalArgumentException("A skill target needs a skill id.");
            }
            skillId = skillId.strip();
        }
    }

    /**
     * A new skill korTTY creates for {@code topic}, named {@code name} (for example "Elixir (korTTY)"). The topic is
     * lower case and becomes the skill's tag.
     */
    record NewSkill(String topic, String name) implements RuleTarget {

        public NewSkill {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("A new skill needs a topic.");
            }
            topic = topic.strip().toLowerCase(Locale.ROOT);
            name = name == null || name.isBlank() ? topic : name.strip();
        }
    }

    /** No target: the policy allows neither the memory nor the creation or change of skills. */
    record None() implements RuleTarget {
    }
}
