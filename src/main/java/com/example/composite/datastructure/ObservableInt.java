package com.example.composite.datastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ObservableInt {
    private final AtomicInteger value;
    private final List<ValueChangeListener> listeners = new ArrayList<>();
    private final Lock listenerLock = new ReentrantLock(); // Lock for thread-safe listener management

    public ObservableInt(int initialValue) {
        this.value = new AtomicInteger(initialValue);
    }

    public int get() {
        return value.get();
    }

    public void set(int newValue) {
        int oldValue = value.getAndSet(newValue);
        notifyListeners(oldValue, newValue);
    }

    public void decrement() {
        int oldValue = value.get();
        int newValue = value.decrementAndGet();  // Atomically decrement the value
        notifyListeners(oldValue, newValue);
    }

    public void addValueChangeListener(ValueChangeListener listener) {
        listenerLock.lock(); // Ensure thread-safety when adding listener
        try {
            listeners.add(listener);
        } finally {
            listenerLock.unlock();
        }
    }

    public void removeValueChangeListener(ValueChangeListener listener) {
        listenerLock.lock(); // Ensure thread-safety when removing listener
        try {
            listeners.remove(listener);
        } finally {
            listenerLock.unlock();
        }
    }

    // Cleanup method to remove all listeners safely in a multithreaded context
    public void cleanup() {
        listenerLock.lock(); // Synchronize cleanup to prevent race conditions
        try {
            listeners.clear();
        } finally {
            listenerLock.unlock();
        }
    }

    // Notify listeners, ensuring thread-safety by copying the listeners list
    private void notifyListeners(int oldValue, int newValue) {
        List<ValueChangeListener> snapshotListeners;
        listenerLock.lock();  // Lock before copying the list
        try {
            snapshotListeners = new ArrayList<>(listeners); // Create a snapshot to avoid modification during iteration
        } finally {
            listenerLock.unlock();
        }

        for (ValueChangeListener listener : snapshotListeners) {
            listener.onValueChanged(oldValue, newValue);
        }
    }

    public interface ValueChangeListener {
        void onValueChanged(int oldValue, int newValue);
    }
}


