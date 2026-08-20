package com.minidb.table;

import com.minidb.index.BPlusTree;
import com.minidb.index.Rid;
import com.minidb.record.Row;
import com.minidb.record.RowPage;
import com.minidb.record.RowSerializer;
import com.minidb.storage.DiskManager;
import com.minidb.storage.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A single heap table: rows appended across as many pages as they need.
 *
 * An in-memory B+Tree secondary index is maintained on the `id` column.
 * Because the heap is append-only and row (pageNum, offset) locations are stable,
 * the index is derived from the heap and rebuilt by scanning all pages upon open.
 */
public class Table {
    private final DiskManager disk;
    private final BPlusTree index;

    public Table(DiskManager disk) throws IOException {
        this.disk = disk;
        this.index = new BPlusTree();
        rebuildIndex();
    }

    public BPlusTree getIndex() {
        return index;
    }

    public DiskManager getDisk() {
        return disk;
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

        int numPages = disk.getNumPages();

        if (numPages == 0) {
            appendToNewPage(row.getId(), bytes);
            return;
        }

        int last = numPages - 1;
        Page page = disk.readPage(last);
        RowPage rowPage = RowPage.load(page); // load, not init - the header is already there
        int offset = rowPage.insertRow(bytes);
        if (offset >= 0) {
            disk.writePage(page);
            index.insert(row.getId(), new Rid(last, offset));
        } else {
            appendToNewPage(row.getId(), bytes);
        }
    }

    /** Full table scan: every page, in page order, rows in insertion order. */
    public List<Row> scan() throws IOException {
        List<Row> all = new ArrayList<>();
        int numPages = disk.getNumPages();
        for (int i = 0; i < numPages; i++) {
            all.addAll(RowPage.load(disk.readPage(i)).getAllRows());
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

    private void appendToNewPage(int id, byte[] bytes) throws IOException {
        int pageNum = disk.allocatePage();
        Page page = disk.readPage(pageNum);
        RowPage rowPage = RowPage.init(page); // brand-new page - stamp the header
        int offset = rowPage.insertRow(bytes);
        if (offset < 0) {
            // insert() screens oversized rows already, so reaching here means the
            // page geometry itself is wrong rather than the caller's row.
            throw new IllegalStateException(
                    "Row of " + bytes.length + " bytes did not fit an empty page");
        }
        disk.writePage(page); // forget this and the rows never reach disk
        index.insert(id, new Rid(pageNum, offset));
    }

    private void rebuildIndex() throws IOException {
        int numPages = disk.getNumPages();
        for (int p = 0; p < numPages; p++) {
            Page page = disk.readPage(p);
            RowPage rowPage = RowPage.load(page);
            for (RowPage.RowWithOffset ro : rowPage.getAllRowsWithOffsets()) {
                index.insert(ro.row().getId(), new Rid(p, ro.offset()));
            }
        }
    }
}
