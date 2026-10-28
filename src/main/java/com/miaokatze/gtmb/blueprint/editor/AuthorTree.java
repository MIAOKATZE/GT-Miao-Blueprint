package com.miaokatze.gtmb.blueprint.editor;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Persistent AVL index. Updating a key copies only its logarithmic search path. */
final class AuthorTree<K extends Comparable<K>, V> implements Iterable<AuthorTree.Entry<K, V>> {

    static final class Entry<K, V> {

        final K key;
        final V value;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class Node<K, V> {

        final K key;
        final V value;
        final Node<K, V> left, right;
        final int height;

        Node(K key, V value, Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            height = 1 + Math.max(height(left), height(right));
        }
    }

    private final Node<K, V> root;

    AuthorTree() {
        this(null);
    }

    private AuthorTree(Node<K, V> root) {
        this.root = root;
    }

    static <K extends Comparable<K>, V> AuthorTree<K, V> fromSorted(java.util.List<Entry<K, V>> entries) {
        for (int i = 1; i < entries.size(); i++) if (entries.get(i - 1).key.compareTo(entries.get(i).key) >= 0)
            throw new IllegalArgumentException("Index keys must be strictly ordered");
        return new AuthorTree<>(build(entries, 0, entries.size()));
    }

    private static <K, V> Node<K, V> build(java.util.List<Entry<K, V>> entries, int from, int to) {
        if (from == to) return null;
        int middle = (from + to) >>> 1;
        Entry<K, V> value = entries.get(middle);
        return new Node<>(value.key, value.value, build(entries, from, middle), build(entries, middle + 1, to));
    }

    V get(K key) {
        Node<K, V> n = root;
        while (n != null) {
            int c = key.compareTo(n.key);
            if (c == 0) return n.value;
            n = c < 0 ? n.left : n.right;
        }
        return null;
    }

    AuthorTree<K, V> put(K key, V value) {
        return new AuthorTree<>(put(root, key, value));
    }

    AuthorTree<K, V> remove(K key) {
        return new AuthorTree<>(remove(root, key));
    }

    private Node<K, V> put(Node<K, V> n, K key, V value) {
        if (n == null) return new Node<>(key, value, null, null);
        int c = key.compareTo(n.key);
        if (c == 0) return new Node<>(key, value, n.left, n.right);
        return balance(
            c < 0 ? new Node<>(n.key, n.value, put(n.left, key, value), n.right)
                : new Node<>(n.key, n.value, n.left, put(n.right, key, value)));
    }

    private Node<K, V> remove(Node<K, V> n, K key) {
        if (n == null) return null;
        int c = key.compareTo(n.key);
        if (c < 0) return balance(new Node<>(n.key, n.value, remove(n.left, key), n.right));
        if (c > 0) return balance(new Node<>(n.key, n.value, n.left, remove(n.right, key)));
        if (n.left == null) return n.right;
        if (n.right == null) return n.left;
        Node<K, V> successor = n.right;
        while (successor.left != null) successor = successor.left;
        return balance(new Node<>(successor.key, successor.value, n.left, remove(n.right, successor.key)));
    }

    private static int height(Node<?, ?> n) {
        return n == null ? 0 : n.height;
    }

    private Node<K, V> balance(Node<K, V> n) {
        int d = height(n.left) - height(n.right);
        if (d > 1) {
            if (height(n.left.left) < height(n.left.right)) n = new Node<>(n.key, n.value, left(n.left), n.right);
            return right(n);
        }
        if (d < -1) {
            if (height(n.right.right) < height(n.right.left)) n = new Node<>(n.key, n.value, n.left, right(n.right));
            return left(n);
        }
        return n;
    }

    private Node<K, V> left(Node<K, V> n) {
        Node<K, V> r = n.right;
        return new Node<>(r.key, r.value, new Node<>(n.key, n.value, n.left, r.left), r.right);
    }

    private Node<K, V> right(Node<K, V> n) {
        Node<K, V> l = n.left;
        return new Node<>(l.key, l.value, l.left, new Node<>(n.key, n.value, l.right, n.right));
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        return new Iterator<Entry<K, V>>() {

            final ArrayDeque<Node<K, V>> stack = new ArrayDeque<>();
            {
                push(root);
            }

            void push(Node<K, V> n) {
                while (n != null) {
                    stack.push(n);
                    n = n.left;
                }
            }

            public boolean hasNext() {
                return !stack.isEmpty();
            }

            public Entry<K, V> next() {
                if (stack.isEmpty()) throw new NoSuchElementException();
                Node<K, V> n = stack.pop();
                push(n.right);
                return new Entry<>(n.key, n.value);
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
