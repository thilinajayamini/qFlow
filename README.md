# qFlow Creator-Approver Library

**qFlow** is a Java library that adds robust "Maker-Checker" (Creator-Approver) lifecycle management to any domain object in your application.

It provides field-level change sets, multi-level approval chaining, dynamic rule engines, and JPA-based audit persistence.

---

## Features

- **Standardised Lifecycles**: Supports `CREATE`, `UPDATE`, and `DELETE` workflows.
  - Create/Update: `DRAFT → PENDING_L1 → PENDING_L2 → APPROVED`
  - Delete: `PENDING_DELETE → DELETED`
  - Rejection handling: `REJECTED → RESUBMITTED`
- **Field-Level Diffing**: Using the `@Trackable` annotation, qFlow automatically computes the exact fields that changed and presents them to approvers.
- **Dynamic Rule Engine**: Evaluate business rules at runtime to determine approval levels (e.g., "Transactions > $10,000 require 2 levels, otherwise 1 level").
- **Audit Trails**: Every action, field change, and approver decision is tracked in JPA-backed tables (`ChangeRequest` and `ApprovalDecision`).
- **JPA Persistence**: Plugs seamlessly into Hibernate or any standard JPA provider.

---

## Getting Started

### 1. Dependencies

Make sure you have JPA and Jackson included in your project.

```xml
<dependency>
    <groupId>jakarta.persistence</groupId>
    <artifactId>jakarta.persistence-api</artifactId>
    <version>3.1.0</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.1</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <version>2.17.1</version>
</dependency>
```

### 2. Implement Approvable

Have your domain entity implement the `Approvable` interface and mark fields with `@Trackable`.

```java
import com.qflow.api.Approvable;
import com.qflow.api.Trackable;

public class Product implements Approvable {

    private Long id;

    @Trackable(label = "Product Name")
    private String name;

    @Trackable(label = "Unit Price")
    private BigDecimal price;

    @Override
    public String getEntityId() {
        return id == null ? null : id.toString();
    }
    
    // Getters and Setters...
}
```

### 3. Setup ApprovalManager

Instantiate the `ApprovalManager` with your JPA repositories.

```java
ApprovalManager manager = new ApprovalManager(changeRequestRepo, decisionRepo);

// Optional: Register a static config (e.g., 2 levels of approval for Products)
manager.registerConfig(new ApprovalWorkflowConfig() {
    @Override public Class<?> entityType()   { return Product.class; }
    @Override public int      requiredLevels(){ return 2; }
});
```

---

## Workflow Examples

### CREATE Workflow

```java
// 1. Submit a brand new entity
Product newProduct = new Product(null, "Widget", new BigDecimal("10.00"));
ChangeRequest cr = manager.submitCreate(newProduct, "alice", "New product request");

// 2. Approver signs off
manager.approve(cr.getId(), "bob", "Looks good");
```

### UPDATE Workflow

```java
// 1. Fetch live entity, create proposed state
Product current = ...;
Product proposed = new Product(current.getId(), "Widget", new BigDecimal("15.00"));

// 2. Submit changes (DiffEngine calculates the exact field differences)
ChangeRequest cr = manager.submitUpdate(current, proposed, "alice", "Price bump");

// 3. Level 1 Approver
manager.approve(cr.getId(), "bob", "L1 Approved");

// 4. Level 2 Approver (Changes are applied to liveEntity on final approval)
manager.approve(cr.getId(), "carol", "L2 Approved", current);
```

### Dynamic Rules

Instead of static levels, use an `ApprovalRuleEngine` for complex business logic.

```java
manager.registerRuleEngine((entity, action, context) -> {
    if (action == ActionType.DELETE) return 3; // Deletes require 3 levels
    
    if (entity instanceof Product p && p.getPrice().compareTo(new BigDecimal("1000")) > 0) {
        return 2; // High value products need L1 and L2
    }
    return 1;
});

// Pass a custom context if needed
manager.submitCreate(newProduct, "alice", "Important!", customContextObject);
```

### RESUBMIT Flow (Rejections)

```java
// 1. Request is rejected
manager.reject(cr.getId(), "bob", "Price too high");

// 2. Creator fixes the entity and resubmits
Product fixedProduct = new Product(current.getId(), "Widget", new BigDecimal("12.00"));
ChangeRequest newCr = manager.resubmit(cr.getId(), current, fixedProduct, "alice", "Fixed price");
```

### Audit History

Fetch the entire history for an entity to see all modifications and decisions.

```java
List<ChangeRequest> history = manager.getHistory(Product.class.getName(), "123");

for (ChangeRequest cr : history) {
    System.out.println("Status: " + cr.getStatus() + " | Action: " + cr.getActionType());
    
    // View exactly what changed
    cr.getChangeSet().forEach(fc -> 
        System.out.println(fc.getFieldName() + ": " + fc.getOldValue() + " -> " + fc.getNewValue())
    );
}
```

---

## Testing

qFlow comes with a comprehensive suite of unit tests for the `DiffEngine` and H2/Hibernate integration tests for the `ApprovalManager`.

To run the tests:
```bash
mvn clean test
```
