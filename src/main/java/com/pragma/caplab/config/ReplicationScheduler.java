package com.pragma.caplab.config;

import com.pragma.caplab.core.ClusterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Motor de replicación asíncrona de AP: cada 100 ms entrega los mensajes cuya latencia ya venció
 * y cuyo destino es alcanzable. Los demás siguen en cola (hinted handoff).
 *
 * @author Raul Bolivar Navas.
 */
@Component
@ConditionalOnProperty(
        name = "lab.replication.auto",
        havingValue = "true",
        matchIfMissing = true
)
public class ReplicationScheduler {

    private final ClusterService cluster;

    public ReplicationScheduler(ClusterService cluster) {
        this.cluster = cluster;
    }

    @Scheduled(fixedDelay = 100)
    public void tick() {
        cluster.deliverDue(false);
    }
}
