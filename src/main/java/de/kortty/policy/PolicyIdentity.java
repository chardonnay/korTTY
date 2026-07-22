package de.kortty.policy;

import java.util.Set;

/**
 * The identity policy rules are resolved against. Abstracted so that a directory-backed provider
 * (LDAP/AD) can replace {@link OsUserIdentity} later without touching the resolution logic.
 */
public interface PolicyIdentity {

    /** The login name, lowercased. */
    String userName();

    /**
     * The OS-level group memberships of the user, lowercased. On domain-joined Windows machines
     * this includes AD groups (both {@code domain\group} and the bare group name). Empty when
     * membership could not be determined — never null.
     */
    Set<String> osGroups();
}
