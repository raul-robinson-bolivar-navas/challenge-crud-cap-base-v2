package com.pragma.caplab.core;

import com.pragma.caplab.domain.Mode;
import com.pragma.caplab.domain.VersionedRecord;
import java.util.List;
import java.util.Map;

/**
 * Vista completa (y "omnisciente") del estado del clúster para el panel del laboratorio.
 *
 * @author Raul Bolivar Navas.
 * */
public record ClusterSnapshot(
        Mode mode,
        long latencyMs,
        int quorum,
        int totalNodes,
        List<NodeView> nodes,
        List<PendingView> pending,
        Convergence convergence,
        Stats stats,
        String summary) {

    public record NodeView(String id, boolean up, int partition, List<RecordView> records) {
    }

    public record RecordView(
            String key,
            String name,
            Integer quantity,
            boolean deleted,
            Map<String, Long> vectorClock,
            long timestamp,
            String origin) {

        static RecordView from(VersionedRecord r) {
            return new RecordView(
                    r.key(),
                    r.deleted() ? null : r.data().name(),
                    r.deleted() ? null : r.data().quantity(),
                    r.deleted(),
                    r.clock().counters(),
                    r.timestamp(),
                    r.origin());
        }
    }

    public record PendingView(long id, String from, String to, String key, long dueInMs, String status) {
    }

    /** converged = todas las réplicas guardan exactamente lo mismo. */
    public record Convergence(boolean converged, List<String> divergentKeys) {
    }

    public record Stats(long conflictsResolved, long replicationsDelivered, long rejectedOperations, int pendingReplications) {
    }
}
