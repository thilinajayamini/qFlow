package com.qflow.model;

/**
 * Represents the lifecycle status of an entity undergoing the approval process.
 *
 * <p>Lifecycle transitions:
 * <pre>
 *   CREATE/UPDATE:
 *   DRAFT ──► PENDING_L1 ──► PENDING_L2 ──► APPROVED
 *                  │               │
 *                  └──► REJECTED ◄─┘
 *                         │
 *                         └──► RESUBMITTED ──► PENDING_L1 ──► ...
 *
 *   DELETE:
 *   PENDING_DELETE ──► DELETED
 *         │
 *         └──► REJECTED
 * </pre>
 */
public enum ApprovalStatus {

    /** The entity has been created or modified but not yet submitted for approval. */
    DRAFT,

    /** The change request is awaiting a Level-1 approver decision. */
    PENDING_L1,

    /** The change request passed Level-1 and is awaiting a Level-2 approver decision. */
    PENDING_L2,

    /** Awaiting approval of a DELETE action. */
    PENDING_DELETE,

    /** All required approval levels satisfied; changes applied to the entity. */
    APPROVED,

    /**
     * The delete request was approved; the entity is logically removed.
     * The caller is responsible for physically deleting or soft-deleting the record.
     */
    DELETED,

    /** The change request was rejected at any approval level; no changes applied. */
    REJECTED,

    /**
     * The creator has edited and resubmitted a previously REJECTED request.
     * A new {@link com.qflow.model.ChangeRequest} is created with a link
     * ({@code parentRequestId}) to the original rejected request.
     */
    RESUBMITTED
}
