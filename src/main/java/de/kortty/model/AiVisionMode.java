package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;

/**
 * Whether a profile may send images ("vision") with a prompt. {@code AUTO} derives the capability
 * from discovered LM Studio metadata or model-name heuristics; {@code ENABLED}/{@code DISABLED}
 * are explicit user overrides for models the heuristics misjudge.
 */
@XmlEnum
public enum AiVisionMode {
    AUTO,
    ENABLED,
    DISABLED
}
