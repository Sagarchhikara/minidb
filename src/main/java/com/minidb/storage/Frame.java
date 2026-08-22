package com.minidb.storage;

/**
 * Represents a cached page frame inside the buffer pool.
 *
 * Tracks the page data along with:
 * - dirty: true if the page has been modified since it was loaded from disk
 * - pinCount: number of active readers/writers currently using the page (pinned pages cannot be evicted)
 *
 * Dirtiness is stored on the Page, not duplicated here: Page marks itself on every
 * putInt/putBytes, so a caller that mutates a page but forgets to pass dirtied=true
 * to unpin still gets its write flushed instead of silently losing it.
 */
public class Frame {
    private final int pageNum;
    private final Page page;
    private int pinCount;

    public Frame(int pageNum, Page page, boolean dirty, int pinCount) {
        this.pageNum = pageNum;
        this.page = page;
        this.pinCount = pinCount;
        setDirty(dirty);
    }

    public int getPageNum() {
        return pageNum;
    }

    public Page getPage() {
        return page;
    }

    public boolean isDirty() {
        return page.isDirty();
    }

    public void setDirty(boolean dirty) {
        if (dirty) {
            page.markDirty();
        } else {
            page.clearDirty();
        }
    }

    public int getPinCount() {
        return pinCount;
    }

    public void incrementPin() {
        this.pinCount++;
    }

    public void decrementPin() {
        if (pinCount <= 0) {
            throw new IllegalStateException("pinCount is already 0 for page " + pageNum);
        }
        this.pinCount--;
    }
}
