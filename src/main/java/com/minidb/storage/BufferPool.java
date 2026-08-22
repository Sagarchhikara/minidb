package com.minidb.storage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory cache of fixed capacity (in frames) placed in front of DiskManager.
 *
 * Enforces the buffer pool protocol:
 * - Every fetchPage increments pinCount and must be paired with exactly one unpin.
 * - Dirty frames are flushed to disk before eviction and upon flushAll/close.
 * - Pinned frames (pinCount > 0) are protected from eviction.
 */
public class BufferPool {
    private final DiskManager disk;
    private final int capacity;
    private final Map<Integer, Frame> table = new HashMap<>();
    private final EvictionPolicy policy;

    private int hits = 0;
    private int misses = 0;

    public BufferPool(DiskManager disk, int capacity) {
        this(disk, capacity, new LruPolicy());
    }

    public BufferPool(DiskManager disk, int capacity, EvictionPolicy policy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.disk = disk;
        this.capacity = capacity;
        this.policy = policy;
    }

    /**
     * Fetches a page from the buffer pool or loads it from disk if not cached.
     * Increments the frame's pinCount. The caller MUST call unpin() when done.
     */
    public synchronized Page fetchPage(int pageNum) throws IOException {
        Frame f = table.get(pageNum);
        if (f != null) {
            // HIT
            f.incrementPin();
            policy.touch(pageNum);
            hits++;
            return f.getPage();
        }

        // MISS
        misses++;
        if (table.size() >= capacity) {
            evictOne();
        }
        Page page = disk.readPage(pageNum);
        Frame nf = new Frame(pageNum, page, false, 1);
        table.put(pageNum, nf);
        policy.recordInsert(pageNum);
        return nf.getPage();
    }

    /**
     * Releases a pin on a page frame, optionally marking it dirty if written to.
     */
    public synchronized void unpin(int pageNum, boolean dirtied) {
        Frame f = table.get(pageNum);
        if (f == null) {
            throw new IllegalStateException("Attempted to unpin non-buffered page " + pageNum);
        }
        f.decrementPin();
        if (dirtied) {
            f.setDirty(true);
        }
    }

    /**
     * Selects and evicts an unpinned frame according to the eviction policy.
     * Flushes dirty data to disk before dropping the frame.
     */
    public synchronized void evictOne() throws IOException {
        Integer victimPage = policy.findVictim(p -> {
            Frame f = table.get(p);
            return f != null && f.getPinCount() == 0;
        });

        if (victimPage == null) {
            throw new IllegalStateException("Cannot evict page: all " + table.size() + " frames are pinned");
        }

        Frame f = table.get(victimPage);
        if (f.isDirty()) {
            disk.writePage(f.getPage());
        }
        table.remove(victimPage);
        policy.remove(victimPage);
    }

    /**
     * Flushes all dirty frames currently in the buffer pool to disk.
     */
    public synchronized void flushAll() throws IOException {
        for (Frame f : table.values()) {
            if (f.isDirty()) {
                disk.writePage(f.getPage());
                f.setDirty(false);
            }
        }
    }

    /**
     * Flushes a specific page if dirty.
     */
    public synchronized void flushPage(int pageNum) throws IOException {
        Frame f = table.get(pageNum);
        if (f != null && f.isDirty()) {
            disk.writePage(f.getPage());
            f.setDirty(false);
        }
    }

    public synchronized int getHits() {
        return hits;
    }

    public synchronized int getMisses() {
        return misses;
    }

    public synchronized int getCapacity() {
        return capacity;
    }

    public synchronized int getNumFrames() {
        return table.size();
    }

    public synchronized boolean isCached(int pageNum) {
        return table.containsKey(pageNum);
    }

    public synchronized Frame getFrame(int pageNum) {
        return table.get(pageNum);
    }

    /**
     * Tripwire for pin leaks: throws if any frame is still pinned.
     *
     * Every fetchPage must be paired with exactly one unpin, so at a quiescent point
     * (end of a query, end of a test) no frame should hold a pin. A leaked pin is
     * silent until the pool fills up and evictOne finds nothing evictable, which
     * surfaces the failure arbitrarily far from the operator that caused it.
     */
    public synchronized void assertNoPinnedFrames() {
        StringBuilder leaked = new StringBuilder();
        int count = 0;
        for (Frame f : table.values()) {
            if (f.getPinCount() > 0) {
                if (count++ > 0) {
                    leaked.append(", ");
                }
                leaked.append("page ").append(f.getPageNum()).append(" pinCount=").append(f.getPinCount());
            }
        }
        if (count > 0) {
            throw new IllegalStateException("Pin leak: " + count + " frame(s) still pinned -> " + leaked);
        }
    }

    public DiskManager getDisk() {
        return disk;
    }
}
