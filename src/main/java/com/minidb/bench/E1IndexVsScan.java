package com.minidb.bench;

import com.minidb.bench.Harness.PoolRegime;
import com.minidb.bench.Harness.Result;
import com.minidb.index.BPlusTree;
import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
import com.minidb.table.Table;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * E1: index vs full scan within MiniDB, across n = 1K..1M.
 *
 * The claim under test is asymptotic, not competitive: the index arm should be flat in
 * pages read while the scan arm grows linearly. Pages read is the primary metric because
 * it is deterministic and is the actual algorithmic quantity; latency is reported
 * alongside but carries JIT, GC, and OS-cache caveats that pages do not.
 *
 * Pool regime: FIXED frame count across all n, so pool-as-a-fraction-of-table shrinks
 * as n grows. That is what happens to a real system as data outgrows memory. The
 * alternative — scaling the pool with n — is equally defensible but answers a different
 * question, and mixing the two silently would bend the curve for two reasons at once.
 */
public final class E1IndexVsScan {

    private static final int[] SCALES = {1_000, 10_000, 100_000, 1_000_000};

    /** Constant across every n, deliberately. See the class comment. */
    private static final int POOL_FRAMES = 64;

    /** Warm-pool batching; COLD forces batch=1 inside the harness. */
    private static final int WARM_BATCH = 50;

    public static void main(String[] args) throws IOException {
        List<Result> results = new ArrayList<>();

        System.out.println("E1: index vs scan, pool fixed at " + POOL_FRAMES + " frames, "
                + "B+Tree fanout " + BPlusTree.DEFAULT_MAX_KEYS);
        System.out.println("Every number is warm-OS-cache: the page cache is not dropped "
                + "between runs (not portable), so 'cold' means cold buffer pool only.\n");

        // Per-cell WARMUP only warms the JIT for code already loaded. The FIRST cell of
        // the run additionally pays class loading and initial C2 compilation, which
        // showed up as a 4x inflated median and 6x inflated parse cost at n=1000. One
        // discarded cell up front removes an artifact that would otherwise read as
        // "small n is slower".
        globalWarmup();

        System.out.printf("%-9s %-6s %-6s %6s %8s %10s %10s %9s%n",
                "n", "mode", "regime", "pool", "rows", "pages", "median us", "p95 us");

        for (int n : SCALES) {
            String path = DataGen.ensure(n);

            for (PoolRegime regime : PoolRegime.values()) {
                for (boolean useIndexes : new boolean[]{true, false}) {
                    // Fresh Table per cell so index-rebuild cost never lands in a sample,
                    // and so a warm cell never inherits the previous cell's pool state.
                    DiskManager disk = new DiskManager();
                    disk.open(path);
                    Table table = new Table(new BufferPool(disk, POOL_FRAMES),
                            BPlusTree.DEFAULT_MAX_KEYS);

                    // A point lookup in the middle of the key range: the index can answer
                    // it with a seek, and forcing useIndexes=false makes the same query
                    // walk the heap. Same query, same rows, different access path.
                    String sql = "SELECT * FROM users WHERE id = " + (n / 2);

                    Result r = Harness.run(table, n, sql, useIndexes, regime, WARM_BATCH);
                    results.add(r);
                    System.out.printf("%-9d %-6s %-6s %6d %8d %10d %10.2f %9.2f%n",
                            r.n(), r.mode(), r.regime(), r.poolFrames(), r.rowsReturned(),
                            r.pagesRead(), r.medianMicros(), r.p95Micros());

                    table.close();
                }
            }
        }

        writeCsv(results);
        System.out.println("\nWrote e1_results.csv");
        System.out.println("(sink=" + Harness.sink() + ")");
    }

    /** One full discarded cell, so the first recorded cell is not the JVM's first. */
    private static void globalWarmup() throws IOException {
        String path = DataGen.ensure(SCALES[0]);
        for (boolean useIndexes : new boolean[]{true, false}) {
            DiskManager disk = new DiskManager();
            disk.open(path);
            Table table = new Table(new BufferPool(disk, POOL_FRAMES), BPlusTree.DEFAULT_MAX_KEYS);
            Harness.run(table, SCALES[0], "SELECT * FROM users WHERE id = " + (SCALES[0] / 2),
                    useIndexes, PoolRegime.WARM, WARM_BATCH);
            table.close();
        }
    }

    private static void writeCsv(List<Result> results) throws IOException {
        try (PrintWriter out = new PrintWriter("e1_results.csv")) {
            out.println("n,mode,regime,pool_frames,rows,pages_read,median_us,p95_us,parse_us");
            for (Result r : results) {
                out.printf("%d,%s,%s,%d,%d,%d,%.3f,%.3f,%.3f%n",
                        r.n(), r.mode(), r.regime(), r.poolFrames(), r.rowsReturned(),
                        r.pagesRead(), r.medianMicros(), r.p95Micros(), r.parseMicros());
            }
        }
    }
}
