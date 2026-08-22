package com.minidb.storage;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Manages low-level disk I/O operations for MiniDB.
 * <p>
 * {@code DiskManager} serves as the physical storage interface between in-memory {@link Page} instances
 * and disk storage. It abstracts file I/O operations using a {@link RandomAccessFile} in read-write mode,
 * treating database files as contiguous sequences of fixed-size (4096-byte) blocks.
 * </p>
 * 
 * <h3>Key Responsibilities & Working Mechanism:</h3>
 * <ul>
 *   <li><b>Direct Offset Addressing:</b> Calculates exact byte locations using page math:
 *       {@code fileOffset = (long) pageNumber * Page.PAGE_SIZE}.</li>
 *   <li><b>Page Storage & Persistence:</b> Reads raw 4 KB byte arrays into {@link Page} objects
 *       and persists modified page data back to disk.</li>
 *   <li><b>File Extension / Page Allocation:</b> Appends zero-filled 4 KB blocks to the file when
 *       allocating new pages, maintaining accurate track of total allocated pages.</li>
 *   <li><b>Resource Lifecycle:</b> Controls open/close states to manage OS file handlers safely.</li>
 * </ul>
 */
public class DiskManager {
    /**
     * Physical file accessor configured for read-write ("rw") direct access.
     */
    private RandomAccessFile file;

    /**
     * Total number of pages stored in the open database file.
     */
    private int numPages;

    /**
     * Opens a database file at the specified file path for reading and writing.
     * <p>
     * Initializes the underlying {@link RandomAccessFile} and computes the total page count
     * based on file length divided by {@link Page#PAGE_SIZE}.
     * </p>
     *
     * @param path file system path to the database file
     * @throws IOException if an I/O error occurs while opening or querying file length
     */
    public void open(String path) throws IOException {
        file = new RandomAccessFile(path, "rw");
        numPages = (int) (file.length() / Page.PAGE_SIZE);
    }

    /**
     * Reads a page from disk into an in-memory {@link Page} object.
     * <p>
     * Seeks to offset {@code (long) pageNumber * PAGE_SIZE} and reads exactly {@link Page#PAGE_SIZE}
     * bytes from disk.
     * </p>
     *
     * @param pageNumber 0-indexed page identifier to read
     * @return a new {@link Page} initialized with the data read from disk
     * @throws IOException              if an I/O error occurs during disk seek or read
     * @throws IllegalArgumentException if {@code pageNumber} is negative or &gt;= {@code numPages}
     * @throws IllegalStateException    if {@code DiskManager} has not been opened
     */
    public Page readPage(int pageNumber) throws IOException {
        checkOpen();
        if (pageNumber < 0 || pageNumber >= numPages) {
            throw new IllegalArgumentException("Invalid page number: " + pageNumber);
        }
        byte[] buffer = new byte[Page.PAGE_SIZE];
        file.seek((long) pageNumber * Page.PAGE_SIZE);
        file.readFully(buffer);
        return new Page(pageNumber, buffer);
    }

    /**
     * Writes the contents of an in-memory {@link Page} back to disk and clears its dirty flag.
     * <p>
     * Seeks to offset {@code (long) page.getPageNumber() * PAGE_SIZE} and writes the full 4096-byte array.
     * On successful write, {@link Page#clearDirty()} is invoked to mark the page clean.
     * </p>
     *
     * @param page the {@link Page} instance to persist
     * @throws IOException           if an I/O error occurs during disk seek or write
     * @throws IllegalStateException if {@code DiskManager} has not been opened
     */
    public void writePage(Page page) throws IOException {
        checkOpen();
        file.seek((long) page.getPageNumber() * Page.PAGE_SIZE);
        file.write(page.getData());
        page.clearDirty();
    }

    /**
     * Allocates a new page at the end of the database file.
     * <p>
     * Extends the physical file by seeking to the current file end ({@code numPages * PAGE_SIZE})
     * and writing a zero-filled 4096-byte block. Increments {@code numPages} and returns the new page ID.
     * </p>
     *
     * @return 0-indexed page number of the newly allocated page
     * @throws IOException           if an I/O error occurs while extending the file
     * @throws IllegalStateException if {@code DiskManager} has not been opened
     */
    public int allocatePage() throws IOException {
        checkOpen();
        int newPageNumber = numPages;
        // Extend the file by one page of zero bytes
        file.seek((long) newPageNumber * Page.PAGE_SIZE);
        file.write(new byte[Page.PAGE_SIZE]);
        numPages++;
        return newPageNumber;
    }

    /**
     * Gets the total number of pages currently allocated in the database file.
     *
     * @return total page count
     */
    public int getNumPages() {
        return numPages;
    }

    /**
     * Closes the database file handle and releases OS resources.
     * Safe to call multiple times.
     *
     * @throws IOException if an error occurs while closing the file handle
     */
    public void close() throws IOException {
        if (file != null) {
            file.close();
            file = null;
        }
    }

    /**
     * Internal helper method validating that {@code DiskManager} is open before file operations.
     *
     * @throws IllegalStateException if {@code file} handle is null
     */
    private void checkOpen() {
        if (file == null) {
            throw new IllegalStateException("DiskManager is not open. Call open() first.");
        }
    }
}

