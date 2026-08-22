package com.minidb.bench;

import com.minidb.index.BPlusTree;
import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
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
    private static final int WARMUP = 2;
    private static final int MEASURE = 7;

    private static final int POOL_FRAMES = 64;

    public record Rebuild(int n, int pages, int height,
                          double medianMillis, double minMillis, double p95Millis,
                          double microsPerRow) {
    }

    public static void main(String[] args) throws IOException {
        List<Rebuild> results = new ArrayList<>();

        System.out.println("E5: index rebuild on open, pool " + POOL_FRAMES
                + " frames, fanout " + BPlusTree.DEFAULT_MAX_KEYS);
        System.out.println("Rebuild = full heap scan + one B+Tree insert per row, "
                + "paid before any query runs.\n");
        System.out.printf("%-9s %8s %7s %12s %12s %12s %12s%n",
                "n", "pages", "height", "median ms", "min ms", "p95 ms", "us/row");

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
                    Harness.percentile(samples, 0.95), median * 1000.0 / n);
            results.add(r);
            System.out.printf("%-9d %8d %7d %12.2f %12.2f %12.2f %12.3f%n",
                    r.n(), r.pages(), r.height(), r.medianMillis(), r.minMillis(),
                    r.p95Millis(), r.microsPerRow());
        }

        // Linearity check: per-row cost should be roughly flat if rebuild is O(n log n)
        // with a small log factor. A rising per-row column is the story, not a defect.
        System.out.println("\nScaling (median ms, relative to previous decade):");
        for (int i = 1; i < results.size(); i++) {
            double ratio = results.get(i).medianMillis() / results.get(i - 1).medianMillis();
            System.out.printf("  %d -> %d : %.2fx  (10x more rows)%n",
                    results.get(i - 1).n(), results.get(i).n(), ratio);
        }

        try (PrintWriter out = new PrintWriter("e5_results.csv")) {
            out.println("n,pages,index_height,median_ms,min_ms,p95_ms,us_per_row");
            for (Rebuild r : results) {
                out.printf("%d,%d,%d,%.3f,%.3f,%.3f,%.4f%n", r.n(), r.pages(), r.height(),
                        r.medianMillis(), r.minMillis(), r.p95Millis(), r.microsPerRow());
            }
        }
        System.out.println("\nWrote e5_results.csv");
    }

    /** Opening a Table runs rebuildIndex(); this is the operation under test. */
    private static Table open(String path) throws IOException {
        DiskManager disk = new DiskManager();
        disk.open(path);
        return new Table(new BufferPool(disk, POOL_FRAMES), BPlusTree.DEFAULT_MAX_KEYS);
    }

    private static void openAndClose(String path) throws IOException {
        open(path).close();
    }
}
