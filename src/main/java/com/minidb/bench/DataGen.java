package com.minidb.bench;

import com.minidb.index.BPlusTree;
import com.minidb.record.Row;
import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
import com.minidb.table.Table;

import java.io.File;
import java.io.IOException;

/**
 * Builds and caches the benchmark tables.
 *
 * Generation is deterministic (fixed seed, ids 1..n dense) so a rebuilt file is
 * byte-identical and a cached one is safe to reuse. Rows are a fixed shape so that
 * rows-per-page is constant across n — otherwise the page counts that are the primary
 * metric would move for two reasons at once.
 */
public final class DataGen {

    /** Directory holding generated .db files, reused across runs. */
    public static final String DATA_DIR = "bench-data";

    private DataGen() {
    }

    public static String pathFor(int n) {
        return DATA_DIR + File.separator + "bench_" + n + ".db";
    }

    /**
     * Row payload for a given id. Fixed width so rows-per-page does not drift with n.
     * Age spans 20..79 so range predicates match a stable fraction at every scale.
     */
    public static Row rowFor(int id) {
        return new Row(id, String.format("user-%09d-padding", id), 20 + (id % 60));
    }

    /**
     * Returns the path to a table of n rows, generating it only if absent or stale.
     *
     * Building 1M rows takes a while and every experiment reuses the same files, so
     * regenerating per run would dominate the wall clock for no benefit.
     */
    public static String ensure(int n) throws IOException {
        new File(DATA_DIR).mkdirs();
        String path = pathFor(n);
        File f = new File(path);

        if (f.exists() && verify(path, n, false)) {
            return path;
        }
        f.delete();

        DiskManager disk = new DiskManager();
        disk.open(path);
        // A generous pool for building only; the experiment sets its own capacity when
        // it reopens the file, so this does not leak into any measurement.
        Table table = new Table(new BufferPool(disk, 256), BPlusTree.DEFAULT_MAX_KEYS);
        for (int id = 1; id <= n; id++) {
            table.insert(rowFor(id));
        }
        table.close();

        if (!verify(path, n, true)) {
            throw new IOException("generated table for n=" + n + " failed verification");
        }
        return path;
    }

    /**
     * Confirms a generated file really holds n correct rows.
     *
     * A truncated or half-written file would otherwise be silently benchmarked, and
     * "the scan got faster" is exactly what a short table looks like.
     */
    public static boolean verify(String path, int n, boolean loud) throws IOException {
        DiskManager disk = new DiskManager();
        disk.open(path);
        Table table = new Table(new BufferPool(disk, 256), BPlusTree.DEFAULT_MAX_KEYS);
        try {
            int count = 0;
            int mismatches = 0;
            for (Row row : table.scan()) {
                count++;
                Row expected = rowFor(row.getId());
                if (row.getId() < 1 || row.getId() > n
                        || !row.getName().equals(expected.getName())
                        || row.getAge() != expected.getAge()) {
                    mismatches++;
                }
            }
            // Spot-check the index at both ends and the middle, plus a known absent key.
            boolean indexOk = table.getIndex().search(1) != null
                    && table.getIndex().search(n / 2) != null
                    && table.getIndex().search(n) != null
                    && table.getIndex().search(n + 1) == null;

            boolean ok = count == n && mismatches == 0 && indexOk;
            if (loud) {
                System.out.printf("  verify n=%d: rows=%d mismatches=%d indexOk=%b pages=%d -> %s%n",
                        n, count, mismatches, indexOk, disk.getNumPages(), ok ? "OK" : "BAD");
            }
            return ok;
        } finally {
            table.close();
        }
    }

    /** Reports rows-per-page and index height, the two shape numbers the write-up needs. */
    public static void describe(int n) throws IOException {
        String path = ensure(n);
        DiskManager disk = new DiskManager();
        disk.open(path);
        Table table = new Table(new BufferPool(disk, 256), BPlusTree.DEFAULT_MAX_KEYS);
        int pages = disk.getNumPages();
        System.out.printf("  n=%-8d pages=%-6d rows/page=%-6.1f index height=%d%n",
                n, pages, n / (double) pages, table.getIndex().height());
        table.close();
    }

    public static void main(String[] args) throws IOException {
        int[] scales = {1_000, 10_000, 100_000, 1_000_000};
        System.out.println("Generating benchmark tables (cached in " + DATA_DIR + "/)");
        for (int n : scales) {
            long start = System.nanoTime();
            ensure(n);
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("  n=%-8d ready in %d ms%n", n, ms);
        }
        System.out.println("Shape:");
        for (int n : scales) {
            describe(n);
        }
    }
}
