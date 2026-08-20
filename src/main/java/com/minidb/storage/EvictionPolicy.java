package com.minidb.storage;

import java.util.function.Predicate;

/**
 * Strategy interface for buffer pool page eviction algorithms (e.g. LRU, FIFO).
 */
public interface EvictionPolicy {
    /** Called when a page enters the buffer pool. */
    void recordInsert(int pageNum);

    /** Called when a buffered page is accessed (hit). */
    void touch(int pageNum);

    /** Called when a page is removed/evicted from the buffer pool. */
    void remove(int pageNum);

    /**
     * Finds the next eviction victim among unpinned candidate pages.
     * Returns null if no unpinned candidate is available.
     */
    Integer findVictim(Predicate<Integer> canEvict);
}
