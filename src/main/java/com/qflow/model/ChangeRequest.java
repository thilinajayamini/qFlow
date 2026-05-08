package com.qflow.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The central JPA entity for the qFlow approval workflow.
 *
 * <p>A {@code ChangeRequest} is created whenever a user submits a domain entity
 * for creation, modification, or deletion. It tracks:
 * <ul>
 *   <li>Which entity is being changed ({@link #entityType} + {@link #entityId})</li>
 *   <li>What action was requested ({@link #actionType})</li>
 *   <li>The full serialised snapshot of the proposed entity state ({@link #proposedSnapshot})</li>
 *   <li>A list of field-level diffs ({@link #changeSet}) for UPDATE actions</li>
 *   <li>The current approval status and which level is pending</li>
 * </ul>
 *
 * <p>Approver decisions are captured in separate {@link ApprovalDecision} records
 * linked back to this entity.
 *
 * <p><strong>Table:</strong> {@code qflow_change_request}
 */
@Entity
@Table(name = "qflow_change_request", indexes = {
        @Index(name = "idx_cr_entity",   columnList = "entity_type, entity_id"),
        @Index(name = "idx_cr_status",   columnList = "status"),
        @Index(name = "idx_cr_requester", columnList = "requested_by")
})
public class ChangeRequest implements Serializable {

    @Id
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private String id;

    // ------------------------------------------------------------------
    // Target entity identification
    // ------------------------------------------------------------------

    /** Fully-qualified class name of the entity being changed. */
    @Column(name = "entity_type", nullable = false, length = 512)
    private String entityType;

    /** String representation of the primary key of the target entity. May be null for CREATE on unsaved entities. */
    @Column(name = "entity_id", nullable = true, length = 255)
    private String entityId;

    // ------------------------------------------------------------------
    // Action & status
    // ------------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApprovalStatus status;

    /**
     * The approval level currently required.
     * 1 = awaiting L1, 2 = awaiting L2, etc.
     */
    @Column(name = "current_level", nullable = false)
    private int currentLevel;

    /**
     * Total number of approval levels required before this request is approved.
     * Configured via {@link com.qflow.api.ApprovalWorkflowConfig}.
     */
    @Column(name = "required_levels", nullable = false)
    private int requiredLevels;

    // ------------------------------------------------------------------
    // Requester metadata
    // ------------------------------------------------------------------

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    // ------------------------------------------------------------------
    // Proposed state (full JSON snapshot of the entity-to-be)
    // ------------------------------------------------------------------

    /**
     * Full JSON serialisation of the entity in its proposed (new) state.
     * Used during CREATE so approvers see the complete new object.
     * For UPDATE, approvers use the {@link #changeSet} for the diff view.
     */
    @Column(name = "proposed_snapshot", columnDefinition = "TEXT")
    private String proposedSnapshot;

    // ------------------------------------------------------------------
    // Field-level change set
    // ------------------------------------------------------------------

    /**
     * Ordered list of field-level changes captured by the {@link com.qflow.engine.DiffEngine}.
     * Populated only for UPDATE actions.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "qflow_change_set",
            joinColumns = @JoinColumn(name = "change_request_id")
    )
    @OrderColumn(name = "change_order")
    private List<FieldChange> changeSet = new ArrayList<>();

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /**
     * UUID of the original {@link ChangeRequest} that was REJECTED and then
     * resubmitted by the creator. {@code null} for first-time submissions.
     */
    @Column(name = "parent_request_id", length = 36)
    private String parentRequestId;

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    protected ChangeRequest() {}

    /**
     * Factory method — the preferred way to create a new {@code ChangeRequest}.
     *
     * @param entityType     fully-qualified class name of the target entity
     * @param entityId       string form of the entity's primary key
     * @param actionType     CREATE, UPDATE, or DELETE
     * @param requiredLevels number of approval levels configured for this entity type
     * @param requestedBy    identifier of the user submitting the request
     * @param comments       optional notes from the requester
     * @return a newly initialised {@code ChangeRequest} with status PENDING_L1
     */
    public static ChangeRequest create(String entityType,
                                       String entityId,
                                       ActionType actionType,
                                       int requiredLevels,
                                       String requestedBy,
                                       String comments) {
        ChangeRequest cr = new ChangeRequest();
        cr.id             = UUID.randomUUID().toString();
        cr.entityType     = entityType;
        cr.entityId       = entityId;
        cr.actionType     = actionType;
        cr.status         = ApprovalStatus.PENDING_L1;
        cr.currentLevel   = 1;
        cr.requiredLevels = requiredLevels;
        cr.requestedBy    = requestedBy;
        cr.requestedAt    = Instant.now();
        cr.comments       = comments;
        return cr;
    }

    // ------------------------------------------------------------------
    // Business methods
    // ------------------------------------------------------------------

    /**
     * Advances the request to the next approval level, or marks it APPROVED
     * if all required levels have been satisfied.
     */
    public void advanceLevel() {
        if (actionType == ActionType.DELETE) {
            // DELETE requests skip level tracking — one approval marks as DELETED
            this.status = ApprovalStatus.DELETED;
            this.resolvedAt = Instant.now();
        } else if (currentLevel >= requiredLevels) {
            this.status = ApprovalStatus.APPROVED;
            this.resolvedAt = Instant.now();
        } else {
            this.currentLevel++;
            this.status = resolveStatusForLevel(currentLevel);
        }
    }

    /**
     * Marks the request as REJECTED and records the rejection metadata.
     *
     * @param rejectedBy    identifier of the approver who rejected
     * @param reason        human-readable reason for rejection
     */
    public void reject(String rejectedBy, String reason) {
        this.status          = ApprovalStatus.REJECTED;
        this.resolvedAt      = Instant.now();
        this.resolvedBy      = rejectedBy;
        this.rejectionReason = reason;
    }

    /** Returns {@code true} when no further approvals are needed. */
    public boolean isFinalised() {
        return status == ApprovalStatus.APPROVED
            || status == ApprovalStatus.REJECTED
            || status == ApprovalStatus.DELETED;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static ApprovalStatus resolveStatusForLevel(int level) {
        return switch (level) {
            case 1 -> ApprovalStatus.PENDING_L1;
            case 2 -> ApprovalStatus.PENDING_L2;
            default -> ApprovalStatus.PENDING_L1;
        };
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------

    public String getId()               { return id; }
    public String getEntityType()       { return entityType; }
    public String getEntityId()         { return entityId; }
    public ActionType getActionType()   { return actionType; }
    public ApprovalStatus getStatus()   { return status; }
    public int getCurrentLevel()        { return currentLevel; }
    public int getRequiredLevels()      { return requiredLevels; }
    public String getRequestedBy()      { return requestedBy; }
    public Instant getRequestedAt()     { return requestedAt; }
    public String getComments()         { return comments; }
    public String getProposedSnapshot() { return proposedSnapshot; }
    public Instant getResolvedAt()      { return resolvedAt; }
    public String getResolvedBy()       { return resolvedBy; }
    public String getRejectionReason()  { return rejectionReason; }
    public String getParentRequestId()  { return parentRequestId; }

    public List<FieldChange> getChangeSet() {
        return Collections.unmodifiableList(changeSet);
    }

    // ------------------------------------------------------------------
    // Package-private setters (used by ApprovalManager / DiffEngine)
    // ------------------------------------------------------------------

    public void setProposedSnapshot(String proposedSnapshot) {
        this.proposedSnapshot = proposedSnapshot;
    }

    public void setChangeSet(List<FieldChange> changeSet) {
        this.changeSet.clear();
        this.changeSet.addAll(changeSet);
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public void setParentRequestId(String parentRequestId) {
        this.parentRequestId = parentRequestId;
    }

    /** Used when a DELETE request is submitted — bypasses L1/L2 level status. */
    public void overrideStatus(ApprovalStatus status) {
        this.status = status;
    }
}
