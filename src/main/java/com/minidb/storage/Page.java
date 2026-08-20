package com.minidb.storage;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class Page {
    public static final int PAGE_SIZE = 4096;

    private final int pageNumber;
    private final byte[] data;
    private boolean dirty;

    public Page(int pageNumber) {
        this.pageNumber = pageNumber;
        this.data = new byte[PAGE_SIZE];
        this.dirty = false;
    }

    // Used when loading raw bytes off disk into a Page
    public Page(int pageNumber, byte[] data) {
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException("Page data must be exactly " + PAGE_SIZE + " bytes");
        }
        this.pageNumber = pageNumber;
        this.data = data;
        this.dirty = false;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public byte[] getData() {
        return data;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    public int getInt(int offset) {
        checkBounds(offset, Integer.BYTES);
        return ByteBuffer.wrap(data).getInt(offset);
    }

    public void putInt(int offset, int value) {
        checkBounds(offset, Integer.BYTES);
        ByteBuffer.wrap(data).putInt(offset, value);
        markDirty();
    }

    public byte[] getBytes(int offset, int length) {
        checkBounds(offset, length);
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    public void putBytes(int offset, byte[] src) {
        checkBounds(offset, src.length);
        System.arraycopy(src, 0, data, offset, src.length);
        markDirty();
    }

    private void checkBounds(int offset, int length) {
        if (offset < 0 || offset + length > PAGE_SIZE) {
            throw new IndexOutOfBoundsException(
                    "Access [" + offset + ", " + (offset + length) + ") out of bounds for page size " + PAGE_SIZE);
        }
    }
}