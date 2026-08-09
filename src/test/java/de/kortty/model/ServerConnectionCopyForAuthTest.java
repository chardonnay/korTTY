package de.kortty.model;

import jakarta.xml.bind.annotation.XmlTransient;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Guard for {@link ServerConnection#copyForAuth}: it copies every persisted field by hand and is
 * the funnel for all connections.xml saves (XMLConnectionRepository.prepareForPersistence), so a
 * field that is missing there is silently reset on the next save. This test populates every
 * persisted field with a value that differs from a fresh instance's default and asserts the copy
 * carries each one — adding a field without extending copyForAuth fails here immediately.
 */
public class ServerConnectionCopyForAuthTest {

    /**
     * Fields copyForAuth deliberately copies by value instead of sharing the reference; they are
     * compared field-by-field instead of via {@link Objects#equals}.
     */
    private static final Set<String> DEEP_COPIED_FIELDS = Set.of("logConfig");

    @Test
    void copyForAuthCarriesEveryPersistedField() throws Exception {
        ServerConnection defaults = new ServerConnection();
        ServerConnection source = new ServerConnection();
        List<Field> fields = persistedFields();

        int seed = 1;
        for (Field field : fields) {
            Object value = distinctValueFor(field, defaults, seed++);
            // A value equal to the fresh-instance default could never prove the field was copied,
            // because copyForAuth starts from new ServerConnection().
            assertWithMessage(
                    "Test bug: the generated value for '%s' equals the default of a fresh"
                            + " instance; teach distinctValueFor about this field",
                    field.getName())
                    .that(isDistinctFromDefault(field, value, defaults))
                    .isTrue();
            field.set(source, value);
        }

        ServerConnection copy = ServerConnection.copyForAuth(source);

        List<String> dropped = new ArrayList<>();
        for (Field field : fields) {
            Object expected = field.get(source);
            Object actual = field.get(copy);
            boolean carried = DEEP_COPIED_FIELDS.contains(field.getName())
                    ? equalByFields(expected, actual)
                    : Objects.equals(expected, actual);
            if (!carried) {
                dropped.add(field.getName() + " (expected " + expected + ", but copy has " + actual + ")");
            }
        }

        assertWithMessage(
                "ServerConnection.copyForAuth drops persisted fields; every field JAXB persists"
                        + " must be copied there, or the next save of connections.xml silently"
                        + " resets it")
                .that(dropped)
                .isEmpty();
    }

    /**
     * Every instance field JAXB persists: the class uses XmlAccessType.FIELD, so each non-static,
     * non-transient field without {@link XmlTransient} ends up in connections.xml whether it is
     * annotated or not.
     */
    private static List<Field> persistedFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : ServerConnection.class.getDeclaredFields()) {
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

    private static boolean isDistinctFromDefault(Field field, Object value, ServerConnection defaults)
            throws Exception {
        Object defaultValue = field.get(defaults);
        return DEEP_COPIED_FIELDS.contains(field.getName())
                ? !equalByFields(value, defaultValue)
                : !Objects.equals(value, defaultValue);
    }

    private static Object distinctValueFor(Field field, ServerConnection defaults, int seed)
            throws Exception {
        Class<?> type = field.getType();
        if (type == String.class) {
            return "test-" + field.getName();
        }
        if (type == int.class || type == Integer.class) {
            return 40_000 + seed;
        }
        if (type == long.class || type == Long.class) {
            return 7_000_000L + seed;
        }
        if (type == double.class || type == Double.class) {
            return 3.5 + seed;
        }
        if (type == boolean.class) {
            return !field.getBoolean(defaults);
        }
        if (type == Boolean.class) {
            Boolean current = (Boolean) field.get(defaults);
            return current == null || !current;
        }
        if (type.isEnum()) {
            return enumConstantOtherThan(type, field.get(defaults), field.getName());
        }
        if (List.class.isAssignableFrom(type)) {
            List<Object> list = new ArrayList<>();
            list.add(newElementFor(field, seed));
            return list;
        }
        return populate(type.getDeclaredConstructor().newInstance(), seed);
    }

    /** Fills the instance's simple fields with non-default values so value comparison means something. */
    private static Object populate(Object instance, int seed) throws Exception {
        int offset = seed * 100;
        for (Field field : instance.getClass().getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.isSynthetic() || Modifier.isStatic(mods)) {
                continue;
            }
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == String.class) {
                field.set(instance, "test-" + field.getName() + "-" + offset);
            } else if (type == int.class || type == Integer.class) {
                field.set(instance, offset++);
            } else if (type == long.class || type == Long.class) {
                field.set(instance, (long) offset++);
            } else if (type == double.class || type == Double.class) {
                field.set(instance, offset++ + 0.5);
            } else if (type == boolean.class) {
                field.setBoolean(instance, !field.getBoolean(instance));
            } else if (type == Boolean.class) {
                Boolean current = (Boolean) field.get(instance);
                field.set(instance, current == null || !current);
            } else if (type.isEnum()) {
                field.set(instance, enumConstantOtherThan(type, field.get(instance), field.getName()));
            }
            // Fields of other types keep their defaults; the outer comparison still catches a
            // dropped reference because none of these classes overrides equals().
        }
        return instance;
    }

    private static Object enumConstantOtherThan(Class<?> enumType, Object current, String fieldName) {
        for (Object constant : enumType.getEnumConstants()) {
            if (!constant.equals(current)) {
                return constant;
            }
        }
        throw new AssertionError("Enum " + enumType.getSimpleName() + " for field '" + fieldName
                + "' has no constant differing from its default");
    }

    private static Object newElementFor(Field field, int seed) throws Exception {
        Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType parameterized) {
            Type element = parameterized.getActualTypeArguments()[0];
            if (element == String.class) {
                return "test-element-" + field.getName();
            }
            if (element instanceof Class<?> elementClass) {
                return populate(elementClass.getDeclaredConstructor().newInstance(), seed);
            }
        }
        throw new AssertionError("Cannot build a list element for field '" + field.getName()
                + "'; teach newElementFor about its element type");
    }

    /** Shallow field-by-field comparison for deliberately deep-copied values without equals(). */
    private static boolean equalByFields(Object a, Object b) throws Exception {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.getClass() != b.getClass()) {
            return false;
        }
        for (Field field : a.getClass().getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            if (!Objects.equals(field.get(a), field.get(b))) {
                return false;
            }
        }
        return true;
    }
}
