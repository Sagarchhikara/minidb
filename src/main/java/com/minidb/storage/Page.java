package com.minidb.storage;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Represents a fixed-size (4096-byte) block of memory and disk storage in MiniDB.
 * <p>
 * {@code Page} is the fundamental unit of I/O, caching, and storage layout in the database engine.
 * Disk files are formatted into contiguous fixed-size pages, and all memory operations within the
 * buffer pool operate on page-level objects.
 * </p>
 * 
 * <h3>Key Responsibilities & Working Mechanism:</h3>
 * <ul>
 *   <li><b>Fixed Capacity:</b> Constant size defined by {@link #PAGE_SIZE} (4 KB = 4096 bytes).</li>
 *   <li><b>Page Identification:</b> Identified by a 0-indexed {@code pageNumber}, which corresponds directly
 *       to the file position offset {@code (pageNumber * PAGE_SIZE)} in {@code DiskManager}.</li>
 *   <li><b>Dirty Tracking:</b> Tracks modifications using a {@code dirty} flag. Writing data via
 *       {@link #putInt(int, int)} or {@link #putBytes(int, byte[])} automatically sets {@code dirty = true},
 *       notifying {@code BufferPool} and {@code DiskManager} that this page must be flushed to disk before eviction.</li>
 *   <li><b>Raw Byte Manipulation:</b> Provides helper methods for reading and writing primitive data types
 *       (e.g., 32-bit big-endian integers, raw byte arrays) with automatic bounds validation. Higher-level
 *       abstractions such as {@code RowPage} construct structured formats (headers, slot arrays, tuple data)
 *       on top of this raw byte array.</li>
 * </ul>
 */
public class Page {
    /**
     * The fixed size of a page in bytes (4096 bytes / 4 KB).
     * Matches typical OS hardware block and page sizes for optimal disk I/O efficiency.
     */
    public static final int PAGE_SIZE = 4096;

    /**
     * Unique 0-indexed page identifier corresponding to disk block position.
     */
    private final int pageNumber;

    /**
     * Raw byte array containing the page payload (length is always {@link #PAGE_SIZE}).
     */
    private final byte[] data;

    /**
     * Flag indicating if in-memory content has been modified relative to the persisted disk version.
     */
    private boolean dirty;

    /**
     * Constructs a new, blank in-memory {@code Page} with default zero-filled bytes.
     * Marks the page initial state as clean (dirty = false).
     *
     * @param pageNumber the unique ID assigned to this page
     */
    public Page(int pageNumber) {
        this.pageNumber = pageNumber;
        this.data = new byte[PAGE_SIZE];
        this.dirty = false;
    }

    /**
     * Constructs a {@code Page} wrapping an existing byte array loaded from disk.
     * Marks the page initial state as clean (dirty = false).
     *
     * @param pageNumber the unique ID assigned to this page
     * @param data       raw byte array of length {@link #PAGE_SIZE}
     * @throws IllegalArgumentException if {@code data.length} is not equal to {@link #PAGE_SIZE}
     */
    public Page(int pageNumber, byte[] data) {
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException("Page data must be exactly " + PAGE_SIZE + " bytes");
        }
        this.pageNumber = pageNumber;
        this.data = data;
        this.dirty = false;
    }

    /**
     * Gets the unique page identifier.
     *
     * @return 0-indexed page number
     */
    public int getPageNumber() {
        return pageNumber;
    }

    /**
     * Gets the raw byte array backing this page.
     *
     * @return the byte array of size {@link #PAGE_SIZE}
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Checks whether the page has unpersisted changes.
     *
     * @return {@code true} if modified, {@code false} otherwise
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Explicitly marks this page as dirty (modified).
     */
    public void markDirty() {
        this.dirty = true;
    }

    /**
     * Clears the dirty flag, usually invoked by {@code DiskManager} after successfully writing to disk.
     */
    public void clearDirty() {
        this.dirty = false;
    }

    /**
     * Reads a 4-byte big-endian integer from the specified byte offset.
     *
     * @param offset byte offset within the page
     * @return 32-bit integer value
     * @throws IndexOutOfBoundsException if offset access exceeds page boundaries
     */
    public int getInt(int offset) {
        checkBounds(offset, Integer.BYTES);
        return ByteBuffer.wrap(data).getInt(offset);
    }

    /**
     * Writes a 4-byte big-endian integer at the specified byte offset and marks the page as dirty.
     *
     * @param offset byte offset within the page
     * @param value  32-bit integer value to store
     * @throws IndexOutOfBoundsException if offset access exceeds page boundaries
     */
    public void putInt(int offset, int value) {
        checkBounds(offset, Integer.BYTES);
        ByteBuffer.wrap(data).putInt(offset, value);
        markDirty();
    }

    /**
     * Reads a slice of bytes from the specified byte offset and length.
     *
     * @param offset byte starting offset within the page
     * @param length number of bytes to read
     * @return a new byte array copy containing the slice
     * @throws IndexOutOfBoundsException if offset or range exceeds page boundaries
     */
    public byte[] getBytes(int offset, int length) {
        checkBounds(offset, length);
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    /**
     * Copies a byte array into the page at the specified byte offset and marks the page as dirty.
     *
     * @param offset byte starting offset within the page
     * @param src    source byte array to write into page data
     * @throws IndexOutOfBoundsException if destination range exceeds page boundaries
     */
    public void putBytes(int offset, byte[] src) {
        checkBounds(offset, src.length);
        System.arraycopy(src, 0, data, offset, src.length);
        markDirty();
    }

    /**
     * Validates that an operation accessing {@code [offset, offset + length)} remains fully within
     * the page bounds {@code [0, PAGE_SIZE)}.
     *
     * @param offset start offset of the access
     * @param length byte count of the access
     * @throws IndexOutOfBoundsException if access range is out of bounds
     */
    private void checkBounds(int offset, int length) {
        if (offset < 0 || offset + length > PAGE_SIZE) {
            throw new IndexOutOfBoundsException(
                    "Access [" + offset + ", " + (offset + length) + ") out of bounds for page size " + PAGE_SIZE);
        }
    }
}