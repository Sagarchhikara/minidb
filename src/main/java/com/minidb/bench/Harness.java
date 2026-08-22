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
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
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
    /**
     * Recorded measurements. Each yields one latency sample.
     *
     * Odd, so the median is an actual observation rather than an interpolation. 25 was
     * too few once adaptive batching dropped slow queries to batch=1: a 10K scan cell
     * then spanned only ~10ms total and the median sat 65% above the min. More samples
     * cost little now that batching no longer inflates the slow cells.
     */
    public static final int MEASURE = 51;

    /**
     * Target duration of one timed region, in nanoseconds.
     *
     * Batching exists to lift a sample above timer resolution, but a fixed batch
     * over-corrects at the slow end: at B=50 a 1M warm scan sample spans 1.4s and
     * allocates ~50M Rows, so EVERY sample contains multiple GCs and the median loses
     * its ability to reject them. Sizing each batch to land near this target gives
     * ~300 for a sub-microsecond index seek and 1 for a 27ms scan, so GC-free samples
     * exist for the median to find.
     */
    private static final long TARGET_BATCH_NANOS = 100_000L;

    private static final int MAX_BATCH = 1000;

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
            int evictions,
            int batch,
            double medianMicros,
            double p95Micros,
            double minMicros,
            double gcPerSample,
            double parseMicros) {
    }

    private static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            c += b.getCollectionCount();
        }
        return c;
    }

    /**
     * Runs one (n, mode) cell.
     *
     * @param useIndexes false forces the SeqScan plan for the same query.
     */
    public static Result run(Table table, int n, String sql, boolean useIndexes,
                             PoolRegime regime) throws IOException {
        BufferPool pool = table.getBufferPool();
        SelectStatement stmt = (SelectStatement) Parser.parse(sql);
        Planner planner = new Planner(table).useIndexes(useIndexes);

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
        int evictionsBefore = pool.getEvictions();
        int rows = Executor.drain(planner.plan(stmt)).size();
        int pagesRead = pool.getMisses();
        // Reported because COLD is not purely cold: it starts from an empty pool and so
        // gets `capacity` eviction-free insertions that WARM pays for. The asymmetry is
        // largest when the table is barely bigger than the pool (84 pages vs 64 frames
        // gives WARM 84 evictions and COLD 20) and vanishes as n grows. Without this
        // column that shows up only as an unexplained latency inversion.
        int evictions = pool.getEvictions() - evictionsBefore;
        pool.assertNoPinnedFrames();

        // ---- Batch sizing ----------------------------------------------------------
        // COLD is always 1: the reset sits outside the timer, so with B=50 only the
        // first query of a batch is cold and the sample is a 1:49 blend. That blend
        // does not fail loudly — at 1.2us cold and 0.3us warm it reports 0.318us, i.e.
        // the WARM number to two significant figures, in a table that looks healthy.
        // Asserted below rather than left to the expression staying correct.
        int effectiveBatch = (regime == PoolRegime.COLD) ? 1 : calibrateBatch(planner, stmt);
        if (regime == PoolRegime.COLD && effectiveBatch != 1) {
            throw new IllegalStateException("COLD regime requires batch 1, got " + effectiveBatch);
        }

        // ---- Warmup, discarded ----------------------------------------------------
        for (int i = 0; i < WARMUP; i++) {
            if (regime == PoolRegime.COLD) {
                pool.clear();
            }
            for (int k = 0; k < effectiveBatch; k++) {
                consume(Executor.drain(planner.plan((SelectStatement) Parser.parse(sql))));
            }
        }

        // Data generation can leave frames dirty. The first clear() of a cell would flush
        // them and every later one would not, so one sample would carry a write the rest
        // do not. Warmup happens to absorb it; that is ordering luck, not a guarantee.
        if (pool.dirtyFrameCount() != 0) {
            throw new IllegalStateException("pool has " + pool.dirtyFrameCount()
                    + " dirty frame(s) entering measurement; the first sample would pay a flush");
        }

        // ---- Measurement ----------------------------------------------------------
        // Pool reset sits OUTSIDE the timed region: clear() walks and flushes frames,
        // which is harness cost, not query cost. Timing it would add a term that grows
        // with pool size and would be indistinguishable from query work in the result.
        List<Double> samples = new ArrayList<>(MEASURE);
        long gcBefore = gcCount();
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
        double gcPerSample = (gcCount() - gcBefore) / (double) MEASURE;
        pool.assertNoPinnedFrames();

        // ---- Parse cost, measured separately --------------------------------------
        // MiniDB re-parses on every query; SQLite with a prepared statement would not.
        // Reporting the split keeps that comparison honest and is a real number on its own.
        double parseMicros = measureParse(sql, effectiveBatch);

        Collections.sort(samples);
        // min is reported because it is the GC-free sample. When median and min disagree
        // across two regimes that read identical pages, the difference is collector time,
        // not engine behaviour.
        return new Result(n, useIndexes ? "index" : "scan", regime, pool.getCapacity(),
                rows, pagesRead, evictions, effectiveBatch,
                percentile(samples, 0.50), percentile(samples, 0.95), samples.get(0),
                gcPerSample, parseMicros);
    }

    /**
     * Picks a batch size so one timed region lands near TARGET_BATCH_NANOS.
     *
     * Times a few single queries first, then divides. Clamped to at least 1 so a query
     * slower than the target is measured unbatched.
     */
    private static int calibrateBatch(Planner planner, SelectStatement stmt) throws IOException {
        for (int i = 0; i < 5; i++) {
            consume(Executor.drain(planner.plan(stmt)));
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            consume(Executor.drain(planner.plan(stmt)));
            best = Math.min(best, System.nanoTime() - t0);
        }
        if (best <= 0) {
            return MAX_BATCH;
        }
        long b = TARGET_BATCH_NANOS / best;
        return (int) Math.max(1, Math.min(MAX_BATCH, b));
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
