package com.qflow.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

/**
 * Represents a single field-level change captured during an UPDATE action.
 *
 * <p>Stored as an embeddable component inside {@link ChangeRequest}. Each
 * {@code FieldChange} records the field name, the serialised JSON value before
 * the change, and the serialised JSON value after the change.
 *
 * <p>Example:
 * <pre>
 *   fieldName : "price"
 *   oldValue  : "100.00"
 *   newValue  : "120.00"
 * </pre>
 */
@Embeddable
public class FieldChange implements Serializable {

    @Column(name = "field_name", nullable = false, length = 255)
    private String fieldName;

    /** JSON-serialised representation of the value before the change. */
    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    /** JSON-serialised representation of the value after the change. */
    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    protected FieldChange() {}

    public FieldChange(String fieldName, String oldValue, String newValue) {
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public String getFieldName() { return fieldName; }
    public String getOldValue()  { return oldValue; }
    public String getNewValue()  { return newValue; }

    @Override
    public String toString() {
        return "FieldChange{field='" + fieldName + "', old='" + oldValue + "', new='" + newValue + "'}";
    }
}
