package com.qflow.api;

import com.qflow.model.ActionType;

/**
 * Strategy interface for <em>dynamic</em> approval-level resolution.
 *
 * <p>When registered with {@link com.qflow.service.ApprovalManager}, the rule
 * engine takes precedence over the static {@link ApprovalWorkflowConfig} for
 * determining how many approval levels a specific change request requires.
 *
 * <p>This enables business-rule-driven approval chains. For example:
 * <ul>
 *   <li>Price changes &gt; $1,000 → require 2 levels (L1 + L2)</li>
 *   <li>Price changes ≤ $1,000 → require only L1</li>
 *   <li>DELETE actions → always require 2 levels regardless of value</li>
 * </ul>
 *
 * <p>Example implementation:
 * <pre>{@code
 * public class ProductRuleEngine implements ApprovalRuleEngine {
 *
 *     @Override
 *     public int resolveRequiredLevels(Approvable entity, ActionType action, Object context) {
 *         if (action == ActionType.DELETE) return 2;
 *         if (entity instanceof Product p) {
 *             return (p.getPrice() != null && p.getPrice().compareTo(new BigDecimal("1000")) > 0)
 *                 ? 2 : 1;
 *         }
 *         return 1;
 *     }
 * }
 * }</pre>
 *
 * <p>Registration:
 * <pre>{@code
 * manager.registerRuleEngine(new ProductRuleEngine());
 * }</pre>
 *
 * @author Thilina Jayamini
 * @since 2026-05-08
 */
public interface ApprovalRuleEngine {

    /**
     * Resolves the number of approval levels required for the given entity and action.
     *
     * <p>Called during {@code submitCreate}, {@code submitUpdate}, and {@code submitDelete}.
     * Return value must be ≥ 1.
     *
     * @param entity  the {@link Approvable} entity being submitted
     * @param action  the type of action (CREATE, UPDATE, DELETE)
     * @param context an optional caller-supplied context object (e.g. transaction amount,
     *                user role); may be {@code null} if no context is provided
     * @return the number of required approval levels (≥ 1)
     */
    int resolveRequiredLevels(Approvable entity, ActionType action, Object context);
}
