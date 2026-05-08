package com.qflow;

import com.qflow.api.Approvable;
import com.qflow.api.Trackable;

import java.math.BigDecimal;

/**
 * Sample domain entity used across unit and integration tests.
 *
 * <p>This class intentionally lives in the {@code test} source tree to
 * demonstrate how a library consumer would implement {@link Approvable}.
 */
public class Product implements Approvable {

    private Long id;

    @Trackable(label = "Product Name")
    private String name;

    @Trackable(label = "Unit Price")
    private BigDecimal price;

    @Trackable(label = "Stock Quantity")
    private int stock;

    /** Not tracked — changes here will not appear in any change set. */
    private String internalCode;

    public Product() {}

    public Product(Long id, String name, BigDecimal price, int stock) {
        this.id    = id;
        this.name  = name;
        this.price = price;
        this.stock = stock;
    }

    @Override
    public String getEntityId() {
        return id == null ? null : id.toString();
    }

    // ------------------------------------------------------------------
    // Getters / Setters (needed by Jackson for snapshot)
    // ------------------------------------------------------------------

    public Long getId()                   { return id; }
    public void setId(Long id)            { this.id = id; }

    public String getName()               { return name; }
    public void setName(String name)      { this.name = name; }

    public BigDecimal getPrice()          { return price; }
    public void setPrice(BigDecimal price){ this.price = price; }

    public int getStock()                 { return stock; }
    public void setStock(int stock)       { this.stock = stock; }

    public String getInternalCode()               { return internalCode; }
    public void setInternalCode(String code)      { this.internalCode = code; }
}
