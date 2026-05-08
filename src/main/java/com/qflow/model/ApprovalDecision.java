package com.qflow.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit record for a single approver decision on a {@link ChangeRequest}.
 *
 * <p>One {@code ApprovalDecision} is created each time an approver acts on a
 * change request (approve or reject). Together these records provide a full
 * audit trail of who approved/rejected what, at which level, and when.
 *
 * <p><strong>Table:</strong> {@code qflow_approval_decision}
 */
@Entity
@Table(name = "qflow_approval_decision", indexes = {
        @Index(name = "idx_ad_change_request", columnList = "change_request_id"),
        @Index(name = "idx_ad_approver",        columnList = "approver_id")
})
public class ApprovalDecision implements Serializable {

    @Id
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "change_request_id", nullable = false)
    private ChangeRequest changeRequest;

    /** The approval level at which this decision was made (1, 2, …). */
    @Column(name = "approval_level", nullable = false)
    private int approvalLevel;

    /** Identifier (username, user ID, etc.) of the person making the decision. */
    @Column(name = "approver_id", nullable = false, length = 255)
    private String approverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 10)
    private Decision decision;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    // ------------------------------------------------------------------
    // Inner enum
    // ------------------------------------------------------------------

    public enum Decision { APPROVED, REJECTED }

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    protected ApprovalDecision() {}

    /**
     * Creates a new {@code ApprovalDecision} record.
     *
     * @param changeRequest the request being acted upon
     * @param approvalLevel the level at which this decision is made
     * @param approverId    identifier of the approver
     * @param decision      APPROVED or REJECTED
     * @param remarks       optional comments from the approver
     */
    public static ApprovalDecision of(ChangeRequest changeRequest,
                                      int approvalLevel,
                                      String approverId,
                                      Decision decision,
                                      String remarks) {
        ApprovalDecision ad = new ApprovalDecision();
        ad.id            = UUID.randomUUID().toString();
        ad.changeRequest = changeRequest;
        ad.approvalLevel = approvalLevel;
        ad.approverId    = approverId;
        ad.decision      = decision;
        ad.decidedAt     = Instant.now();
        ad.remarks       = remarks;
        return ad;
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------

    public String getId()                  { return id; }
    public ChangeRequest getChangeRequest(){ return changeRequest; }
    public int getApprovalLevel()          { return approvalLevel; }
    public String getApproverId()          { return approverId; }
    public Decision getDecision()          { return decision; }
    public Instant getDecidedAt()          { return decidedAt; }
    public String getRemarks()             { return remarks; }
}
