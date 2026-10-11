package com.pragma.caplab.api;

import com.pragma.caplab.core.ClusterService;
import com.pragma.caplab.core.OpResult;
import com.pragma.caplab.domain.ItemData;
import com.pragma.caplab.domain.Mode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Capa reactiva sobre el clúster.
 * Aquí se materializa el compromiso PACELC:
 * - en modo CP cada operación espera la latencia de red simulada (replicación/lectura por quórum),
 * - en modo AP responde de inmediato y la replicación viaja en segundo plano.
 *
 * @author Raul Bolivar Navas.
 */
@Service
public class ItemService {

    private final ClusterService cluster;

    public ItemService(ClusterService cluster) {
        this.cluster = cluster;
    }

    public Mono<OpResult<ItemView>> create(String node, ItemRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.id().trim();
        return timed(() -> cluster.create(node, id, data(request))).map(r -> r.map(ItemView::from));
    }

    public Mono<OpResult<ItemView>> update(String node, String id, ItemRequest request) {
        return timed(() -> cluster.update(node, id, data(request))).map(r -> r.map(ItemView::from));
    }

    public Mono<OpResult<ItemView>> delete(String node, String id) {
        return timed(() -> cluster.delete(node, id)).map(r -> r.map(ItemView::from));
    }

    public Mono<OpResult<ItemView>> get(String node, String id) {
        return timed(() -> cluster.read(node, id)).map(r -> r.map(ItemView::from));
    }

    public Mono<OpResult<List<ItemView>>> list(String node) {
        return timed(() -> cluster.list(node)).map(r -> r.map(items -> items.stream().map(ItemView::from).toList()));
    }

    private static ItemData data(ItemRequest request) {
        return new ItemData(request.name().trim(), request.quantity());
    }

    private <T> Mono<OpResult<T>> timed(Supplier<OpResult<T>> operation) {
        long start = System.nanoTime();
        long delay = cluster.mode() == Mode.CP ? cluster.latencyMs() : 0;
        Mono<Long> network = delay > 0 ? Mono.delay(Duration.ofMillis(delay)) : Mono.just(0L);
        return network
                .map(ignored -> operation.get())
                .map(result -> result.withLatency((System.nanoTime() - start) / 1_000_000));
    }
}
