package com.pragma.caplab.domain;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reloj vectorial inmutable.
 * Permite saber si una escritura ocurrió ANTES, DESPUÉS
 * o de forma CONCURRENTE respecto a otra, sin depender de relojes físicos sincronizados.
 * Dos escrituras concurrentes son, precisamente, un conflicto de replicación.
 *
 * @author Raul Bolivar Navas.
 */
public record VectorClock(Map<String, Long> counters) {

    public VectorClock {
        counters = Collections.unmodifiableMap(new TreeMap<>(counters));
    }

    public static VectorClock empty() {
        return new VectorClock(Map.of());
    }

    public VectorClock increment(String nodeId) {
        var copy = new TreeMap<>(counters);
        copy.merge(nodeId, 1L, Long::sum);
        return new VectorClock(copy);
    }

    /** Máximo componente a componente. */
    public VectorClock merge(VectorClock other) {
        var copy = new TreeMap<>(counters);
        other.counters.forEach((node, value) -> copy.merge(node, value, Math::max));
        return new VectorClock(copy);
    }

    public Ordering compare(VectorClock other) {
        boolean less = false;
        boolean greater = false;
        Set<String> nodes = new HashSet<>(counters.keySet());
        nodes.addAll(other.counters.keySet());
        for (String node : nodes) {
            long mine = counters.getOrDefault(node, 0L);
            long theirs = other.counters.getOrDefault(node, 0L);
            if (mine < theirs) {
                less = true;
            } else if (mine > theirs) {
                greater = true;
            }
        }
        if (less && greater) {
            return Ordering.CONCURRENT;
        }
        if (less) {
            return Ordering.BEFORE;
        }
        if (greater) {
            return Ordering.AFTER;
        }
        return Ordering.EQUAL;
    }

    @Override
    public String toString() {
        return counters.toString();
    }
}
