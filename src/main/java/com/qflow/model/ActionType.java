package com.qflow.model;

/**
 * Describes the type of action that triggered the approval workflow.
 *
 * @author Thilina Jayamini
 * @since 2026-05-08
 */
public enum ActionType {

    /**
     * A brand-new entity is being created and requires approval before being activated.
     */
    CREATE,

    /**
     * An existing entity is being modified; the change set will contain field-level diffs.
     */
    UPDATE,

    /**
     * An existing entity is being deleted (soft-delete pattern); requires approval.
     */
    DELETE
}
