package com.qflow.api;

/**
 * Strategy interface for configuring the approval workflow for a specific
 * entity type.
 *
 * <p>Implement this interface and register it with
 * {@link com.qflow.service.ApprovalManager} to control how many approval
 * levels are required before a change is applied to a given entity type.
 *
 * <p>Example — two-level approval for {@code Product}:
 * <pre>{@code
 * public class ProductWorkflowConfig implements ApprovalWorkflowConfig {
 *
 *     @Override
 *     public Class<?> entityType() {
 *         return Product.class;
 *     }
 *
 *     @Override
 *     public int requiredLevels() {
 *         return 2;          // L1 → L2 → APPROVED
 *     }
 * }
 * }</pre>
 *
 * <p>If no config is registered for an entity type,
 * {@link com.qflow.service.ApprovalManager} defaults to <strong>1</strong> level.
 */
public interface ApprovalWorkflowConfig {

    /**
     * The entity class this configuration applies to.
     *
     * @return the entity class
     */
    Class<?> entityType();

    /**
     * The number of sequential approval levels required before a change request
     * transitions to {@link com.qflow.model.ApprovalStatus#APPROVED}.
     *
     * <p>Must be ≥ 1. The current supported range is 1–2, corresponding to
     * {@code PENDING_L1} and {@code PENDING_L2} statuses. Extend
     * {@link com.qflow.model.ChangeRequest#resolveStatusForLevel} (via subclass)
     * to support deeper hierarchies.
     *
     * @return number of approval levels; default is 1
     */
    default int requiredLevels() {
        return 1;
    }
}
