package com.qflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qflow.api.Trackable;
import com.qflow.model.FieldChange;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Applies an approved {@link com.qflow.model.ChangeRequest}'s change set to
 * the live entity, or deserialises a full snapshot over it for CREATE actions.
 *
 * <p>For UPDATE workflows the applier iterates over the {@link FieldChange} list
 * and sets each tracked field to its {@code newValue} (deserialised from JSON).
 * For CREATE workflows it deserialises the full JSON snapshot onto the entity.
 *
 * <p>Thread-safety: {@code EntityApplier} is stateless and safe to share.
 *
 * @author Thilina Jayamini
 * @since 2026-05-08
 */
public final class EntityApplier {

    private final ObjectMapper mapper;

    public EntityApplier() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Applies the approved change set to the target entity by updating each
     * {@link Trackable}-annotated field to its new value.
     *
     * <p>Fields are matched by label (or field name when no label is set).
     *
     * @param target    the live entity to update in-place
     * @param changeSet the list of approved field changes from the ChangeRequest
     * @throws ApplyException if reflection or JSON deserialisation fails
     */
    public void applyChangeSet(Object target, List<FieldChange> changeSet) {
        if (target == null || changeSet == null || changeSet.isEmpty()) return;

        Class<?> clazz = target.getClass();

        for (FieldChange fc : changeSet) {
            Field field = findField(clazz, fc.getFieldName());
            if (field == null) {
                // Field may have been removed from the class — skip gracefully
                continue;
            }
            field.setAccessible(true);
            try {
                Object newValue = (fc.getNewValue() == null)
                        ? null
                        : mapper.readValue(fc.getNewValue(), field.getType());
                field.set(target, newValue);
            } catch (Exception e) {
                throw new ApplyException(
                        "Failed to apply change for field '" + fc.getFieldName() + "'", e);
            }
        }
    }

    /**
     * Deserialises a full JSON snapshot onto the target entity for CREATE workflows.
     *
     * @param target           the entity instance to populate
     * @param proposedSnapshot the JSON snapshot from the approved ChangeRequest
     * @param <T>              entity type
     * @throws ApplyException if deserialisation fails
     */
    @SuppressWarnings("unchecked")
    public <T> T applySnapshot(T target, String proposedSnapshot) {
        try {
            return (T) mapper.readerForUpdating(target).readValue(proposedSnapshot);
        } catch (Exception e) {
            throw new ApplyException("Failed to apply snapshot to entity", e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Walks the class hierarchy to find a {@link Trackable} field whose label
     * (or Java field name) matches {@code label}.
     */
    private Field findField(Class<?> clazz, String label) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.isAnnotationPresent(Trackable.class)) continue;
                String resolved = resolveLabel(f);
                if (resolved.equals(label)) return f;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private String resolveLabel(Field field) {
        Trackable ann = field.getAnnotation(Trackable.class);
        String label = ann.label();
        return (label == null || label.isBlank()) ? field.getName() : label;
    }

    // ------------------------------------------------------------------
    // Exception
    // ------------------------------------------------------------------

    /**
     * Unchecked exception thrown when applying a change set or snapshot fails.
     */
    public static class ApplyException extends RuntimeException {
        public ApplyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
