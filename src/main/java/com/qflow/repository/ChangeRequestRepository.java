package com.qflow.repository;

import com.qflow.model.ApprovalStatus;
import com.qflow.model.ChangeRequest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.Optional;

/**
 * JPA-based repository for {@link ChangeRequest} persistence operations.
 *
 * <p>This class is intentionally framework-agnostic: it uses only a standard
 * JPA {@link EntityManager}. Users can extend it, wrap it in a Spring
 * {@code @Repository}, or simply inject it from a CDI context.
 *
 * <p>Transaction management is the caller's responsibility.
 */
public class ChangeRequestRepository {

    protected final EntityManager em;

    public ChangeRequestRepository(EntityManager em) {
        this.em = em;
    }

    // ------------------------------------------------------------------
    // Write operations
    // ------------------------------------------------------------------

    /** Persists a new {@link ChangeRequest}. */
    public void save(ChangeRequest changeRequest) {
        em.persist(changeRequest);
    }

    /** Merges (updates) an existing {@link ChangeRequest}. */
    public ChangeRequest update(ChangeRequest changeRequest) {
        return em.merge(changeRequest);
    }

    // ------------------------------------------------------------------
    // Read operations
    // ------------------------------------------------------------------

    /**
     * Finds a {@link ChangeRequest} by its UUID.
     *
     * @param id the UUID string
     * @return an {@link Optional} containing the request, or empty if not found
     */
    public Optional<ChangeRequest> findById(String id) {
        ChangeRequest cr = em.find(ChangeRequest.class, id);
        return Optional.ofNullable(cr);
    }

    /**
     * Returns all pending change requests for a given entity type and ID.
     *
     * @param entityType fully-qualified class name
     * @param entityId   string primary key
     * @return list of non-finalised change requests, ordered oldest-first
     */
    public List<ChangeRequest> findPendingByEntity(String entityType, String entityId) {
        TypedQuery<ChangeRequest> q = em.createQuery(
                "SELECT cr FROM ChangeRequest cr " +
                "WHERE cr.entityType = :type " +
                "  AND cr.entityId   = :id " +
                "  AND cr.status NOT IN (:approved, :rejected) " +
                "ORDER BY cr.requestedAt ASC",
                ChangeRequest.class);
        q.setParameter("type",     entityType);
        q.setParameter("id",       entityId);
        q.setParameter("approved", ApprovalStatus.APPROVED);
        q.setParameter("rejected", ApprovalStatus.REJECTED);
        return q.getResultList();
    }

    /**
     * Returns all change requests for a specific entity (complete audit history).
     *
     * @param entityType fully-qualified class name
     * @param entityId   string primary key
     * @return complete history, newest-first
     */
    public List<ChangeRequest> findHistoryByEntity(String entityType, String entityId) {
        TypedQuery<ChangeRequest> q = em.createQuery(
                "SELECT cr FROM ChangeRequest cr " +
                "WHERE cr.entityType = :type " +
                "  AND cr.entityId   = :id " +
                "ORDER BY cr.requestedAt DESC",
                ChangeRequest.class);
        q.setParameter("type", entityType);
        q.setParameter("id",   entityId);
        return q.getResultList();
    }

    /**
     * Returns all change requests currently awaiting a specific approval level.
     *
     * @param status the pending status to filter on (e.g. PENDING_L1)
     * @return list of pending requests for that level
     */
    public List<ChangeRequest> findByStatus(ApprovalStatus status) {
        TypedQuery<ChangeRequest> q = em.createQuery(
                "SELECT cr FROM ChangeRequest cr " +
                "WHERE cr.status = :status " +
                "ORDER BY cr.requestedAt ASC",
                ChangeRequest.class);
        q.setParameter("status", status);
        return q.getResultList();
    }
}
