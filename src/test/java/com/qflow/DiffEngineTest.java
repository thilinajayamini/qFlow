package com.qflow;

import com.qflow.engine.DiffEngine;
import com.qflow.model.FieldChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DiffEngine}.
 *
 * <p>These tests are purely in-memory — no JPA or database is involved.
 */
@DisplayName("DiffEngine")
class DiffEngineTest {

    private DiffEngine diffEngine;

    @BeforeEach
    void setUp() {
        diffEngine = new DiffEngine();
    }

    // ------------------------------------------------------------------
    // diff() — basic cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No changes: returns empty list when entities are identical")
    void diff_identicalEntities_returnsEmpty() {
        Product a = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        Product b = new Product(1L, "Widget", new BigDecimal("9.99"), 100);

        List<FieldChange> changes = diffEngine.diff(a, b);

        assertTrue(changes.isEmpty(), "Expected no changes for identical entities");
    }

    @Test
    @DisplayName("Single field change: detects name change")
    void diff_nameChanged_detectsOneChange() {
        Product current  = new Product(1L, "Widget",   new BigDecimal("9.99"), 100);
        Product proposed = new Product(1L, "Gadget",   new BigDecimal("9.99"), 100);

        List<FieldChange> changes = diffEngine.diff(current, proposed);

        assertEquals(1, changes.size());
        FieldChange fc = changes.get(0);
        assertEquals("Product Name", fc.getFieldName());
        assertTrue(fc.getOldValue().contains("Widget"));
        assertTrue(fc.getNewValue().contains("Gadget"));
    }

    @Test
    @DisplayName("Multiple field changes: detects all changed @Trackable fields")
    void diff_multipleChanges_detectsAll() {
        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"),  100);
        Product proposed = new Product(1L, "Gadget", new BigDecimal("14.99"),  50);

        List<FieldChange> changes = diffEngine.diff(current, proposed);

        assertEquals(3, changes.size(), "Expected 3 tracked fields changed");
    }

    @Test
    @DisplayName("Untracked field change: ignored by diff")
    void diff_untrackedFieldChanged_notIncluded() {
        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        Product proposed = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        current.setInternalCode("OLD-001");
        proposed.setInternalCode("NEW-999");   // not @Trackable

        List<FieldChange> changes = diffEngine.diff(current, proposed);

        assertTrue(changes.isEmpty(), "Untracked field should not appear in diff");
    }

    @Test
    @DisplayName("CREATE diff: null current → all @Trackable fields have null oldValue")
    void diff_nullCurrent_allFieldsNewWithNullOld() {
        Product proposed = new Product(null, "Brand New", new BigDecimal("5.00"), 10);

        List<FieldChange> changes = diffEngine.diff(null, proposed);

        assertFalse(changes.isEmpty());
        changes.forEach(fc ->
                assertNull(fc.getOldValue(), "Old value should be null for CREATE diff"));
    }

    @Test
    @DisplayName("Null proposed: throws IllegalArgumentException")
    void diff_nullProposed_throwsException() {
        Product current = new Product(1L, "Widget", BigDecimal.ONE, 1);

        assertThrows(IllegalArgumentException.class,
                () -> diffEngine.diff(current, null));
    }

    // ------------------------------------------------------------------
    // toSnapshot()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toSnapshot: produces non-empty JSON string")
    void toSnapshot_returnsJson() {
        Product product = new Product(42L, "Widget", new BigDecimal("9.99"), 100);

        String snapshot = diffEngine.toSnapshot(product);

        assertNotNull(snapshot);
        assertTrue(snapshot.contains("Widget"),    "Snapshot should contain entity data");
        assertTrue(snapshot.contains("9.99"),      "Snapshot should contain price");
    }

    @Test
    @DisplayName("Field label: uses @Trackable label when provided")
    void diff_usesTrackableLabel() {
        Product current  = new Product(1L, "A", BigDecimal.ONE,  1);
        Product proposed = new Product(1L, "B", BigDecimal.TEN, 10);

        List<FieldChange> changes = diffEngine.diff(current, proposed);

        List<String> fieldNames = changes.stream().map(FieldChange::getFieldName).toList();
        assertTrue(fieldNames.contains("Product Name"),    "Should use @Trackable label");
        assertTrue(fieldNames.contains("Unit Price"),      "Should use @Trackable label");
        assertTrue(fieldNames.contains("Stock Quantity"),  "Should use @Trackable label");
    }
}
