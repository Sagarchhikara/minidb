package com.minidb.plan;

import com.minidb.record.Row;

import java.io.IOException;
import java.util.List;

/**
 * Stops after n rows.
 *
 * This is what makes early termination observable: once the counter is spent, next()
 * returns null without pulling from the child again, leaving the child mid-scan and
 * still holding a page pin. The subsequent close() is what has to release it.
 */
public class Limit implements Operator {

    private final Operator child;
    private final int limit;

    private int produced;

    public Limit(Operator child, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        this.child = child;
        this.limit = limit;
    }

    @Override
    public void open() throws IOException {
        produced = 0;
        child.open();
    }

    @Override
    public Row next() throws IOException {
        if (produced >= limit) {
            return null; // deliberately does not touch the child
        }
        Row row = child.next();
        if (row == null) {
            return null;
        }
        produced++;
        return row;
    }

    @Override
    public void close() throws IOException {
        child.close();
    }

    @Override
    public List<Operator> children() {
        return List.of(child);
    }

    @Override
    public String describe() {
        return "Limit(" + limit + ")";
    }

    /** Toy cost: capped by the child, but never cheaper than touching one page. */
    @Override
    public double estimatedCost() {
        return Math.min(child.estimatedCost(), Math.max(1, limit));
    }
}
