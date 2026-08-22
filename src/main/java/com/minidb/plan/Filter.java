package com.minidb.plan;

import com.minidb.record.Row;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

/** Passes through only the rows its child produces that satisfy the predicate. */
public class Filter implements Operator {

    private final Operator child;
    private final Predicate<Row> predicate;
    private final String label;

    public Filter(Operator child, Predicate<Row> predicate, String label) {
        this.child = child;
        this.predicate = predicate;
        this.label = label;
    }

    @Override
    public void open() throws IOException {
        child.open();
    }

    @Override
    public Row next() throws IOException {
        Row row;
        while ((row = child.next()) != null) {
            if (predicate.test(row)) {
                return row;
            }
        }
        return null;
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
        return "Filter(" + label + ")";
    }

    /** Toy cost: the child's work; the predicate itself is free at this granularity. */
    @Override
    public double estimatedCost() {
        return child.estimatedCost();
    }
}
