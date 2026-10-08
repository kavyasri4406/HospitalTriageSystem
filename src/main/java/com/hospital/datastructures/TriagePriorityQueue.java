package com.hospital.datastructures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * Indexed binary max-heap written from scratch.
 *
 * <p>Unlike {@link java.util.PriorityQueue}, it keeps a {@link HashMap} from each
 * element's key to its position in the heap array, which allows:
 * <ul>
 *   <li>{@code offer} / {@code poll} in O(log n)</li>
 *   <li>{@code peek}, {@code contains}, {@code get} in O(1)</li>
 *   <li>{@code remove(key)} of an arbitrary element in O(log n) (e.g. "admit this patient now")</li>
 *   <li>{@code update(key)} after an element's priority changed in O(log n)</li>
 *   <li>{@code rebuild()} after every priority changed (wait-time aging) in O(n) via Floyd's heapify</li>
 * </ul>
 *
 * @param <K> key type (e.g. patient id)
 * @param <T> element type
 */
public class TriagePriorityQueue<K, T> {

    private final List<T> heap = new ArrayList<>();
    private final Map<K, Integer> positions = new HashMap<>();
    private final Comparator<? super T> priority;
    private final Function<? super T, K> keyOf;

    /**
     * @param priority comparator where a <b>greater</b> element has <b>higher</b> priority
     * @param keyOf    extracts the unique key of an element
     */
    public TriagePriorityQueue(Comparator<? super T> priority, Function<? super T, K> keyOf) {
        this.priority = priority;
        this.keyOf = keyOf;
    }

    public void offer(T item) {
        K key = keyOf.apply(item);
        if (positions.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate key in queue: " + key);
        }
        heap.add(item);
        positions.put(key, heap.size() - 1);
        siftUp(heap.size() - 1);
    }

    public T peek() {
        return heap.isEmpty() ? null : heap.get(0);
    }

    public T poll() {
        if (heap.isEmpty()) return null;
        return removeAt(0);
    }

    public T remove(K key) {
        Integer index = positions.get(key);
        if (index == null) throw new NoSuchElementException("Not in queue: " + key);
        return removeAt(index);
    }

    /** Restores heap order for one element whose priority has changed. */
    public void update(K key) {
        Integer index = positions.get(key);
        if (index == null) throw new NoSuchElementException("Not in queue: " + key);
        siftUp(index);
        siftDown(positions.get(key));
    }

    /** Re-heapifies everything in O(n). Call after all priorities were recalculated. */
    public void rebuild() {
        for (int i = heap.size() / 2 - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    public boolean contains(K key) { return positions.containsKey(key); }

    public T get(K key) {
        Integer index = positions.get(key);
        return index == null ? null : heap.get(index);
    }

    public int size() { return heap.size(); }

    public boolean isEmpty() { return heap.isEmpty(); }

    public void clear() {
        heap.clear();
        positions.clear();
    }

    /** Unordered view of the elements (heap array order). */
    public List<T> elements() {
        return Collections.unmodifiableList(heap);
    }

    /** Copy of all elements in priority order (highest first). Does not modify the heap. */
    public List<T> toSortedList() {
        List<T> copy = new ArrayList<>(heap);
        copy.sort(priority.reversed());
        return copy;
    }

    /** Checks the max-heap invariant; used by the self-tests. */
    public boolean isValidHeap() {
        for (int i = 1; i < heap.size(); i++) {
            if (higher(heap.get(i), heap.get(parent(i)))) return false;
        }
        for (int i = 0; i < heap.size(); i++) {
            if (positions.get(keyOf.apply(heap.get(i))) != i) return false;
        }
        return positions.size() == heap.size();
    }

    // ---- internals -------------------------------------------------------------

    private T removeAt(int index) {
        T removed = heap.get(index);
        int last = heap.size() - 1;
        swap(index, last);
        heap.remove(last);
        positions.remove(keyOf.apply(removed));
        if (index < heap.size()) {
            // The former last element now sits at 'index'; move it up or down as needed.
            T moved = heap.get(index);
            siftUp(index);
            siftDown(positions.get(keyOf.apply(moved)));
        }
        return removed;
    }

    private void siftUp(int i) {
        while (i > 0) {
            int p = parent(i);
            if (!higher(heap.get(i), heap.get(p))) break;
            swap(i, p);
            i = p;
        }
    }

    private void siftDown(int i) {
        int n = heap.size();
        while (true) {
            int left = 2 * i + 1;
            int right = left + 1;
            int largest = i;
            if (left < n && higher(heap.get(left), heap.get(largest))) largest = left;
            if (right < n && higher(heap.get(right), heap.get(largest))) largest = right;
            if (largest == i) return;
            swap(i, largest);
            i = largest;
        }
    }

    private boolean higher(T a, T b) {
        return priority.compare(a, b) > 0;
    }

    private void swap(int i, int j) {
        if (i == j) return;
        T a = heap.get(i);
        T b = heap.get(j);
        heap.set(i, b);
        heap.set(j, a);
        positions.put(keyOf.apply(b), i);
        positions.put(keyOf.apply(a), j);
    }

    private static int parent(int i) { return (i - 1) / 2; }
}
