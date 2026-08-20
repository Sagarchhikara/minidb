package com.minidb.storage;

import java.io.IOException;
import java.io.RandomAccessFile;

public class DiskManager {
    private RandomAccessFile file;
    private int numPages;

    public void open(String path) throws IOException {
        file = new RandomAccessFile(path, "rw");
        numPages = (int) (file.length() / Page.PAGE_SIZE);
    }

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

    public void writePage(Page page) throws IOException {
        checkOpen();
        file.seek((long) page.getPageNumber() * Page.PAGE_SIZE);
        file.write(page.getData());
        page.clearDirty();
    }

    public int allocatePage() throws IOException {
        checkOpen();
        int newPageNumber = numPages;
        // Extend the file by one page of zero bytes
        file.seek((long) newPageNumber * Page.PAGE_SIZE);
        file.write(new byte[Page.PAGE_SIZE]);
        numPages++;
        return newPageNumber;
    }

    public int getNumPages() {
        return numPages;
    }

    public void close() throws IOException {
        if (file != null) {
            file.close();
            file = null;
        }
    }

    private void checkOpen() {
        if (file == null) {
            throw new IllegalStateException("DiskManager is not open. Call open() first.");
        }
    }
}