package com.minidb.bench;

import com.minidb.index.BPlusTree;
import com.minidb.record.RowPage;
import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
import com.minidb.storage.Page;
import com.minidb.table.Table;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * E5: index rebuild cost on open, vs n.
 *
 * The B+Tree is in-memory and derived, so every open() re-scans the whole heap and
 * re-inserts every key. That cost is paid before a single query runs, and it grows
 * linearly in n while the queries it accelerates are logarithmic.
 *
 * This is the measured argument for disk-resident index nodes — the fork deferred at
 * Stage 6.5 — and it is the same argument as E1's index-page caveat: a persistent tree
 * would read ~log_f(n) pages per lookup instead of 0, but would not re-read the entire
 * heap on startup. Both point at the same missing piece.
 */
public final class E5RebuildCost {

    private static final int[] SCALES = {1_000, 10_000, 100_000, 1_000_000};

    /** Rebuild is slow enough that batching is pointless; these are whole-operation timings. */
    private static final int WARMUP = 3;
    /**
     * 7 samples was not enough. Two runs of the 100K->1M decade disagreed by a third
     * (13.3x vs 9.8x), which is the difference between "the tree gained a level" and
     * "it is just linear" — a conclusion-changing amount of noise. 21 samples, and the
     * min reported next to the median, so a claim can be checked against the quietest
     * sample rather than the middle of a noisy pile.
     */
    private static final int MEASURE = 21;

    private static final int POOL_FRAMES = 64;

    /**
     * Fanouts swept at a fixed n, to isolate tree height as the cause of the 1M row.
     *
     * The scale sweep confounds two things: n grows AND the tree gains a level. Holding
     * n at 1M and moving only the fanout changes the height (3 / 4 / 5) while the heap
     * scan, the row count, and the per-open fixed cost all stay put. If rebuild really
     * is O(n * height), these three points differ by height alone.
     */
    private static final int[] SWEEP_FANOUTS = {256, 128, 32};

    private static final int SWEEP_N = 1_000_000;

    public record FanoutPoint(int fanout, int height, double medianMillis, double minMillis) {
    }

    public record Rebuild(int n, int pages, int height,
                          double medianMillis, double minMillis, double p95Millis,
                          double microsPerRow, double scanOnlyMillis) {
    }

