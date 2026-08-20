package com.minidb.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Least-Recently Used (LRU) eviction policy implemented with a hand-rolled
 * doubly linked list and hash map for O(1) touches and removals.
 *
 * head.next = Least-Recently Used (first candidate to evict)
 * tail.prev = Most-Recently Used
 */
public class LruPolicy implements EvictionPolicy {
    private static class Node {
        final int pageNum;
        Node prev;
        Node next;

        Node(int pageNum) {
            this.pageNum = pageNum;
        }
    }

    private final Map<Integer, Node> map = new HashMap<>();
    private final Node head;
    private final Node tail;

    public LruPolicy() {
        this.head = new Node(-1);
        this.tail = new Node(-1);
        this.head.next = this.tail;
        this.tail.prev = this.head;
    }

    @Override
    public void recordInsert(int pageNum) {
        Node existing = map.get(pageNum);
        if (existing != null) {
            detach(existing);
        }
        Node node = new Node(pageNum);
        map.put(pageNum, node);
        addLast(node);
    }

    @Override
    public void touch(int pageNum) {
        Node node = map.get(pageNum);
        if (node != null) {
            detach(node);
            addLast(node);
        }
    }

    @Override
    public void remove(int pageNum) {
        Node node = map.remove(pageNum);
        if (node != null) {
            detach(node);
        }
    }

    @Override
    public Integer findVictim(Predicate<Integer> canEvict) {
        Node curr = head.next;
        while (curr != tail) {
            if (canEvict.test(curr.pageNum)) {
                return curr.pageNum;
            }
            curr = curr.next;
        }
        return null;
    }

    private void detach(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void addLast(Node node) {
        node.prev = tail.prev;
        node.next = tail;
        tail.prev.next = node;
        tail.prev = node;
    }
}
