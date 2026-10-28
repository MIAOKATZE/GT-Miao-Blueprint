package com.miaokatze.gtmb.client.hologram;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

/** Immutable AVL directory used only by local document render pages, memberships and spatial regions. */
final class HologramDocumentIndex<K, V> extends AbstractMap<K, V> {

    private final Comparator<K> order;
    private final Function<V, int[]> extent;
    private final Node<K, V> root;
    final int copiedNodes;

    HologramDocumentIndex(Comparator<K> order) {
        this(order, null);
    }

    HologramDocumentIndex(Comparator<K> order, Function<V, int[]> extent) {
        this(order, extent, null, 0);
    }

    private HologramDocumentIndex(Comparator<K> order, Function<V, int[]> extent, Node<K, V> root, int copied) {
        this.order = order;
        this.extent = extent;
        this.root = root;
        copiedNodes = copied;
    }

    HologramDocumentIndex<K, V> with(K key, V value) {
        int[] copied = { 0 };
        Node<K, V> next = replace(root, key, value, copied);
        return next == root ? this : new HologramDocumentIndex<>(order, extent, next, copied[0]);
    }

    @Override
    public int size() {
        return size(root);
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get(Object key) {
        Node<K, V> node = root;
        while (node != null) {
            int compare = order.compare((K) key, node.key);
            if (compare == 0) return node.value;
            node = compare < 0 ? node.left : node.right;
        }
        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    V at(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
        Node<K, V> node = root;
        while (node != null) {
            int left = size(node.left);
            if (index == left) return node.value;
            if (index < left) node = node.left;
            else {
                index -= left + 1;
                node = node.right;
            }
        }
        throw new AssertionError("Missing document index rank");
    }

    int[] bounds() {
        return root == null
            ? new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE }
            : root.bounds.clone();
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return Collections.unmodifiableSet(new AbstractSet<Map.Entry<K, V>>() {

            @Override
            public int size() {
                return HologramDocumentIndex.this.size();
            }

            @Override
            public Iterator<Map.Entry<K, V>> iterator() {
                return new Iterator<Map.Entry<K, V>>() {

                    final Deque<Node<K, V>> stack = new ArrayDeque<>();
                    {
                        push(root);
                    }

                    private void push(Node<K, V> node) {
                        while (node != null) {
                            stack.push(node);
                            node = node.left;
                        }
                    }

                    public boolean hasNext() {
                        return !stack.isEmpty();
                    }

                    public Map.Entry<K, V> next() {
                        if (stack.isEmpty()) throw new NoSuchElementException();
                        Node<K, V> node = stack.pop();
                        push(node.right);
                        return new SimpleImmutableEntry<>(node.key, node.value);
                    }

                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        });
    }

    private Node<K, V> replace(Node<K, V> node, K key, V value, int[] copied) {
        if (node == null) return value == null ? null : make(key, value, null, null, copied);
        int compare = order.compare(key, node.key);
        if (compare == 0) {
            if (value == node.value) return node;
            if (value != null) return make(key, value, node.left, node.right, copied);
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;
            Node<K, V> successor = node.right;
            while (successor.left != null) successor = successor.left;
            return balance(
                make(
                    successor.key,
                    successor.value,
                    node.left,
                    replace(node.right, successor.key, null, copied),
                    copied),
                copied);
        }
        Node<K, V> child = replace(compare < 0 ? node.left : node.right, key, value, copied);
        if (child == (compare < 0 ? node.left : node.right)) return node;
        return balance(
            make(node.key, node.value, compare < 0 ? child : node.left, compare > 0 ? child : node.right, copied),
            copied);
    }

    private Node<K, V> balance(Node<K, V> node, int[] copied) {
        int difference = height(node.left) - height(node.right);
        if (difference > 1) {
            Node<K, V> left = node.left;
            if (height(left.left) < height(left.right)) left = rotateLeft(left, copied);
            return rotateRight(make(node.key, node.value, left, node.right, copied), copied);
        }
        if (difference < -1) {
            Node<K, V> right = node.right;
            if (height(right.right) < height(right.left)) right = rotateRight(right, copied);
            return rotateLeft(make(node.key, node.value, node.left, right, copied), copied);
        }
        return node;
    }

    private Node<K, V> rotateLeft(Node<K, V> node, int[] copied) {
        Node<K, V> top = node.right;
        return make(top.key, top.value, make(node.key, node.value, node.left, top.left, copied), top.right, copied);
    }

    private Node<K, V> rotateRight(Node<K, V> node, int[] copied) {
        Node<K, V> top = node.left;
        return make(top.key, top.value, top.left, make(node.key, node.value, top.right, node.right, copied), copied);
    }

    private Node<K, V> make(K key, V value, Node<K, V> left, Node<K, V> right, int[] copied) {
        copied[0]++;
        return new Node<>(key, value, left, right, extent == null ? null : extent.apply(value));
    }

    private static int height(Node<?, ?> node) {
        return node == null ? 0 : node.height;
    }

    private static int size(Node<?, ?> node) {
        return node == null ? 0 : node.size;
    }

    private static final class Node<K, V> {

        final K key;
        final V value;
        final Node<K, V> left, right;
        final int height, size;
        final int[] bounds;

        Node(K key, V value, Node<K, V> left, Node<K, V> right, int[] ownBounds) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            height = 1 + Math.max(height(left), height(right));
            size = 1 + size(left) + size(right);
            bounds = ownBounds == null ? null : ownBounds.clone();
            if (bounds != null) for (int axis = 0; axis < 3; axis++) {
                if (left != null) {
                    bounds[axis] = Math.min(bounds[axis], left.bounds[axis]);
                    bounds[axis + 3] = Math.max(bounds[axis + 3], left.bounds[axis + 3]);
                }
                if (right != null) {
                    bounds[axis] = Math.min(bounds[axis], right.bounds[axis]);
                    bounds[axis + 3] = Math.max(bounds[axis + 3], right.bounds[axis + 3]);
                }
            }
        }
    }
}