    public static void main(String[] args) throws IOException {
        List<Rebuild> results = new ArrayList<>();

        System.out.println("E5: index rebuild on open, pool " + POOL_FRAMES
                + " frames, fanout " + BPlusTree.DEFAULT_MAX_KEYS);
        System.out.println("Rebuild = full heap scan + one B+Tree insert per row, "
                + "paid before any query runs.\n");
        System.out.printf("%-9s %8s %7s %12s %12s %12s %12s %12s%n",
                "n", "pages", "height", "median ms", "min ms", "p95 ms", "scan-only ms", "us/row");

        for (int n : SCALES) {
            String path = DataGen.ensure(n);

            for (int i = 0; i < WARMUP; i++) {
                openAndClose(path);
            }

            List<Double> samples = new ArrayList<>(MEASURE);
            for (int i = 0; i < MEASURE; i++) {
                long t0 = System.nanoTime();
                Table table = open(path);
                long elapsed = System.nanoTime() - t0;
                samples.add(elapsed / 1_000_000.0);
                table.close();
            }
            Collections.sort(samples);

            // Shape numbers, measured outside the timed region.
            DiskManager disk = new DiskManager();
            disk.open(path);
            Table table = new Table(new BufferPool(disk, POOL_FRAMES), BPlusTree.DEFAULT_MAX_KEYS);
            int pages = disk.getNumPages();
            int height = table.getIndex().height();
            table.close();

            double median = Harness.percentile(samples, 0.50);
            Rebuild r = new Rebuild(n, pages, height, median, samples.get(0),
                    Harness.percentile(samples, 0.95), median * 1000.0 / n,
                    heapScanOnly(path));
            results.add(r);
            System.out.printf("%-9d %8d %7d %12.2f %12.2f %12.2f %12.2f %12.3f%n",
                    r.n(), r.pages(), r.height(), r.medianMillis(), r.minMillis(),
                    r.p95Millis(), r.scanOnlyMillis(), r.microsPerRow());
        }

        // Per-decade scaling, with the height model printed next to it.
        //
        // Rebuild is one heap scan (linear in n) plus n descents (each linear in height),
        // so a decade that also gains a tree level should cost 10 * (h_new / h_old), not
        // 10x. Printing the prediction beside the observation is the difference between
        // "the 1M row is anomalous" and "the 1M row is the height step, and here is the
        // number it was supposed to be".
        //
        // The early decades undershoot both models because the fixed per-open cost
        // (file open, pool allocation, class loading) is a large fraction of a 0.9 ms
        // sample at n=1000 and a negligible one at n=1000000; that is amortization, and
        // it is why the us/row column falls before it rises.
        System.out.println("\nScaling (median ms, relative to previous decade):");
        for (int i = 1; i < results.size(); i++) {
            Rebuild prev = results.get(i - 1);
            Rebuild curr = results.get(i);
            double ratio = curr.medianMillis() / prev.medianMillis();
            double predicted = 10.0 * curr.height() / prev.height();
            System.out.printf("  %7d -> %-7d observed %5.2fx   height %d -> %d, "
                            + "so 10 x %d/%d = %5.2fx predicted%n",
                    prev.n(), curr.n(), ratio, prev.height(), curr.height(),
                    curr.height(), prev.height(), predicted);
        }

        // Where the time actually goes. The heap scan is the same work at every fanout,
        // so measuring it directly turns the fanout sweep from a three-point curve fit
        // into a decomposition with a known constant term.
        System.out.println("\nRebuild decomposed (median ms):");
        for (Rebuild r : results) {
            double treeWork = r.medianMillis() - r.scanOnlyMillis();
            System.out.printf("  n=%-8d total %8.2f = heap scan %7.2f (%2.0f%%) + tree inserts %8.2f (%2.0f%%)%n",
                    r.n(), r.medianMillis(), r.scanOnlyMillis(),
                    100 * r.scanOnlyMillis() / r.medianMillis(),
                    treeWork, 100 * treeWork / r.medianMillis());
        }

        List<FanoutPoint> sweep = fanoutSweep();

        try (PrintWriter out = new PrintWriter("e5_results.csv")) {
            out.println("n,pages,index_height,median_ms,min_ms,p95_ms,scan_only_ms,us_per_row");
            for (Rebuild r : results) {
                out.printf("%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.4f%n", r.n(), r.pages(), r.height(),
                        r.medianMillis(), r.minMillis(), r.p95Millis(), r.scanOnlyMillis(),
                        r.microsPerRow());
            }
        }
        try (PrintWriter out = new PrintWriter("e5_fanout.csv")) {
            out.println("n,fanout,index_height,median_ms,min_ms");
            for (FanoutPoint f : sweep) {
                out.printf("%d,%d,%d,%.3f,%.3f%n",
                        SWEEP_N, f.fanout(), f.height(), f.medianMillis(), f.minMillis());
            }
        }
        System.out.println("\nWrote e5_results.csv and e5_fanout.csv");
    }

