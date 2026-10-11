package com.pragma.caplab.api;

import com.pragma.caplab.domain.VersionedRecord;
import java.util.Map;

public record ItemView(
        String id,
        String name,
        Integer quantity,
        boolean deleted,
        Map<String, Long> vectorClock,
        long timestamp,
        String lastWriter
) {

    public static ItemView from(VersionedRecord r) {
        return new ItemView(
                r.key(),
                r.deleted() ? null : r.data().name(),
                r.deleted() ? null : r.data().quantity(),
                r.deleted(),
                r.clock().counters(),
                r.timestamp(),
                r.origin()
        );
    }
}
