package com.minidb.plan;

import com.minidb.record.Row;
import com.minidb.record.RowPage;
import com.minidb.storage.BufferPool;
import com.minidb.storage.Page;

import java.io.IOException;
import java.util.List;

/**
 * Full heap scan, one page at a time, in page order.
 *
 * This is the operator that owns the pin discipline: it holds exactly one page pinned
 * while yielding that page's rows, unpins it before moving to the next, and unpins in
 * close() when abandoned mid-page. A LIMIT upstream will routinely close it while it
 * still holds a pin, so that path is load-bearing rather than defensive.
 */
public class SeqScan implements Operator {

    private static final int NONE = -1;

    private final BufferPool pool;

    private int nextPage;
    private int pinnedPage = NONE;
    private RowPage.RowCursor cursor;
    private boolean open;

    public SeqScan(BufferPool pool) {
        this.pool = pool;
    }

    @Override
    public void open() {
        nextPage = 0;
        pinnedPage = NONE;
        cursor = null;
        open = true;
    }

    @Override
    public Row next() throws IOException {
        if (!open) {
            throw new IllegalStateException("SeqScan.next() called before open()");
        }
        while (true) {
            if (cursor != null && cursor.hasNext()) {
                return cursor.next();
            }
            // Current page is spent (or there is none yet) — advance.
            releasePin();
            if (nextPage >= pool.getDisk().getNumPages()) {
                return null;
            }
            int target = nextPage++;
            Page page = pool.fetchPage(target);
            pinnedPage = target;
            cursor = RowPage.load(page).cursor();
        }
    }

    @Override
    public void close() {
        releasePin();
        cursor = null;
        open = false;
    }

    /** Idempotent: pinnedPage is reset to NONE before unpinning can be attempted again. */
    private void releasePin() {
        if (pinnedPage != NONE) {
            int held = pinnedPage;
            pinnedPage = NONE;
            pool.unpin(held, false);
        }
    }

    @Override
    public List<Operator> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "SeqScan(users)";
    }

    /** Toy cost: one unit per page touched. */
    @Override
    public double estimatedCost() {
        return Math.max(1, pool.getDisk().getNumPages());
    }
}
