package server.storage.cache;

import protocol.K;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * First In First Out strategy.
 * Evicts ({@link ICacheDisplacementTracker#evict()}) the oldest item in the insert order.
 */
public class FIFO implements ICacheDisplacementTracker {
    private LinkedHashSet<K> registry;

    public FIFO(int trackerCapacity) {
        registry = new LinkedHashSet<K>(trackerCapacity + 1, 1);
    }

    @Override
    public K evict() {
        Iterator<K> iter = registry.iterator();
        K k = iter.next();
        registry.remove(k);
        return k;
    }

    @Override
    public K register(K k) {
        registry.remove(k);
        registry.add(k);
        return k;
    }

    @Override
    public void unregister(K k) {
        this.registry.remove(k);
    }

    @Override
    public boolean containsKey(K key) {
        return registry.contains(key);
    }
}
