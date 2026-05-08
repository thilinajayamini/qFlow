package com.qflow.api;

/**
 * Marker interface that every domain entity must implement to participate
 * in the qFlow Creator-Approver lifecycle.
 *
 * <p>Implementing this interface signals to the {@link com.qflow.service.ApprovalManager}
 * that the entity supports submission, approval, and rejection workflows.
 *
 * <p>The entity must expose its primary key via {@link #getEntityId()} so the
 * approval framework can correlate change requests to the correct record.
 *
 * <p>Usage:
 * <pre>{@code
 * public class Product implements Approvable {
 *
 *     private Long id;
 *
 *     @Trackable(label = "Product Name")
 *     private String name;
 *
 *     @Trackable(label = "Unit Price")
 *     private BigDecimal price;
 *
 *     @Override
 *     public String getEntityId() {
 *         return id == null ? null : id.toString();
 *     }
 * }
 * }</pre>
 */
public interface Approvable {

    /**
     * Returns the string representation of this entity's primary key.
     * For new (unsaved) entities being submitted for CREATE approval,
     * this may return {@code null} or a temporary client-side identifier.
     *
     * @return string form of the primary key, or {@code null} for unsaved entities
     */
    String getEntityId();
}
