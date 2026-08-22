package com.minidb.table;

import com.minidb.index.BPlusTree;
import com.minidb.index.Rid;
import com.minidb.record.Row;
import com.minidb.record.RowPage;
import com.minidb.record.RowSerializer;
import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
import com.minidb.storage.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A single heap table: rows appended across as many pages as they need.
 *
 * Sits on top of a BufferPool (which sits on top of DiskManager).
 * An in-memory B+Tree secondary index is maintained on the `id` column.
 *
 * Every page access through the buffer pool honors the fetchPage / unpin protocol
 * wrapped in try/finally blocks to prevent pin leaks.
 */
public class Table {
    private final BufferPool bufferPool;
    private final BPlusTree index;

    public Table(DiskManager disk) throws IOException {
        this(new BufferPool(disk, 128));
    }

    public Table(BufferPool bufferPool) throws IOException {
        this.bufferPool = bufferPool;
        this.index = new BPlusTree();
        rebuildIndex();
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    public DiskManager getDisk() {
        return bufferPool.getDisk();
    }

    public BPlusTree getIndex() {
        return index;
    }

    /** Appends a row, spilling onto a new page when the last one is full, and updates the index. */
    public void insert(Row row) throws IOException {
        // Enforce primary key uniqueness on id before modifying the heap
        if (index.search(row.getId()) != null) {
            throw new IllegalArgumentException("Duplicate key: id " + row.getId() + " already exists");
        }

        byte[] bytes = RowSerializer.serialize(row);

        // Checked before anything touches disk. Deferring this to appendToNewPage
        // would reject the row only after allocatePage() had already grown the
        // file, leaving a trailing page of zeros with no header - and a zeroed
        // header reads as freeOffset 0, so the next insert would write its row
        // at offset 0 and then stamp the header over it.
        if (bytes.length > RowPage.maxRowSize()) {
            throw new IllegalArgumentException(
                    "Row of " + bytes.length + " bytes exceeds the " + RowPage.maxRowSize()
                            + " bytes a page can hold");
        }

        int numPages = bufferPool.getDisk().getNumPages();

        if (numPages == 0) {
            appendToNewPage(row.getId(), bytes);
            return;
        }

        int last = numPages - 1;
        Page page = bufferPool.fetchPage(last);
        boolean dirtied = false;
        try {
            RowPage rowPage = RowPage.load(page); // load, not init - the header is already there
            int offset = rowPage.insertRow(bytes);
            if (offset >= 0) {
                dirtied = true;
                index.insert(row.getId(), new Rid(last, offset));
                return;
            }
        } finally {
            bufferPool.unpin(last, dirtied);
        }

        appendToNewPage(row.getId(), bytes);
    }

    /** Full table scan: every page, in page order, rows in insertion order. */
    public List<Row> scan() throws IOException {
        List<Row> all = new ArrayList<>();
        int numPages = bufferPool.getDisk().getNumPages();
        for (int i = 0; i < numPages; i++) {
            Page page = bufferPool.fetchPage(i);
            try {
                all.addAll(RowPage.load(page).getAllRows());
            } finally {
                bufferPool.unpin(i, false);
            }
        }
        return all;
    }

    /**
     * A scan with the filter applied in Java. This is the shape a WHERE clause
     * takes once Stage 5 can parse one: `age > 18` becomes `r -> r.getAge() > 18`.
     */
    public List<Row> scanWhere(Predicate<Row> predicate) throws IOException {
        List<Row> matches = new ArrayList<>();
        for (Row row : scan()) {
            if (predicate.test(row)) {
                matches.add(row);
            }
        }
        return matches;
    }

    /** Flushes any buffered dirty pages to disk. */
    public void flush() throws IOException {
        bufferPool.flushAll();
    }

    /**
     * Flushes and then closes the underlying file. Closing the DiskManager directly
     * drops every dirty page still sitting in the pool, so prefer this.
     */
    public void close() throws IOException {
        flush();
        bufferPool.getDisk().close();
    }

    private void appendToNewPage(int id, byte[] bytes) throws IOException {
        int pageNum = bufferPool.getDisk().allocatePage();
        Page page = bufferPool.fetchPage(pageNum);
        boolean dirtied = false;
        try {
            RowPage rowPage = RowPage.init(page); // brand-new page - stamp the header
            int offset = rowPage.insertRow(bytes);
            if (offset < 0) {
                // insert() screens oversized rows already, so reaching here means the
                // page geometry itself is wrong rather than the caller's row.
                throw new IllegalStateException(
                        "Row of " + bytes.length + " bytes did not fit an empty page");
            }
            dirtied = true;
            index.insert(id, new Rid(pageNum, offset));
        } finally {
            bufferPool.unpin(pageNum, dirtied);
        }
    }

    private void rebuildIndex() throws IOException {
        int numPages = bufferPool.getDisk().getNumPages();
        for (int p = 0; p < numPages; p++) {
            Page page = bufferPool.fetchPage(p);
            try {
                RowPage rowPage = RowPage.load(page);
                for (RowPage.RowWithOffset ro : rowPage.getAllRowsWithOffsets()) {
                    index.insert(ro.row().getId(), new Rid(p, ro.offset()));
                }
            } finally {
                bufferPool.unpin(p, false);
            }
        }
    }
}
