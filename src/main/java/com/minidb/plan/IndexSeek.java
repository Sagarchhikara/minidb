package com.minidb.plan;

import com.minidb.index.BPlusTree;
import com.minidb.index.Rid;
import com.minidb.record.Row;
import com.minidb.record.RowSerializer;
import com.minidb.storage.BufferPool;
import com.minidb.storage.Page;

import java.io.IOException;
import java.util.List;

/**
 * Point lookup through the B+Tree index on `id`. Yields at most one row.
 *
 * The tree search happens in open(); next() fetches the single page the Rid points at,
 * decodes the row, and unpins immediately — unlike SeqScan there is nothing to hold a
 * pin across, since the operator is exhausted after one row.
 */
public class IndexSeek implements Operator {

    private final BufferPool pool;
    private final BPlusTree index;
    private final int key;

    private Rid hit;
    private boolean consumed;
    private boolean open;

    public IndexSeek(BufferPool pool, BPlusTree index, int key) {
        this.pool = pool;
        this.index = index;
        this.key = key;
    }

    @Override
    public void open() {
        hit = index.search(key);
        consumed = false;
        open = true;
    }

    @Override
    public Row next() throws IOException {
        if (!open) {
            throw new IllegalStateException("IndexSeek.next() called before open()");
        }
        if (hit == null || consumed) {
            return null;
        }
        consumed = true;
        Page page = pool.fetchPage(hit.pageNum());
        try {
            return RowSerializer.deserialize(page.getData(), hit.offset());
        } finally {
            pool.unpin(hit.pageNum(), false);
        }
    }

    @Override
    public void close() {
        hit = null;
        consumed = true;
        open = false;
    }

    @Override
    public List<Operator> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "IndexSeek(users.id = " + key + ")";
    }

    /** Toy cost: descend the tree, then one page fetch for the row. */
    @Override
    public double estimatedCost() {
        return index.height() + 1.0;
    }
}
