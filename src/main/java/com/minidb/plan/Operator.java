package com.minidb.plan;

import com.minidb.record.Row;

import java.io.IOException;
import java.util.List;

/**
 * One node in a Volcano-model (iterator) physical plan.
 *
 * Contract:
 * - open() prepares the operator and its children. Called exactly once, before next().
 * - next() returns the next row, or null once exhausted. Never called after it returns null.
 * - close() releases resources (notably buffer pool pins). It MUST be idempotent and
 *   MUST be safe after a partial scan — LIMIT abandons its child mid-stream, so close()
 *   on a half-consumed operator is the normal case, not an error path.
 *
 * Rows are pulled one at a time rather than materialized into a List at each step, so a
 * LIMIT can stop the scan early instead of every stage running to exhaustion.
 */
public interface Operator {

    void open() throws IOException;

    /** Returns the next row, or null when exhausted. */
    Row next() throws IOException;

    void close() throws IOException;

    /** Children, left to right. Leaves return an empty list. Used by EXPLAIN. */
    List<Operator> children();

    /** Node label for EXPLAIN, e.g. "SeqScan" or "Filter(age > 60)". */
    String describe();

    /**
     * Toy cost estimate, purely so EXPLAIN prints a number. There are no table
     * statistics yet, so this ranks plans rather than predicting runtime.
     */
    double estimatedCost();
}
