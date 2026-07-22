package de.kortty.policy;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of loading a policy file. {@code file} is null when {@code errors} is non-empty — the
 * whole file is rejected on any error (all-or-nothing, so a partially applied policy can never
 * grant more than the admin wrote). Warnings (e.g. unknown keys) do not invalidate the file.
 *
 * @param file     the validated policy, or null on any error
 * @param errors   admin-readable validation errors, with TOML positions where available
 * @param warnings non-fatal findings
 * @param source   the file that was read
 */
public record PolicyLoadResult(PolicyFile file, List<String> errors, List<String> warnings, Path source) {

    public PolicyLoadResult {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean isValid() {
        return file != null && errors.isEmpty();
    }
}
