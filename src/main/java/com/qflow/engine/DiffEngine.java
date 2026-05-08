package com.qflow.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qflow.api.Trackable;
import com.qflow.model.FieldChange;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reflection-based utility that computes a field-level diff between two instances
 * of the same {@link com.qflow.api.Approvable} entity class.
 *
 * <p>Only fields annotated with {@link Trackable} are included in the diff.
 * The comparison walks the class hierarchy (superclasses included), so fields
 * declared in parent classes are tracked as well.
 *
 * <p>Field values are serialised to JSON via Jackson before storing as
 * {@link FieldChange} records. This ensures that complex types (dates,
 * BigDecimal, nested objects) are represented faithfully.
 *
 * <p>Thread-safety: {@code DiffEngine} is stateless and safe to share.
 */
public final class DiffEngine {

    private final ObjectMapper mapper;

    public DiffEngine() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Computes the diff between {@code current} (the live entity) and
     * {@code proposed} (the modified entity submitted for approval).
     *
     * <p>For CREATE actions, pass {@code null} as {@code current}; all
     * {@link Trackable} fields on {@code proposed} will appear in the change
     * set with {@code oldValue = null}.
     *
     * @param current  the existing entity state, or {@code null} for CREATE
     * @param proposed the new entity state submitted by the creator
     * @param <T>      entity type
     * @return an unmodifiable list of {@link FieldChange} objects, one per changed field
     * @throws DiffException if reflection or JSON serialisation fails
     */
    public <T> List<FieldChange> diff(T current, T proposed) {
        if (proposed == null) {
            throw new IllegalArgumentException("proposed entity must not be null");
        }

        List<FieldChange> changes = new ArrayList<>();
        Class<?> clazz = proposed.getClass();

        // Walk the class hierarchy to pick up inherited @Trackable fields
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Trackable.class)) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object oldVal = (current == null) ? null : field.get(current);
                    Object newVal = field.get(proposed);

                    if (!Objects.equals(oldVal, newVal)) {
                        String label    = resolveLabel(field);
                        String oldJson  = toJson(oldVal);
                        String newJson  = toJson(newVal);
                        changes.add(new FieldChange(label, oldJson, newJson));
                    }
                } catch (IllegalAccessException e) {
                    throw new DiffException(
                            "Cannot access field '" + field.getName() + "' on " + clazz.getName(), e);
                }
            }
            clazz = clazz.getSuperclass();
        }

        return Collections.unmodifiableList(changes);
    }

    /**
     * Serialises the full state of an entity to a JSON string.
     * Used to populate {@link com.qflow.model.ChangeRequest#getProposedSnapshot()}.
     *
     * @param entity the entity to serialise
     * @return JSON string
     * @throws DiffException if serialisation fails
     */
    public String toSnapshot(Object entity) {
        try {
            return mapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            throw new DiffException("Failed to serialise entity snapshot", e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String resolveLabel(Field field) {
        Trackable annotation = field.getAnnotation(Trackable.class);
        String label = annotation.label();
        return (label == null || label.isBlank()) ? field.getName() : label;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Fall back to toString() if Jackson can't handle the type
            return value.toString();
        }
    }

    // ------------------------------------------------------------------
    // Exception
    // ------------------------------------------------------------------

    /**
     * Unchecked exception thrown when the diff computation fails due to
     * reflection or serialisation errors.
     */
    public static class DiffException extends RuntimeException {
        public DiffException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
