package com.qflow;

import com.qflow.api.ApprovalWorkflowConfig;
import com.qflow.model.ActionType;
import com.qflow.model.ApprovalDecision;
import com.qflow.model.ApprovalStatus;
import com.qflow.model.ChangeRequest;
import com.qflow.repository.ApprovalDecisionRepository;
import com.qflow.repository.ChangeRequestRepository;
import com.qflow.service.ApprovalManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ApprovalManager} using an in-memory H2
 * database wired through Hibernate (JPA persistence unit "qflow-test").
 *
 * <p>Each test runs in its own transaction which is rolled back afterwards,
 * keeping tests fully isolated.
 *
 * @author Thilina Jayamini
 * @since 2026-05-08
 */
@DisplayName("ApprovalManager Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApprovalManagerTest {

    private static EntityManagerFactory emf;
    private EntityManager em;
    private ApprovalManager manager;

    @BeforeAll
    static void initFactory() {
        emf = Persistence.createEntityManagerFactory("qflow-test");
    }

    @AfterAll
    static void closeFactory() {
        if (emf != null) emf.close();
    }

    @BeforeEach
    void setUp() {
        em = emf.createEntityManager();
        ChangeRequestRepository   crRepo  = new ChangeRequestRepository(em);
        ApprovalDecisionRepository adRepo = new ApprovalDecisionRepository(em);
        manager = new ApprovalManager(crRepo, adRepo);
    }

    @AfterEach
    void tearDown() {
        if (em.getTransaction().isActive()) {
            em.getTransaction().rollback();
        }
        em.close();
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private void registerTwoLevelConfig() {
        manager.registerConfig(new ApprovalWorkflowConfig() {
            @Override public Class<?> entityType()   { return Product.class; }
            @Override public int      requiredLevels(){ return 2; }
        });
    }

    // ------------------------------------------------------------------
    // CREATE workflow — single-level (default)
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("CREATE: request is persisted with PENDING_L1 status")
    void submitCreate_persistsWithPendingL1() {
        em.getTransaction().begin();

        Product newProduct = new Product(null, "Widget", new BigDecimal("9.99"), 100);
        ChangeRequest cr = manager.submitCreate(newProduct, "alice", "New product");

        em.flush();
        em.getTransaction().rollback();

        assertNotNull(cr.getId());
        assertEquals(ApprovalStatus.PENDING_L1, cr.getStatus());
        assertEquals(ActionType.CREATE,         cr.getActionType());
        assertEquals(1, cr.getRequiredLevels());
        assertEquals("alice", cr.getRequestedBy());
        assertNotNull(cr.getProposedSnapshot());
    }

    @Test
    @Order(2)
    @DisplayName("CREATE → APPROVE (1-level): status becomes APPROVED")
    void submitCreate_thenApprove_singleLevel_becomesApproved() {
        em.getTransaction().begin();

        Product newProduct = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        ChangeRequest cr = manager.submitCreate(newProduct, "alice", "New product");
        em.flush();

        ChangeRequest approved = manager.approve(cr.getId(), "bob", "Looks good");
        em.flush();
        em.getTransaction().rollback();

        assertEquals(ApprovalStatus.APPROVED, approved.getStatus());
        assertEquals("bob", approved.getResolvedBy());
        assertNotNull(approved.getResolvedAt());
    }

    // ------------------------------------------------------------------
    // UPDATE workflow — two-level approval
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("UPDATE: change set captures only @Trackable fields")
    void submitUpdate_capturesTrackedFields() {
        registerTwoLevelConfig();
        em.getTransaction().begin();

        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"),  100);
        Product proposed = new Product(1L, "Widget", new BigDecimal("14.99"), 100);
        proposed.setInternalCode("IGNORED"); // not tracked

        ChangeRequest cr = manager.submitUpdate(current, proposed, "alice", "Price update");
        em.flush();
        em.getTransaction().rollback();

        assertNotNull(cr);
        assertEquals(1, cr.getChangeSet().size(), "Only Unit Price changed");
        assertEquals("Unit Price", cr.getChangeSet().get(0).getFieldName());
        assertEquals(2, cr.getRequiredLevels());
    }

    @Test
    @Order(4)
    @DisplayName("UPDATE → L1 APPROVE → PENDING_L2 (2-level workflow)")
    void submitUpdate_approveL1_becomesPendingL2() {
        registerTwoLevelConfig();
        em.getTransaction().begin();

        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        Product proposed = new Product(1L, "Widget", new BigDecimal("19.99"), 100);
        ChangeRequest cr = manager.submitUpdate(current, proposed, "alice", "Price bump");
        em.flush();

        ChangeRequest afterL1 = manager.approve(cr.getId(), "bob", "L1 ok");
        em.flush();
        em.getTransaction().rollback();

        assertEquals(ApprovalStatus.PENDING_L2, afterL1.getStatus());
        assertEquals(2, afterL1.getCurrentLevel());
    }

    @Test
    @Order(5)
    @DisplayName("UPDATE → L1 → L2 APPROVE: changes applied to live entity")
    void submitUpdate_approveL1ThenL2_appliesChangesToLiveEntity() {
        registerTwoLevelConfig();
        em.getTransaction().begin();

        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        Product proposed = new Product(1L, "Widget", new BigDecimal("29.99"), 100);
        ChangeRequest cr = manager.submitUpdate(current, proposed, "alice", "Big price jump");
        em.flush();

        manager.approve(cr.getId(), "bob",   "L1 approved");
        em.flush();

        Product liveEntity = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        ChangeRequest finalCr = manager.approve(cr.getId(), "carol", "L2 approved", liveEntity);
        em.flush();
        em.getTransaction().rollback();

        assertEquals(ApprovalStatus.APPROVED, finalCr.getStatus());
        // Changes should have been applied to liveEntity
        assertEquals(new BigDecimal("29.99"), liveEntity.getPrice(),
                "Live entity price should be updated after final approval");
    }

    // ------------------------------------------------------------------
    // REJECT workflow
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("REJECT: status becomes REJECTED, reason persisted")
    void submitUpdate_thenReject_setsRejectedStatus() {
        em.getTransaction().begin();

        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        Product proposed = new Product(1L, "Widget", new BigDecimal("99.99"), 100);
        ChangeRequest cr = manager.submitUpdate(current, proposed, "alice", "Massive price hike");
        em.flush();

        ChangeRequest rejected = manager.reject(cr.getId(), "bob", "Price too high");
        em.flush();
        em.getTransaction().rollback();

        assertEquals(ApprovalStatus.REJECTED, rejected.getStatus());
        assertEquals("Price too high", rejected.getRejectionReason());
        assertEquals("bob", rejected.getResolvedBy());
    }

    @Test
    @Order(7)
    @DisplayName("Reject already-finalised: throws IllegalStateException")
    void approve_finalisedRequest_throwsException() {
        em.getTransaction().begin();

        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        Product proposed = new Product(1L, "Widget", new BigDecimal("14.99"), 100);
        ChangeRequest cr = manager.submitUpdate(current, proposed, "alice", "test");
        em.flush();

        manager.reject(cr.getId(), "bob", "No thanks");
        em.flush();

        assertThrows(IllegalStateException.class,
                () -> manager.approve(cr.getId(), "carol", "Late approval"));

        em.getTransaction().rollback();
    }

    // ------------------------------------------------------------------
    // No changes
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("submitUpdate with no tracked changes: returns null")
    void submitUpdate_noTrackedChanges_returnsNull() {
        em.getTransaction().begin();

        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        Product proposed = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        proposed.setInternalCode("CHANGED"); // not tracked

        ChangeRequest cr = manager.submitUpdate(current, proposed, "alice", "No-op");
        em.getTransaction().rollback();

        assertNull(cr, "No ChangeRequest should be created when nothing tracked changed");
    }

    // ------------------------------------------------------------------
    // Audit trail
    // ------------------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("Audit trail: two approval decisions recorded for 2-level workflow")
    void approvalDecisions_twoLevelWorkflow_twoDecisionsRecorded() {
        registerTwoLevelConfig();
        em.getTransaction().begin();

        Product current  = new Product(1L, "Widget", new BigDecimal("1.00"), 5);
        Product proposed = new Product(1L, "Widget", new BigDecimal("2.00"), 5);
        ChangeRequest cr = manager.submitUpdate(current, proposed, "alice", "Price up");
        em.flush();

        manager.approve(cr.getId(), "bob",   "L1 ok");   em.flush();
        manager.approve(cr.getId(), "carol", "L2 ok");   em.flush();

        List<ApprovalDecision> decisions = manager.getDecisions(cr.getId());
        em.getTransaction().rollback();

        assertEquals(2, decisions.size());
        assertEquals("bob",   decisions.get(0).getApproverId());
        assertEquals("carol", decisions.get(1).getApproverId());
        assertEquals(ApprovalDecision.Decision.APPROVED, decisions.get(0).getDecision());
        assertEquals(ApprovalDecision.Decision.APPROVED, decisions.get(1).getDecision());
    }

    @Test
    @Order(10)
    @DisplayName("History: getHistory returns all change requests for an entity")
    void getHistory_returnsAllRequests() {
        em.getTransaction().begin();

        Product p1c = new Product(5L, "Alpha", BigDecimal.ONE, 1);
        Product p1p = new Product(5L, "Beta",  BigDecimal.TEN, 2);

        manager.submitUpdate(p1c, p1p, "alice", "first update");  em.flush();

        Product p2c = new Product(5L, "Beta",  BigDecimal.TEN, 2);
        Product p2p = new Product(5L, "Gamma", BigDecimal.TEN, 3);
        manager.submitUpdate(p2c, p2p, "alice", "second update"); em.flush();

        List<ChangeRequest> history = manager.getHistory(Product.class.getName(), "5");
        em.getTransaction().rollback();

        assertEquals(2, history.size());
    }
    // ------------------------------------------------------------------
    // Resubmit workflow
    // ------------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("RESUBMIT: rejected request can be resubmitted, linked to parent")
    void resubmit_rejectedRequest_createsNewRequestLinkedToOld() {
        em.getTransaction().begin();

        Product current  = new Product(1L, "Widget", new BigDecimal("9.99"), 100);
        Product proposed = new Product(1L, "Widget", new BigDecimal("99.99"), 100);
        ChangeRequest originalCr = manager.submitUpdate(current, proposed, "alice", "Massive price hike");
        em.flush();

        manager.reject(originalCr.getId(), "bob", "Price too high");
        em.flush();

        Product fixedProposed = new Product(1L, "Widget", new BigDecimal("14.99"), 100);
        ChangeRequest newCr = manager.resubmit(originalCr.getId(), current, fixedProposed, "alice", "Fixed price");
        em.flush();

        // Need to reload original to see status update
        ChangeRequest reloadedOriginal = em.find(ChangeRequest.class, originalCr.getId());
        em.getTransaction().rollback();

        assertNotNull(newCr);
        assertEquals(ApprovalStatus.PENDING_L1, newCr.getStatus());
        assertEquals(originalCr.getId(), newCr.getParentRequestId());
        assertEquals(ApprovalStatus.RESUBMITTED, reloadedOriginal.getStatus());
        assertEquals(1, newCr.getChangeSet().size());
        assertTrue(newCr.getChangeSet().get(0).getNewValue().contains("14.99"));
    }

    // ------------------------------------------------------------------
    // DELETE workflow
    // ------------------------------------------------------------------

    @Test
    @Order(12)
    @DisplayName("DELETE: submitDelete sets PENDING_DELETE and approve sets DELETED")
    void submitDelete_approve_setsDeletedStatus() {
        em.getTransaction().begin();

        Product toDelete = new Product(1L, "Obsolete", new BigDecimal("0.99"), 0);
        ChangeRequest cr = manager.submitDelete(toDelete, "alice", "Clean up");
        em.flush();

        assertEquals(ApprovalStatus.PENDING_DELETE, cr.getStatus());
        assertEquals(ActionType.DELETE, cr.getActionType());

        ChangeRequest approved = manager.approve(cr.getId(), "bob", "Approved delete");
        em.flush();
        em.getTransaction().rollback();

        assertEquals(ApprovalStatus.DELETED, approved.getStatus());
    }

    // ------------------------------------------------------------------
    // Rule Engine workflow
    // ------------------------------------------------------------------

    @Test
    @Order(13)
    @DisplayName("RuleEngine: dynamically resolves required levels based on context")
    void ruleEngine_overridesStaticConfig() {
        manager.registerRuleEngine((entity, action, context) -> {
            if (context instanceof Boolean b && b) {
                return 3; // 3 levels if high priority context
            }
            return 1;
        });

        em.getTransaction().begin();

        Product newProduct = new Product(null, "Important Widget", new BigDecimal("999.99"), 10);
        
        // Submit with high priority context
        ChangeRequest cr = manager.submitCreate(newProduct, "alice", "Urgent", true);
        em.flush();
        
        em.getTransaction().rollback();

        assertEquals(3, cr.getRequiredLevels());
        assertEquals(ApprovalStatus.PENDING_L1, cr.getStatus());
        
        // Remove rule engine for other tests just in case, though manager is recreated per test
        manager.registerRuleEngine(null); 
    }
}
