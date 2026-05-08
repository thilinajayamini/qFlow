package com.qflow.service;

import com.qflow.api.Approvable;
import com.qflow.api.ApprovalRuleEngine;
import com.qflow.api.ApprovalWorkflowConfig;
import com.qflow.engine.DiffEngine;
import com.qflow.engine.EntityApplier;
import com.qflow.model.*;
import com.qflow.repository.ApprovalDecisionRepository;
import com.qflow.repository.ChangeRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central service for the qFlow Creator-Approver workflow.
 *
 * <p>This class orchestrates the full lifecycle of a change request:
 * <ol>
 *   <li><strong>Submit</strong> — creator submits a new or modified entity.</li>
 *   <li><strong>Approve</strong> — approver advances through required levels.</li>
 *   <li><strong>Reject</strong>  — approver rejects at any level.</li>
 *   <li><strong>Apply</strong>   — on final approval, changes are written back to the live entity.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ApprovalManager manager = new ApprovalManager(changeRequestRepo, decisionRepo);
 * manager.registerConfig(new ProductWorkflowConfig()); // 2-level approval
 *
 * // --- Creator ---
 * ChangeRequest cr = manager.submitCreate(newProduct, "alice", "New product request");
 *
 * // --- L1 Approver ---
 * manager.approve(cr.getId(), "bob", "Looks good");
 *
 * // --- L2 Approver ---
 * manager.approve(cr.getId(), "carol", "Approved", liveProduct); // changes applied here
 * }</pre>
 *
 * <p><strong>Transaction management</strong> is the caller's responsibility.
 * Wrap all calls in appropriate JPA transactions.
 */
public class ApprovalManager {

    private static final Logger log = LoggerFactory.getLogger(ApprovalManager.class);

    private final ChangeRequestRepository   changeRequestRepo;
    private final ApprovalDecisionRepository decisionRepo;
    private final DiffEngine                diffEngine;
    private final EntityApplier             entityApplier;

    /** Entity-type → workflow config registry. */
    private final Map<Class<?>, ApprovalWorkflowConfig> configRegistry = new HashMap<>();

    /** Rule Engine. Takes precedence over configRegistry if present. */
    private ApprovalRuleEngine ruleEngine;

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    public ApprovalManager(ChangeRequestRepository changeRequestRepo,
                           ApprovalDecisionRepository decisionRepo) {
        this.changeRequestRepo = changeRequestRepo;
        this.decisionRepo      = decisionRepo;
        this.diffEngine        = new DiffEngine();
        this.entityApplier     = new EntityApplier();
    }

