package com.pragma.caplab.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionedRecordTest {

    private static VectorClock clock(Map<String, Long> counters) {
        return new VectorClock(counters);
    }

    private static VersionedRecord record(String origin, long ts, int qty, VectorClock clock) {
        return VersionedRecord.of("k", new ItemData("x", qty), clock, ts, origin);
    }

    @Test
    @DisplayName("Los relojes vectoriales distinguen antes / después / igual / concurrente")
    void vectorClockOrdering() {
        VectorClock a = clock(Map.of("n1", 1L));
        VectorClock b = clock(Map.of("n1", 2L));
        VectorClock c = clock(Map.of("n2", 1L));

        assertEquals(Ordering.BEFORE, a.compare(b));
        assertEquals(Ordering.AFTER, b.compare(a));
        assertEquals(Ordering.EQUAL, a.compare(clock(Map.of("n1", 1L))));
        assertEquals(Ordering.CONCURRENT, b.compare(c));
        assertEquals(Map.of("n1", 2L, "n2", 1L), b.merge(c).counters());
        assertEquals(Map.of("n1", 2L), a.increment("n1").counters());
    }

    @Test
    @DisplayName("merge es conmutativo, idempotente y asociativo: base de la consistencia eventual")
    void mergeIsASemilattice() {
        VersionedRecord a = record("n1", 10, 1, clock(Map.of("n1", 1L)));
        VersionedRecord b = record("n2", 20, 2, clock(Map.of("n2", 1L)));
        VersionedRecord c = record("n3", 15, 3, clock(Map.of("n3", 1L)));

        // conmutativo
        assertEquals(a.merge(b).result(), b.merge(a).result());
        // idempotente
        assertFalse(a.merge(a).changed());
        assertEquals(a, a.merge(a).result());
        // asociativo
        VersionedRecord left = a.merge(b).result().merge(c).result();
        VersionedRecord right = a.merge(b.merge(c).result()).result();
        assertEquals(left, right);
        // gana la escritura más reciente (b, ts=20)
        assertEquals(2, left.data().quantity());
    }

    @Test
    @DisplayName("Solo se reporta conflicto cuando las escrituras son concurrentes")
    void conflictOnlyWhenConcurrent() {
        VersionedRecord old = record("n1", 10, 1, clock(Map.of("n1", 1L)));
        VersionedRecord newer = record("n1", 20, 2, clock(Map.of("n1", 2L)));
        VersionedRecord other = record("n2", 30, 3, clock(Map.of("n2", 1L)));

        assertFalse(old.merge(newer).conflict());
        assertEquals(2, old.merge(newer).result().data().quantity());
        assertFalse(newer.merge(old).changed());
        assertTrue(newer.merge(other).conflict());
        assertEquals(old.clock().merge(newer.clock()), old.merge(newer).result().clock());
    }
}
