package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlType;

/** Payload format a webhook target expects. */
@XmlType(name = "WebhookFormat")
@XmlEnum
public enum WebhookFormat {
    /** Slack incoming webhook. */
    SLACK,
    /** Microsoft Teams Workflows webhook carrying an Adaptive Card. */
    TEAMS,
    /** korTTY's own versioned JSON document for any HTTP receiver. */
    GENERIC_JSON
}