    /** Constructor that accepts custom engine instances (useful for testing). */
    public ApprovalManager(ChangeRequestRepository changeRequestRepo,
                           ApprovalDecisionRepository decisionRepo,
                           DiffEngine diffEngine,
                           EntityApplier entityApplier) {
        this.changeRequestRepo = changeRequestRepo;
        this.decisionRepo      = decisionRepo;
        this.diffEngine        = diffEngine;
        this.entityApplier     = entityApplier;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Registers an {@link ApprovalWorkflowConfig} for a specific entity type.
     * Can be called multiple times to override or add configurations.
     *
     * @param config the workflow config to register
     */
    public void registerConfig(ApprovalWorkflowConfig config) {
        configRegistry.put(config.entityType(), config);
        log.info("Registered workflow config for [{}]: {} levels",
                config.entityType().getSimpleName(), config.requiredLevels());
    }

    /**
     * Registers an {@link ApprovalRuleEngine} to dynamically resolve approval levels.
     * Takes precedence over static configuration.
     *
     * @param engine the rule engine to register
     */
    public void registerRuleEngine(ApprovalRuleEngine engine) {
        this.ruleEngine = engine;
        log.info("Registered ApprovalRuleEngine");
    }

    // ------------------------------------------------------------------
    // Submission API
    // ------------------------------------------------------------------

    /**
     * Submits a brand-new entity for approval (CREATE workflow).
     *
     * <p>The entity's full state is captured as a JSON snapshot. Once approved,
     * the snapshot is deserialised back to the caller's entity.
     *
     * @param entity      the new entity to be created upon approval
     * @param requestedBy identifier of the creator
     * @param comments    optional notes
     * @param <T>         entity type implementing {@link Approvable}
     * @return the persisted {@link ChangeRequest}
     */
    public <T extends Approvable> ChangeRequest submitCreate(T entity,
                                                              String requestedBy,
                                                              String comments) {
        return submitCreate(entity, requestedBy, comments, null);
    }

    /**
     * Submits a brand-new entity for approval (CREATE workflow) with context.
     *
     * @param entity      the new entity to be created upon approval
     * @param requestedBy identifier of the creator
     * @param comments    optional notes
     * @param context     optional context for rule engine
     * @param <T>         entity type implementing {@link Approvable}
     * @return the persisted {@link ChangeRequest}
     */
    public <T extends Approvable> ChangeRequest submitCreate(T entity,
                                                              String requestedBy,
                                                              String comments,
                                                              Object context) {
        int levels = resolvedLevels(entity, ActionType.CREATE, context);
        ChangeRequest cr = ChangeRequest.create(
                entity.getClass().getName(),
                entity.getEntityId(),
                ActionType.CREATE,
                levels,
                requestedBy,
                comments);

        String snapshot = diffEngine.toSnapshot(entity);
        cr.setProposedSnapshot(snapshot);

        // For CREATE, changeSet shows all @Trackable fields with oldValue=null
        List<FieldChange> changeSet = diffEngine.diff(null, entity);
        cr.setChangeSet(changeSet);

        changeRequestRepo.save(cr);
        log.info("CREATE request [{}] submitted for [{}#{}] by [{}]",
                cr.getId(), entity.getClass().getSimpleName(), entity.getEntityId(), requestedBy);
        return cr;
    }

    /**
     * Submits a modification to an existing entity for approval (UPDATE workflow).
     *
     * <p>The diff between {@code current} and {@code proposed} is computed and
     * stored as the change set. Approvers can inspect the field-level changes.
     *
     * @param current     the live entity (before changes)
     * @param proposed    the modified entity (desired state)
     * @param requestedBy identifier of the creator
     * @param comments    optional notes
     * @param <T>         entity type implementing {@link Approvable}
     * @return the persisted {@link ChangeRequest}, or {@code null} if no tracked fields changed
     */
    public <T extends Approvable> ChangeRequest submitUpdate(T current,
                                                              T proposed,
                                                              String requestedBy,
                                                              String comments) {
        return submitUpdate(current, proposed, requestedBy, comments, null);
    }

    /**
     * Submits a modification to an existing entity for approval (UPDATE workflow) with context.
     *
     * @param current     the live entity (before changes)
     * @param proposed    the modified entity (desired state)
     * @param requestedBy identifier of the creator
     * @param comments    optional notes
     * @param context     optional context for rule engine
     * @param <T>         entity type implementing {@link Approvable}
     * @return the persisted {@link ChangeRequest}, or {@code null} if no tracked fields changed
     */
    public <T extends Approvable> ChangeRequest submitUpdate(T current,
                                                              T proposed,
                                                              String requestedBy,
                                                              String comments,
                                                              Object context) {
        List<FieldChange> changeSet = diffEngine.diff(current, proposed);
        if (changeSet.isEmpty()) {
            log.info("No tracked field changes detected for [{}#{}] — no request created",
                    current.getClass().getSimpleName(), current.getEntityId());
            return null;
        }

        int levels = resolvedLevels(current, ActionType.UPDATE, context);
        ChangeRequest cr = ChangeRequest.create(
                current.getClass().getName(),
                current.getEntityId(),
                ActionType.UPDATE,
                levels,
                requestedBy,
                comments);

        cr.setProposedSnapshot(diffEngine.toSnapshot(proposed));
        cr.setChangeSet(changeSet);

        changeRequestRepo.save(cr);
        log.info("UPDATE request [{}] submitted for [{}#{}] by [{}] — {} field(s) changed",
                cr.getId(), current.getClass().getSimpleName(), current.getEntityId(),
                requestedBy, changeSet.size());
        return cr;
    }

    /**
     * Submits a delete request for an existing entity (DELETE workflow).
     *
     * @param entity      the entity to be deleted upon approval
     * @param requestedBy identifier of the creator
     * @param comments    optional notes
     * @param <T>         entity type implementing {@link Approvable}
     * @return the persisted {@link ChangeRequest}
     */
    public <T extends Approvable> ChangeRequest submitDelete(T entity,
                                                              String requestedBy,
                                                              String comments) {
        return submitDelete(entity, requestedBy, comments, null);
    }

    /**
     * Submits a delete request for an existing entity (DELETE workflow) with context.
     *
     * @param entity      the entity to be deleted upon approval
     * @param requestedBy identifier of the creator
     * @param comments    optional notes
     * @param context     optional context for rule engine
     * @param <T>         entity type implementing {@link Approvable}
     * @return the persisted {@link ChangeRequest}
     */
    public <T extends Approvable> ChangeRequest submitDelete(T entity,
                                                              String requestedBy,
                                                              String comments,
                                                              Object context) {
        int levels = resolvedLevels(entity, ActionType.DELETE, context);
        ChangeRequest cr = ChangeRequest.create(
                entity.getClass().getName(),
                entity.getEntityId(),
                ActionType.DELETE,
                levels,
                requestedBy,
                comments);

        cr.setProposedSnapshot(diffEngine.toSnapshot(entity));
        cr.overrideStatus(ApprovalStatus.PENDING_DELETE); // Set specific status for DELETE

        changeRequestRepo.save(cr);
        log.info("DELETE request [{}] submitted for [{}#{}] by [{}]",
                cr.getId(), entity.getClass().getSimpleName(), entity.getEntityId(), requestedBy);
        return cr;
    }

    /**
     * Resubmits a previously rejected change request.
     *
     * @param rejectedRequestId UUID of the original REJECTED change request
     * @param currentEntity     the live entity (before changes, for UPDATEs)
     * @param updatedEntity     the modified entity with the creator's fixes
     * @param requestedBy       identifier of the creator resubmitting
     * @param comments          optional notes
     * @param <T>               entity type
     * @return the new {@link ChangeRequest} linked to the rejected one
     */
    public <T extends Approvable> ChangeRequest resubmit(String rejectedRequestId,
                                                         T currentEntity,
                                                         T updatedEntity,
                                                         String requestedBy,
                                                         String comments) {
        return resubmit(rejectedRequestId, currentEntity, updatedEntity, requestedBy, comments, null);
    }

    /**
     * Resubmits a previously rejected change request with context.
     *
     * @param rejectedRequestId UUID of the original REJECTED change request
     * @param currentEntity     the live entity (before changes, for UPDATEs)
     * @param updatedEntity     the modified entity with the creator's fixes
     * @param requestedBy       identifier of the creator resubmitting
     * @param comments          optional notes
     * @param context           optional context for rule engine
     * @param <T>               entity type
     * @return the new {@link ChangeRequest} linked to the rejected one
     */
    public <T extends Approvable> ChangeRequest resubmit(String rejectedRequestId,
                                                         T currentEntity,
                                                         T updatedEntity,
                                                         String requestedBy,
                                                         String comments,
                                                         Object context) {
        Optional<ChangeRequest> opt = changeRequestRepo.findById(rejectedRequestId);
        ChangeRequest rejected = opt.orElseThrow(() ->
                new IllegalArgumentException("ChangeRequest not found: " + rejectedRequestId));

        if (rejected.getStatus() != ApprovalStatus.REJECTED) {
            throw new IllegalStateException("Only REJECTED requests can be resubmitted");
        }

        ChangeRequest newCr;
        if (rejected.getActionType() == ActionType.CREATE) {
            newCr = submitCreate(updatedEntity, requestedBy, comments, context);
        } else if (rejected.getActionType() == ActionType.UPDATE) {
            newCr = submitUpdate(currentEntity, updatedEntity, requestedBy, comments, context);
            if (newCr == null) {
                return null; // no changes
            }
        } else {
            newCr = submitDelete(updatedEntity, requestedBy, comments, context);
        }

        newCr.setParentRequestId(rejected.getId());
        changeRequestRepo.update(newCr);

        // Update old request status to RESUBMITTED to show it's no longer the terminal state
        rejected.overrideStatus(ApprovalStatus.RESUBMITTED);
        changeRequestRepo.update(rejected);

        log.info("Request [{}] resubmitted as [{}] by [{}]", rejectedRequestId, newCr.getId(), requestedBy);
        return newCr;
    }

    // ------------------------------------------------------------------
    // Approval / Rejection API
    // ------------------------------------------------------------------

    /**
     * Records an approval decision for the given change request.
     *
     * <p>If this is the final required approval level the request is marked
     * {@link ApprovalStatus#APPROVED}. For UPDATE/CREATE requests, the changes
     * are applied to {@code liveEntity} if provided.
     *
     * @param changeRequestId UUID of the {@link ChangeRequest}
     * @param approverId      identifier of the approving user
     * @param remarks         optional comments from the approver
     * @param liveEntity      the live entity to apply changes to on final approval
     *                        (may be {@code null} for DELETE, or if the caller handles application)
     * @return the updated {@link ChangeRequest}
     * @throws IllegalArgumentException if the request does not exist or is already finalised
     */
    public ChangeRequest approve(String changeRequestId,
                                  String approverId,
                                  String remarks,
                                  Object liveEntity) {
        ChangeRequest cr = requirePending(changeRequestId);

        // Record the decision
        ApprovalDecision decision = ApprovalDecision.of(
                cr,
                cr.getCurrentLevel(),
                approverId,
                ApprovalDecision.Decision.APPROVED,
                remarks);
        decisionRepo.save(decision);

        cr.advanceLevel();
        cr.setResolvedBy(approverId);

        if (cr.getStatus() == ApprovalStatus.APPROVED && liveEntity != null) {
            applyApprovedChanges(cr, liveEntity);
        }

        changeRequestRepo.update(cr);
        log.info("Request [{}] approved by [{}] — new status: {}", changeRequestId, approverId, cr.getStatus());
        return cr;
    }

    /**
     * Convenience overload of {@link #approve} when no live entity is supplied.
     */
    public ChangeRequest approve(String changeRequestId, String approverId, String remarks) {
        return approve(changeRequestId, approverId, remarks, null);
    }

    /**
     * Rejects the change request at the current level.
     *
     * <p>The request is immediately marked {@link ApprovalStatus#REJECTED};
     * no changes are applied to any entity.
     *
     * @param changeRequestId UUID of the {@link ChangeRequest}
     * @param approverId      identifier of the rejecting user
     * @param reason          mandatory reason for rejection
     * @return the updated (rejected) {@link ChangeRequest}
     */
    public ChangeRequest reject(String changeRequestId, String approverId, String reason) {
        ChangeRequest cr = requirePending(changeRequestId);

        ApprovalDecision decision = ApprovalDecision.of(
                cr,
                cr.getCurrentLevel(),
                approverId,
                ApprovalDecision.Decision.REJECTED,
                reason);
        decisionRepo.save(decision);

        cr.reject(approverId, reason);
        changeRequestRepo.update(cr);
        log.info("Request [{}] rejected by [{}] — reason: {}", changeRequestId, approverId, reason);
        return cr;
    }

    // ------------------------------------------------------------------
    // Query helpers
    // ------------------------------------------------------------------

    /**
     * Returns all pending change requests for a given entity at the specified level.
     *
     * @param entityType  fully-qualified class name
     * @param entityId    string primary key
     * @return list of non-finalised change requests
     */
    public List<ChangeRequest> getPendingRequests(String entityType, String entityId) {
        return changeRequestRepo.findPendingByEntity(entityType, entityId);
    }

    /**
     * Returns the full audit history for an entity.
     *
     * @param entityType  fully-qualified class name
     * @param entityId    string primary key
     * @return all change requests (approved, rejected, pending), newest first
     */
    public List<ChangeRequest> getHistory(String entityType, String entityId) {
        return changeRequestRepo.findHistoryByEntity(entityType, entityId);
    }

    /**
     * Returns all decisions associated with a single change request.
     *
     * @param changeRequestId UUID of the change request
     * @return ordered list of {@link ApprovalDecision} records
     */
    public List<ApprovalDecision> getDecisions(String changeRequestId) {
        return decisionRepo.findByChangeRequest(changeRequestId);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private void applyApprovedChanges(ChangeRequest cr, Object liveEntity) {
        if (cr.getActionType() == ActionType.UPDATE) {
            entityApplier.applyChangeSet(liveEntity, cr.getChangeSet());
            log.info("Applied {} field change(s) to [{}#{}]",
                    cr.getChangeSet().size(), cr.getEntityType(), cr.getEntityId());
        } else if (cr.getActionType() == ActionType.CREATE) {
            entityApplier.applySnapshot(liveEntity, cr.getProposedSnapshot());
            log.info("Applied CREATE snapshot to [{}#{}]", cr.getEntityType(), cr.getEntityId());
        }
        // DELETE: caller is responsible for physically removing the entity
    }

    private ChangeRequest requirePending(String changeRequestId) {
        Optional<ChangeRequest> opt = changeRequestRepo.findById(changeRequestId);
        ChangeRequest cr = opt.orElseThrow(() ->
                new IllegalArgumentException("ChangeRequest not found: " + changeRequestId));
        if (cr.isFinalised()) {
            throw new IllegalStateException(
                    "ChangeRequest [" + changeRequestId + "] is already finalised: " + cr.getStatus());
        }
        return cr;
    }

    private int resolvedLevels(Approvable entity, ActionType actionType, Object context) {
        if (ruleEngine != null) {
            return ruleEngine.resolveRequiredLevels(entity, actionType, context);
        }
        ApprovalWorkflowConfig config = configRegistry.get(entity.getClass());
        return (config == null) ? 1 : config.requiredLevels();
    }
}
