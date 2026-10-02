package de.kortty.model;

import jakarta.xml.bind.annotation.XmlTransient;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection helpers for the {@link ConnectionSettings} copy guards: enumerates every field JAXB
 * persists and fills each with a value derived from a seed, so two differently seeded instances
 * differ in every field and an odd seed differs from a fresh instance in every field.
 */
final class ConnectionSettingsFieldValues {

    private ConnectionSettingsFieldValues() {
    }

    /**
     * Every instance field JAXB persists: the class uses XmlAccessType.FIELD, so each non-static,
     * non-transient field without {@link XmlTransient} ends up in the settings XML.
     */
    static List<Field> persistedFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : ConnectionSettings.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.isSynthetic() || Modifier.isStatic(mods) || Modifier.isTransient(mods)
                    || field.isAnnotationPresent(XmlTransient.class)) {
                continue;
            }
            field.setAccessible(true);
            fields.add(field);
        }
        return fields;
    }

    /**
     * A settings object whose persisted fields all carry seed-specific values. With an odd seed
     * every field differs from {@code new ConnectionSettings()}; booleans can only tell two seeds
     * apart by parity, so combine an odd and an even seed when comparing two instances.
     */
    static ConnectionSettings populatedDistinct(int seed) throws Exception {
        ConnectionSettings defaults = new ConnectionSettings();
        ConnectionSettings settings = new ConnectionSettings();
        int ordinal = 0;
        for (Field field : persistedFields()) {
            Class<?> type = field.getType();
            Object value;
            if (type == String.class) {
                value = "test-" + field.getName() + "-" + seed;
            } else if (type == int.class) {
                value = 40_000 + seed * 1_000 + ordinal;
            } else if (type == boolean.class) {
                boolean fresh = field.getBoolean(defaults);
                value = seed % 2 != 0 ? !fresh : fresh;
            } else {
                throw new AssertionError("Teach ConnectionSettingsFieldValues about field '"
                        + field.getName() + "' of type " + type.getName());
            }
            field.set(settings, value);
            ordinal++;
        }
        return settings;
    }
}
