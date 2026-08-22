package com.minidb.record;

import com.minidb.storage.Page;

import java.util.ArrayList;
import java.util.List;

/**
 * A slotted-ish view over a raw {@link Page} that stores rows back to back.
 *
 * Layout: [numRows:4][freeOffset:4][record][record]...
 * Records are variable width, so the only way to find record i is to walk from
 * HEADER_SIZE, asking the serializer how long each one is.
 */
public class RowPage {
    private static final int HEADER_SIZE = 8;
    private static final int NUM_ROWS_OFF = 0;
    private static final int FREE_OFF = 4;

    private final Page page;

    private RowPage(Page page) {
        this.page = page;
    }

    /** Stamps a fresh header onto a blank page. */
    public static RowPage init(Page page) {
        page.putInt(NUM_ROWS_OFF, 0);
        page.putInt(FREE_OFF, HEADER_SIZE);
        return new RowPage(page);
    }

    /**
     * Wraps a page that already carries a header (e.g. one just read off disk).
     *
     * An all-zero header is not corruption: allocatePage() grows the file with zeros
     * immediately, so a page allocated but never flushed reads back this way. Treat it
     * as the empty page it is and stamp the header, rather than letting freeOffset 0
     * send the next row on top of the header. Any other out-of-range header is real
     * damage and is raised instead of being written through.
     */
    public static RowPage load(Page page) {
        int free = page.getInt(FREE_OFF);
        int rows = page.getInt(NUM_ROWS_OFF);
        if (free == 0 && rows == 0) {
            return init(page);
        }
        if (free < HEADER_SIZE || free > Page.PAGE_SIZE || rows < 0) {
            throw new IllegalStateException("Corrupt page header on page " + page.getPageNumber()
                    + ": numRows=" + rows + ", freeOffset=" + free);
        }
        return new RowPage(page);
    }

    public record RowWithOffset(Row row, int offset) {
    }

    /**
     * Appends a serialized row. Returns the byte offset where the row was written,
     * or -1 without touching the page when the row will not fit — the caller decides
     * what to do with a full page.
     */
    public int insertRow(byte[] rowBytes) {
        int free = freeOffset();
        if (free + rowBytes.length > Page.PAGE_SIZE) {
            return -1;
        }
        page.putBytes(free, rowBytes);
        page.putInt(FREE_OFF, free + rowBytes.length);
        page.putInt(NUM_ROWS_OFF, numRows() + 1);
        return free;
    }

    public List<Row> getAllRows() {
        byte[] data = page.getData();
        List<Row> rows = new ArrayList<>();
        int offset = HEADER_SIZE;
        int n = numRows();
        for (int i = 0; i < n; i++) {
            rows.add(RowSerializer.deserialize(data, offset));
            offset += RowSerializer.recordSize(data, offset);
        }
        return rows;
    }

    public List<RowWithOffset> getAllRowsWithOffsets() {
        byte[] data = page.getData();
        List<RowWithOffset> rows = new ArrayList<>();
        int offset = HEADER_SIZE;
        int n = numRows();
        for (int i = 0; i < n; i++) {
            Row row = RowSerializer.deserialize(data, offset);
            rows.add(new RowWithOffset(row, offset));
            offset += RowSerializer.recordSize(data, offset);
        }
        return rows;
    }

    public int getRowCount() {
        return numRows();
    }

    /**
     * A lazy forward cursor over this page's rows.
     *
     * getAllRows() materializes the whole page, which defeats the point of a pipelined
     * scan; this decodes one record at a time so SeqScan can hold the page pinned and
     * yield rows on demand. Record widths vary, so the cursor carries the running
     * offset rather than indexing.
     */
    public RowCursor cursor() {
        return new RowCursor();
    }

    public final class RowCursor {
        private int offset = HEADER_SIZE;
        private int index = 0;

        public boolean hasNext() {
            return index < numRows();
        }

        public Row next() {
            if (!hasNext()) {
                throw new IllegalStateException("No more rows on page " + page.getPageNumber());
            }
            byte[] data = page.getData();
            Row row = RowSerializer.deserialize(data, offset);
            offset += RowSerializer.recordSize(data, offset);
            index++;
            return row;
        }
    }

    /** The largest serialized row that could ever fit on a page, header included. */
    public static int maxRowSize() {
        return Page.PAGE_SIZE - HEADER_SIZE;
    }

    public int getFreeSpace() {
        return Page.PAGE_SIZE - freeOffset();
    }

    private int numRows() {
        return page.getInt(NUM_ROWS_OFF);
    }

    private int freeOffset() {
        return page.getInt(FREE_OFF);
    }
}
