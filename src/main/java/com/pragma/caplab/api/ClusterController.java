package com.pragma.caplab.api;

import com.pragma.caplab.core.ClusterEvent;
import com.pragma.caplab.core.ClusterService;
import com.pragma.caplab.core.ClusterSnapshot;
import com.pragma.caplab.domain.Mode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel de control del laboratorio:
 * modo, latencia, particiones, caídas de nodos y replicación.
 *
 * @author Raul Bolivar Navas.
 * */
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    public record ModeRequest(@NotNull Mode mode) {
    }

    public record LatencyRequest(@Min(0) @Max(10_000) long ms) {
    }

    public record PartitionRequest(@NotEmpty List<List<String>> groups) {
    }

    private final ClusterService cluster;

    public ClusterController(ClusterService cluster) {
        this.cluster = cluster;
    }

    @GetMapping("/state")
    public ClusterSnapshot state() {
        return cluster.snapshot();
    }

    @GetMapping("/events")
    public List<ClusterEvent> events(@RequestParam(defaultValue = "60") int limit) {
        return cluster.events(Math.clamp(limit, 1, 300));
    }

    @PostMapping("/mode")
    public ClusterSnapshot mode(@Valid @RequestBody ModeRequest request) {
        cluster.setMode(request.mode());
        return cluster.snapshot();
    }

    @PostMapping("/latency")
    public ClusterSnapshot latency(@Valid @RequestBody LatencyRequest request) {
        cluster.setLatencyMs(request.ms());
        return cluster.snapshot();
    }

    @PostMapping("/partition")
    public ClusterSnapshot partition(@Valid @RequestBody PartitionRequest request) {
        cluster.partition(request.groups());
        return cluster.snapshot();
    }

    @PostMapping("/heal")
    public ClusterSnapshot heal() {
        cluster.heal();
        return cluster.snapshot();
    }

    @PostMapping("/nodes/{id}/down")
    public ClusterSnapshot down(@PathVariable String id) {
        cluster.nodeDown(id);
        return cluster.snapshot();
    }

    @PostMapping("/nodes/{id}/up")
    public ClusterSnapshot up(@PathVariable String id) {
        cluster.nodeUp(id);
        return cluster.snapshot();
    }

    /** Fuerza la entrega inmediata de la replicación pendiente que sea alcanzable. */
    @PostMapping("/replicate-now")
    public Map<String, Object> replicateNow() {
        int delivered = cluster.deliverDue(true);
        return Map.of("delivered", delivered, "state", cluster.snapshot());
    }

    @PostMapping("/anti-entropy")
    public Map<String, Object> antiEntropy() {
        int repaired = cluster.antiEntropy();
        return Map.of("repaired", repaired, "state", cluster.snapshot());
    }

    @PostMapping("/reset")
    public ClusterSnapshot reset() {
        cluster.reset();
        return cluster.snapshot();
    }
}
