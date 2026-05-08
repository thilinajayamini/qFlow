package com.qflow.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field on an {@link Approvable} entity for inclusion in the
 * change-set diff computed by the {@link com.qflow.engine.DiffEngine}.
 *
 * <p>Only fields annotated with {@code @Trackable} are compared between the
 * current and proposed entity states. Fields without this annotation are
 * silently ignored during diffing.
 *
 * <p>Usage example:
 * <pre>{@code
 * public class Product implements Approvable {
 *
 *     @Trackable
 *     private String name;
 *
 *     @Trackable(label = "Unit Price")
 *     private BigDecimal price;
 *
 *     private String internalCode; // not tracked
 * }
 * }</pre>
 *
 * @author Thilina Jayamini
 * @since 2026-05-08
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Trackable {

    /**
     * Optional human-readable label for this field, shown in the change-set
     * view presented to approvers. Defaults to the Java field name.
     */
    String label() default "";
}
