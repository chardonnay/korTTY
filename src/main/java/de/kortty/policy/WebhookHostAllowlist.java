package de.kortty.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The hosts JobScheduler webhooks may be sent to ({@code [rule.job-scheduler] webhook-host-allowlist}).
 * An entry allows its own host and every host below it, matched on label boundaries: the entry
 * {@code hooks.slack.com} allows {@code hooks.slack.com} and {@code eu.hooks.slack.com}, but neither
 * {@code evilhooks.slack.com} nor {@code hooks.slack.com.attacker.net}. Host names are compared as
 * written in the URL; nothing is resolved through DNS.
 *
 * <p>When several rules of the winning tier set a list, each of them applies: a host must be allowed
 * by every list (the most restrictive combination). An empty list allows every host.
 *
 * @param lists the allowlists that apply, each already normalized; an empty inner list allows any host
 */
public record WebhookHostAllowlist(List<List<String>> lists) {

    /** Nothing set: every host is allowed. */
    public static final WebhookHostAllowlist NONE = new WebhookHostAllowlist(List.of());

    public WebhookHostAllowlist {
        List<List<String>> copy = new ArrayList<>();
        if (lists != null) {
            for (List<String> list : lists) {
                copy.add(list == null ? List.of() : List.copyOf(list));
            }
        }
        lists = List.copyOf(copy);
    }

    /** One rule's list. */
    public static WebhookHostAllowlist of(List<String> entries) {
        return new WebhookHostAllowlist(List.of(entries == null ? List.of() : entries));
    }

    /** Both lists apply (same-tier conflict): a host must pass each. */
    public WebhookHostAllowlist and(WebhookHostAllowlist other) {
        List<List<String>> combined = new ArrayList<>(lists);
        combined.addAll(other.lists);
        return new WebhookHostAllowlist(combined);
    }

    /** True when at least one non-empty list limits the hosts. */
    public boolean restricts() {
        return lists.stream().anyMatch(list -> !list.isEmpty());
    }

    /** Every entry of every list, in order and without duplicates, for messages and the UI. */
    public List<String> entries() {
        Set<String> all = new LinkedHashSet<>();
        lists.forEach(all::addAll);
        return List.copyOf(all);
    }

    /** Whether {@code host} may receive webhooks; a missing host is allowed only without a restriction. */
    public boolean allows(String host) {
        if (!restricts()) {
            return true;
        }
        if (normalizeHost(host) == null) {
            return false;
        }
        for (List<String> list : lists) {
            if (!list.isEmpty() && list.stream().noneMatch(entry -> matches(host, entry))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code host} equals {@code entry} or lies below it ({@code host} ends with
     * {@code "." + entry}). Both sides are normalized first; an empty side never matches.
     */
    public static boolean matches(String host, String entry) {
        String h = normalizeHost(host);
        String e = normalizeHost(entry);
        if (h == null || e == null) {
            return false;
        }
        return h.equals(e) || h.endsWith("." + e);
    }

    /**
     * An allowlist entry as the policy file writes it, normalized: lower case, without a leading
     * {@code *.} or {@code .} and without a trailing dot. Null for a value that is not a bare host
     * name (empty, a URL, a path, user info, whitespace or a wildcard inside the name).
     */
    public static String normalizeEntry(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("*.")) {
            value = value.substring(2);
        } else if (value.startsWith(".")) {
            value = value.substring(1);
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || value.contains("..") || value.startsWith(".")) {
            return null;
        }
        boolean bracketed = value.startsWith("[") && value.endsWith("]");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                || c == '_' || (bracketed && (c == ':' || c == '[' || c == ']'));
            if (!allowed) {
                return null;
            }
        }
        return normalizeHost(value);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }
}
