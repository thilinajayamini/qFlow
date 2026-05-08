package com.qflow.repository;

import com.qflow.model.ApprovalDecision;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.List;

/**
 * JPA-based repository for {@link ApprovalDecision} persistence.
 *
 * <p>Provides access to the per-level audit records for any given
 * change request. Transaction management is the caller's responsibility.
 */
public class ApprovalDecisionRepository {

    protected final EntityManager em;

    public ApprovalDecisionRepository(EntityManager em) {
        this.em = em;
    }

    // ------------------------------------------------------------------
    // Write operations
    // ------------------------------------------------------------------

    /** Persists a new {@link ApprovalDecision}. */
    public void save(ApprovalDecision decision) {
        em.persist(decision);
    }

    // ------------------------------------------------------------------
    // Read operations
    // ------------------------------------------------------------------

    /**
     * Returns all decisions recorded for a given change request, in
     * chronological order (oldest first).
     *
     * @param changeRequestId UUID of the change request
     * @return ordered list of approver decisions
     */
    public List<ApprovalDecision> findByChangeRequest(String changeRequestId) {
        TypedQuery<ApprovalDecision> q = em.createQuery(
                "SELECT ad FROM ApprovalDecision ad " +
                "WHERE ad.changeRequest.id = :crId " +
                "ORDER BY ad.decidedAt ASC",
                ApprovalDecision.class);
        q.setParameter("crId", changeRequestId);
        return q.getResultList();
    }

    /**
     * Returns all decisions made by a specific approver, newest first.
     *
     * @param approverId identifier of the approver
     * @return list of decisions
     */
    public List<ApprovalDecision> findByApprover(String approverId) {
        TypedQuery<ApprovalDecision> q = em.createQuery(
                "SELECT ad FROM ApprovalDecision ad " +
                "WHERE ad.approverId = :approver " +
                "ORDER BY ad.decidedAt DESC",
                ApprovalDecision.class);
        q.setParameter("approver", approverId);
        return q.getResultList();
    }
}
