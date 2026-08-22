package com.minidb.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An in-memory B+Tree secondary index mapping integer keys to Rids.
 *
 * Invariants:
 * 1. Leaves store all (key, rid) pairs and are linked from left to right via `next`.
 * 2. Internal nodes store only routing keys (separators) and child pointers.
 * 3. An internal node with k keys has exactly k + 1 children.
 * 4. Tree height is balanced (all leaves are at the exact same depth).
 * 5. Leaf split: copy-up (the right child's smallest key is copied up; leaves retain all keys).
 * 6. Internal split: push-up (the middle key moves up and is excluded from both halves).
 */
public class BPlusTree {
    /**
     * Fanout for a tree built without an explicit order.
     *
     * Stage 6 used 3 to force splits at every level during testing. That is a terrible
     * benchmarking fanout: at 1M keys it gives a height near 13 with a tiny Java object
     * per node, so a lookup is 13 pointer-chases with no locality and the measurement
     * understates the index badly. Real B+Trees run fanouts in the hundreds.
     *
     * Tests that specifically want frequent splits still pass 3 explicitly.
     */
    public static final int DEFAULT_MAX_KEYS = 128;

    private final int maxKeys;
    private Node root;

    public sealed interface Node permits Leaf, Internal {
    }

    public static final class Leaf implements Node {
        public final List<Integer> keys = new ArrayList<>();
        public final List<Rid> rids = new ArrayList<>();
        public Leaf next;

        public int size() {
            return keys.size();
        }
    }

    public static final class Internal implements Node {
        public final List<Integer> keys = new ArrayList<>();
        public final List<Node> children = new ArrayList<>();

        public int keyCount() {
            return keys.size();
        }
    }

    public record Split(int key, Node right) {
    }

    public BPlusTree() {
        this(DEFAULT_MAX_KEYS);
    }

    public BPlusTree(int maxKeys) {
        if (maxKeys < 3) {
            throw new IllegalArgumentException("maxKeys must be at least 3");
        }
        this.maxKeys = maxKeys;
        this.root = null;
    }

    public Node getRoot() {
        return root;
    }

    public void setRoot(Node root) {
        this.root = root;
    }

    public int getMaxKeys() {
        return maxKeys;
    }

    /**
     * Finds the index of the child to descend to for a given search key.
     * Rule: first separator strictly greater than key -> that child;
     * if key >= all separators -> last child.
     */
    public static int childIndex(Internal n, int key) {
        for (int i = 0; i < n.keys.size(); i++) {
            if (n.keys.get(i) > key) {
                return i;
            }
        }
        return n.keys.size();
    }

    /**
     * Point lookup: descends from root to leaf and finds the Rid for the given key.
     * Returns null if key is not found.
     */
    public Rid search(int key) {
        if (root == null) {
            return null;
        }
        Node curr = root;
        while (curr instanceof Internal n) {
            int idx = childIndex(n, key);
            curr = n.children.get(idx);
        }
        Leaf leaf = (Leaf) curr;
        int idx = Collections.binarySearch(leaf.keys, key);
        if (idx >= 0) {
            return leaf.rids.get(idx);
        }
        return null;
    }

    /**
     * Inserts a (key, rid) mapping. Overwrites the Rid if key is already present.
     */
    public void insert(int key, Rid rid) {
        if (root == null) {
            Leaf leaf = new Leaf();
            leaf.keys.add(key);
            leaf.rids.add(rid);
            root = leaf;
            return;
        }

        Split s = insert(root, key, rid);
        if (s != null) {
            // Root split: tree height grows by 1
            Internal newRoot = new Internal();
            newRoot.keys.add(s.key);
            newRoot.children.add(root);
            newRoot.children.add(s.right);
            root = newRoot;
        }
    }

    private Split insert(Node node, int key, Rid rid) {
        if (node instanceof Leaf leaf) {
            int idx = Collections.binarySearch(leaf.keys, key);
            if (idx >= 0) {
                // Key already exists -> overwrite RID (unique key assumption)
                leaf.rids.set(idx, rid);
                return null;
            }
            int insertPos = -idx - 1;
            leaf.keys.add(insertPos, key);
            leaf.rids.add(insertPos, rid);
            return leaf.size() <= maxKeys ? null : splitLeaf(leaf);
        }

        Internal n = (Internal) node;
        int idx = childIndex(n, key);
        Split s = insert(n.children.get(idx), key, rid);
        if (s == null) {
            return null;
        }

        // Child split: absorb separator and right child into this internal node
        n.keys.add(idx, s.key);
        n.children.add(idx + 1, s.right);
        return n.keyCount() <= maxKeys ? null : splitInternal(n);
    }

    /**
     * Leaf split -> COPY UP.
     * Nothing is removed from leaf data; the right child's smallest key is copied up.
     */
    public Split splitLeaf(Leaf leaf) {
        int mid = (leaf.size() + 1) / 2;
        Leaf right = new Leaf();

        // Move entries [mid .. end] into right; leaf retains [0 .. mid - 1]
        for (int i = mid; i < leaf.keys.size(); i++) {
            right.keys.add(leaf.keys.get(i));
            right.rids.add(leaf.rids.get(i));
        }
        while (leaf.keys.size() > mid) {
            leaf.keys.remove(leaf.keys.size() - 1);
            leaf.rids.remove(leaf.rids.size() - 1);
        }

        right.next = leaf.next;
        leaf.next = right;

        // COPY: right keeps keys[0] too
        return new Split(right.keys.get(0), right);
    }

    /**
     * Internal split -> PUSH UP.
     * The middle key is moved up and excluded from both left and right nodes.
     */
    public Split splitInternal(Internal n) {
        int mid = n.keyCount() / 2;
        int promoted = n.keys.get(mid); // MOVES up — excluded from both halves

        Internal right = new Internal();

        // right.keys = keys[mid+1 .. end]
        for (int i = mid + 1; i < n.keys.size(); i++) {
            right.keys.add(n.keys.get(i));
        }
        // right.children = children[mid+1 .. end]
        for (int i = mid + 1; i < n.children.size(); i++) {
            right.children.add(n.children.get(i));
        }

        // n.keys = keys[0 .. mid-1]
        while (n.keys.size() > mid) {
            n.keys.remove(n.keys.size() - 1);
        }
        // n.children = children[0 .. mid]
        while (n.children.size() > mid + 1) {
            n.children.remove(n.children.size() - 1);
        }

        return new Split(promoted, right);
    }

    /**
     * Root-to-leaf depth, counting the leaf. 0 for an empty tree.
     *
     * Cheap (one descent) unlike validate(), which also returns the height but walks
     * every node — cost estimation must not pay for a full validation.
     */
    public int height() {
        int h = 0;
        Node curr = root;
        while (curr != null) {
            h++;
            if (curr instanceof Internal n) {
                curr = n.children.get(0);
            } else {
                break;
            }
        }
        return h;
    }

    /**
     * Returns the leftmost leaf of the B+Tree.
     */
    public Leaf getLeftmostLeaf() {
        if (root == null) {
            return null;
        }
        Node curr = root;
        while (curr instanceof Internal n) {
            curr = n.children.get(0);
        }
        return (Leaf) curr;
    }

    /**
     * Traverses the leaf next chain and returns all keys in order.
     */
    public List<Integer> getAllKeysFromLeafChain() {
        List<Integer> result = new ArrayList<>();
        Leaf curr = getLeftmostLeaf();
        while (curr != null) {
            result.addAll(curr.keys);
            curr = curr.next;
        }
        return result;
    }

    /**
     * Validates all tree invariants:
     * - Keys strictly ascending in every node
     * - Subtree keys respect (min, max) bounds from separators
     * - Internal nodes have children.size() == keys.size() + 1
     * - All leaf paths have the same height (balance)
     * - Leaf next chain matches all keys in ascending order
     *
     * Returns tree height.
     */
    public int validate() {
        if (root == null) {
            return 0;
        }

        int height = validateSubtree(root, null, null, true);

        // Verify leaf next chain
        Leaf curr = getLeftmostLeaf();
        List<Integer> chainKeys = new ArrayList<>();
        while (curr != null) {
            for (int k : curr.keys) {
                if (!chainKeys.isEmpty() && k <= chainKeys.get(chainKeys.size() - 1)) {
                    throw new IllegalStateException("Leaf chain not strictly ascending at key " + k);
                }
                chainKeys.add(k);
            }
            curr = curr.next;
        }

        int treeLeafKeys = countLeafKeys(root);
        if (chainKeys.size() != treeLeafKeys) {
            throw new IllegalStateException(
                    "Leaf chain key count (" + chainKeys.size() + ") != tree leaf keys (" + treeLeafKeys + ")");
        }

        return height;
    }

    private int validateSubtree(Node node, Integer minKey, Integer maxKey, boolean isRoot) {
        if (node instanceof Leaf leaf) {
            if (leaf.keys.size() != leaf.rids.size()) {
                throw new IllegalStateException("Leaf keys and rids size mismatch");
            }
            for (int i = 0; i < leaf.keys.size(); i++) {
                int k = leaf.keys.get(i);
                if (i > 0 && leaf.keys.get(i - 1) >= k) {
                    throw new IllegalStateException("Leaf keys not strictly ascending: " + leaf.keys);
                }
                if (minKey != null && k < minKey) {
                    throw new IllegalStateException("Leaf key " + k + " violates minKey " + minKey);
                }
                if (maxKey != null && k >= maxKey) {
                    throw new IllegalStateException("Leaf key " + k + " violates maxKey " + maxKey);
                }
            }
            return 1;
        }

        Internal internal = (Internal) node;
        if (internal.children.size() != internal.keys.size() + 1) {
            throw new IllegalStateException(
                    "Internal children size (" + internal.children.size() + ") != keys + 1 (" + (internal.keys.size() + 1) + ")");
        }
        for (int i = 0; i < internal.keys.size(); i++) {
            int k = internal.keys.get(i);
            if (i > 0 && internal.keys.get(i - 1) >= k) {
                throw new IllegalStateException("Internal keys not strictly ascending: " + internal.keys);
            }
            if (minKey != null && k < minKey) {
                throw new IllegalStateException("Internal key " + k + " violates minKey " + minKey);
            }
            if (maxKey != null && k >= maxKey) {
                throw new IllegalStateException("Internal key " + k + " violates maxKey " + maxKey);
            }
        }

        int childHeight = -1;
        for (int i = 0; i < internal.children.size(); i++) {
            Integer childMin = (i == 0) ? minKey : internal.keys.get(i - 1);
            Integer childMax = (i == internal.keys.size()) ? maxKey : internal.keys.get(i);
            int h = validateSubtree(internal.children.get(i), childMin, childMax, false);
            if (childHeight == -1) {
                childHeight = h;
            } else if (childHeight != h) {
                throw new IllegalStateException("Tree unbalanced: child " + i + " height " + h + " != " + childHeight);
            }
        }

        return childHeight + 1;
    }

    private int countLeafKeys(Node node) {
        if (node instanceof Leaf leaf) {
            return leaf.keys.size();
        }
        Internal internal = (Internal) node;
        int count = 0;
        for (Node child : internal.children) {
            count += countLeafKeys(child);
        }
        return count;
    }
}
