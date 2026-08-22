package com.minidb.bench;

import com.minidb.plan.IndexSeek;
import com.minidb.plan.Operator;
import com.minidb.plan.Planner;
import com.minidb.plan.SeqScan;
import com.minidb.record.Row;
import com.minidb.sql.Executor;
import com.minidb.sql.Parser;
import com.minidb.sql.SelectStatement;
import com.minidb.storage.BufferPool;
import com.minidb.table.Table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measurement core: warmup, batching, percentile reporting, and the guards that stop a
 * broken setup from being published as a null result.
 *
 * Not JMH. JMH is the right tool for JVM microbenchmarking — it handles dead-code
 * elimination, constant folding, and fork isolation that a hand-rolled loop does not.
 * It is skipped here because adopting it means adopting Maven, and the project has no
 * build tool by design. The mitigations that matter are applied explicitly: results are
 * accumulated into a sink so the query cannot be optimized away, warmup iterations are
 * discarded, and medians are reported rather than means.
 */
public final class Harness {

    /** Discarded iterations, to let JIT compile the hot path before anything is recorded. */
    public static final int WARMUP = 20;
    /** Recorded measurements. Each yields one latency sample. */
    public static final int MEASURE = 25;

    /** Guards against dead-code elimination: every result row feeds this. */
    private static long sink;

    private Harness() {
    }

    /**
     * Pool state between iterations. Every chart must be labeled with one of these —
     * they measure genuinely different things.
     */
    public enum PoolRegime {
        /** Pool state carries across iterations. Steady state; best case for the index. */
        WARM,
        /** Pool cleared before each iteration. The only regime where buffer size matters. */
        COLD
    }

    public record Result(
            int n,
            String mode,
            PoolRegime regime,
            int poolFrames,
            int rowsReturned,
            int pagesRead,
            double medianMicros,
            double p95Micros,
            double parseMicros) {
    }

    /**
     * Runs one (n, mode) cell.
     *
     * @param useIndexes false forces the SeqScan plan for the same query.
     * @param batch      queries per timed region. Must be 1 for COLD, since the reset
     *                   happens outside the timer and only the first query would be cold.
     */
    public static Result run(Table table, int n, String sql, boolean useIndexes,
                             PoolRegime regime, int batch) throws IOException {
        BufferPool pool = table.getBufferPool();
        SelectStatement stmt = (SelectStatement) Parser.parse(sql);
        Planner planner = new Planner(table).useIndexes(useIndexes);

        int effectiveBatch = (regime == PoolRegime.COLD) ? 1 : batch;

        // ---- Guards, before the clock starts -------------------------------------
        // A silently unmatched rewrite rule makes both arms run SeqScan, the curves
        // overlap, and the null result looks like "the index does not help" rather
        // than the plan bug it actually is.
        Operator plan = planner.plan(stmt);
        boolean hasSeek = Planner.containsNode(plan, IndexSeek.class);
        boolean hasScan = Planner.containsNode(plan, SeqScan.class);
        if (useIndexes && (!hasSeek || hasScan)) {
            throw new IllegalStateException("expected an IndexSeek plan for [" + sql
                    + "] but got:\n" + Planner.explain(plan));
        }
        if (!useIndexes && (hasSeek || !hasScan)) {
            throw new IllegalStateException("expected a forced SeqScan plan for [" + sql
                    + "] but got:\n" + Planner.explain(plan));
        }

        // Timing a query that returns nothing measures the miss path, not the work.
        List<Row> sample = Executor.drain(plan);
        if (sample.isEmpty()) {
            throw new IllegalStateException("query [" + sql + "] returned no rows at n=" + n);
        }
        pool.assertNoPinnedFrames();

        // ---- Pages read: deterministic, and the primary metric --------------------
        // Measured in its own pass so the counter delta is not polluted by warmup.
        if (regime == PoolRegime.COLD) {
            pool.clear();
        }
        pool.resetCounters();
        int rows = Executor.drain(planner.plan(stmt)).size();
        int pagesRead = pool.getMisses();
        pool.assertNoPinnedFrames();

        // ---- Warmup, discarded ----------------------------------------------------
        for (int i = 0; i < WARMUP; i++) {
            if (regime == PoolRegime.COLD) {
                pool.clear();
            }
            for (int k = 0; k < effectiveBatch; k++) {
                consume(Executor.drain(planner.plan((SelectStatement) Parser.parse(sql))));
            }
        }

        // ---- Measurement ----------------------------------------------------------
        // Pool reset sits OUTSIDE the timed region: clear() walks and flushes frames,
        // which is harness cost, not query cost. Timing it would add a term that grows
        // with pool size and would be indistinguishable from query work in the result.
        List<Double> samples = new ArrayList<>(MEASURE);
        for (int i = 0; i < MEASURE; i++) {
            if (regime == PoolRegime.COLD) {
                pool.clear();
            }
            long t0 = System.nanoTime();
            for (int k = 0; k < effectiveBatch; k++) {
                consume(Executor.drain(planner.plan((SelectStatement) Parser.parse(sql))));
            }
            long elapsed = System.nanoTime() - t0;
            samples.add(elapsed / 1000.0 / effectiveBatch);
        }
        pool.assertNoPinnedFrames();

        // ---- Parse cost, measured separately --------------------------------------
        // MiniDB re-parses on every query; SQLite with a prepared statement would not.
        // Reporting the split keeps that comparison honest and is a real number on its own.
        double parseMicros = measureParse(sql, effectiveBatch);

        Collections.sort(samples);
        return new Result(n, useIndexes ? "index" : "scan", regime, pool.getCapacity(),
                rows, pagesRead, percentile(samples, 0.50), percentile(samples, 0.95), parseMicros);
    }

    private static double measureParse(String sql, int batch) {
        for (int i = 0; i < WARMUP; i++) {
            for (int k = 0; k < batch; k++) {
                consume(Parser.parse(sql));
            }
        }
        List<Double> samples = new ArrayList<>(MEASURE);
        for (int i = 0; i < MEASURE; i++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < batch; k++) {
                consume(Parser.parse(sql));
            }
            samples.add((System.nanoTime() - t0) / 1000.0 / batch);
        }
        Collections.sort(samples);
        return percentile(samples, 0.50);
    }

    /** Linear-interpolated percentile over an already-sorted list. */
    public static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted.get(lo);
        }
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    /** Feeds results into a sink so the JIT cannot delete the query as unused. */
    private static void consume(Object o) {
        if (o != null) {
            sink += o.hashCode();
        }
    }

    public static long sink() {
        return sink;
    }
}