    /**
     * Holds n fixed and moves only the fanout, so height is the only variable left.
     *
     * Reported against the height-3 point rather than against fanout 128, because the
     * claim under test is about height, not about fanout.
     */
    private static List<FanoutPoint> fanoutSweep() throws IOException {
        String path = DataGen.ensure(SWEEP_N);
        List<FanoutPoint> points = new ArrayList<>();

        System.out.printf("%nFanout sweep at n=%d (same file, same rows, same heap scan)%n", SWEEP_N);
        System.out.printf("%-8s %7s %12s %12s%n", "fanout", "height", "median ms", "min ms");

        for (int fanout : SWEEP_FANOUTS) {
            for (int i = 0; i < WARMUP; i++) {
                openAndClose(path, fanout);
            }

            List<Double> samples = new ArrayList<>(MEASURE);
            for (int i = 0; i < MEASURE; i++) {
                long t0 = System.nanoTime();
                Table table = open(path, fanout);
                long elapsed = System.nanoTime() - t0;
                samples.add(elapsed / 1_000_000.0);
                table.close();
            }
            Collections.sort(samples);

            Table shape = open(path, fanout);
            int height = shape.getIndex().height();
            shape.close();

            FanoutPoint point = new FanoutPoint(fanout, height,
                    Harness.percentile(samples, 0.50), samples.get(0));
            points.add(point);
            System.out.printf("%-8d %7d %12.2f %12.2f%n",
                    point.fanout(), point.height(), point.medianMillis(), point.minMillis());
        }

        // Two models, printed side by side, because the sweep is what decides between
        // them. "Height" predicts deeper == slower. "Comparisons" predicts cost tracks
        // (height - 1) * fanout, because BPlusTree.childIndex walks an internal node's
        // keys linearly instead of binary-searching them -- and inserting ascending keys
        // always routes to the rightmost child, so every descent scans every key of
        // every internal node it passes through.
        FanoutPoint base = points.get(0);
        double scanOnly = heapScanOnly(path);
        System.out.printf("%nHeap scan alone (no index insert): %.2f ms -- the constant term.%n", scanOnly);
        System.out.println("Relative to fanout " + base.fanout() + ", tree work only "
                + "(total minus heap scan):");
        double baseTree = base.medianMillis() - scanOnly;
        for (FanoutPoint f : points) {
            double tree = f.medianMillis() - scanOnly;
            double byHeight = f.height() / (double) base.height();
            double byComparisons = ((f.height() - 1) * (double) f.fanout())
                    / ((base.height() - 1) * (double) base.fanout());
            System.out.printf("  fanout %-4d height %d : tree work %7.2f ms = %.2fx   "
                            + "| height model %.2fx | comparison model %.2fx%n",
                    f.fanout(), f.height(), tree, tree / baseTree, byHeight, byComparisons);
        }
        return points;
    }

    /**
     * The heap half of rebuild, with the index half removed.
     *
     * Mirrors Table.rebuildIndex() exactly except for the index.insert call, so the
     * difference between this and a full open is the tree work and nothing else.
     */
    private static double heapScanOnly(String path) throws IOException {
        for (int i = 0; i < WARMUP; i++) {
            scanPass(path);
        }
        List<Double> samples = new ArrayList<>(MEASURE);
        for (int i = 0; i < MEASURE; i++) {
            long t0 = System.nanoTime();
            scanPass(path);
            samples.add((System.nanoTime() - t0) / 1_000_000.0);
        }
        Collections.sort(samples);
        return Harness.percentile(samples, 0.50);
    }

    private static long scanSink;

    private static void scanPass(String path) throws IOException {
        DiskManager disk = new DiskManager();
        disk.open(path);
        BufferPool pool = new BufferPool(disk, POOL_FRAMES);
        int numPages = disk.getNumPages();
        for (int p = 0; p < numPages; p++) {
            Page page = pool.fetchPage(p);
            try {
                RowPage rowPage = RowPage.load(page);
                for (RowPage.RowWithOffset ro : rowPage.getAllRowsWithOffsets()) {
                    // Sunk rather than discarded, so the row decode cannot be optimised away.
                    scanSink += ro.row().getId() + ro.offset();
                }
            } finally {
                pool.unpin(p, false);
            }
        }
        disk.close();
    }

    /** Opening a Table runs rebuildIndex(); this is the operation under test. */
    private static Table open(String path) throws IOException {
        return open(path, BPlusTree.DEFAULT_MAX_KEYS);
    }

    private static Table open(String path, int fanout) throws IOException {
        DiskManager disk = new DiskManager();
        disk.open(path);
        return new Table(new BufferPool(disk, POOL_FRAMES), fanout);
    }

    private static void openAndClose(String path) throws IOException {
        open(path).close();
    }

    private static void openAndClose(String path, int fanout) throws IOException {
        open(path, fanout).close();
    }
}
