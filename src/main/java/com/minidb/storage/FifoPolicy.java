package com.minidb.storage;

import java.util.LinkedHashSet;
import java.util.function.Predicate;

/**
 * First-In First-Out (FIFO) eviction policy.
 *
 * Pages are evicted strictly in the order they were inserted, completely
 * ignoring page hits (touch is a no-op).
 */
public class FifoPolicy implements EvictionPolicy {
    private final LinkedHashSet<Integer> order = new LinkedHashSet<>();

    @Override
    public void recordInsert(int pageNum) {
        order.add(pageNum);
    }

    @Override
    public void touch(int pageNum) {
        // FIFO intentionally ignores hits
    }

    @Override
    public void remove(int pageNum) {
        order.remove(pageNum);
    }

    @Override
    public Integer findVictim(Predicate<Integer> canEvict) {
        for (int p : order) {
            if (canEvict.test(p)) {
                return p;
            }
        }
        return null;
    }
}
