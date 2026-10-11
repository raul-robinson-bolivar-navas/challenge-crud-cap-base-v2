package com.pragma.caplab.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pragma.caplab.domain.ItemData;
import com.pragma.caplab.domain.LabException;
import com.pragma.caplab.domain.Mode;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Modo AP / BASE: disponibilidad, estado blando y consistencia eventual. */
class ApModeTest {

    private ClusterService cluster;

    @BeforeEach
    void setUp() {
        cluster = new ClusterService(Clock.systemUTC(), List.of("n1", "n2", "n3"), 0);
        cluster.setMode(Mode.AP);
    }

    private static ItemData coffee(int qty) {
        return new ItemData("Café", qty);
    }

    private int qty(String node, String key) {
        return cluster.read(node, key).data().data().quantity();
    }

    @Test
    @DisplayName("Basically Available: ambos lados de una partición aceptan escrituras")
    void everyAliveNodeStaysAvailableDuringPartition() {
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));

        OpResult<?> minority = cluster.update("n1", "sku-1", coffee(99));
        OpResult<?> majority = cluster.update("n2", "sku-1", coffee(20));

        assertEquals(1, minority.meta().nodesContacted());
        assertEquals(1, minority.meta().required());
        assertEquals(1, majority.meta().nodesContacted());
        assertEquals(99, qty("n1", "sku-1"));
        assertEquals(20, qty("n2", "sku-1"));
    }

    @Test
    @DisplayName("Soft state: lecturas obsoletas hasta que la replicación asíncrona llega")
    void staleReadsThenEventualConsistency() {
        cluster.create("n1", "sku-1", coffee(10));

        LabException notYet = assertThrows(LabException.NotFound.class, () -> cluster.read("n3", "sku-1"));
        assertTrue(notYet.getMessage().contains("replicación"));

        cluster.deliverDue(true);
        assertEquals(10, qty("n3", "sku-1"));
        assertFalse(cluster.read("n3", "sku-1").meta().stale());

        cluster.update("n1", "sku-1", coffee(11));
        OpResult<?> stale = cluster.read("n3", "sku-1");
        assertEquals(10, qty("n3", "sku-1"));
        assertTrue(stale.meta().stale());

        cluster.deliverDue(true);
        assertEquals(11, qty("n3", "sku-1"));
        assertFalse(cluster.read("n3", "sku-1").meta().stale());
    }

    @Test
    @DisplayName("Eventual consistency: escrituras concurrentes divergen y convergen por Last-Write-Wins")
    void concurrentWritesDivergeThenConverge() {
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));

        cluster.update("n1", "sku-1", coffee(99));
        cluster.update("n2", "sku-1", coffee(20)); // posterior en el tiempo: debe ganar
        cluster.deliverDue(true);

        // Estado blando: las réplicas difieren y hay replicación atrapada tras la partición.
        ClusterSnapshot diverged = cluster.snapshot();
        assertFalse(diverged.convergence().converged());
        assertEquals(List.of("sku-1"), diverged.convergence().divergentKeys());
        assertTrue(diverged.pending().stream().allMatch(p -> p.status().contains("partición")));
        assertEquals(99, qty("n1", "sku-1"));
        assertTrue(cluster.read("n1", "sku-1").meta().stale());
        assertEquals(20, qty("n3", "sku-1"));

        cluster.heal();
        cluster.deliverDue(true);

        ClusterSnapshot converged = cluster.snapshot();
        assertTrue(converged.convergence().converged());
        assertEquals(0, converged.pending().size());
        assertEquals(1, converged.stats().conflictsResolved());
        for (String node : List.of("n1", "n2", "n3")) {
            assertEquals(20, qty(node, "sku-1"));
        }
        assertTrue(cluster.events(300).stream().anyMatch(e -> e.type().equals("CONFLICT")));
    }

    @Test
    @DisplayName("El orden de llegada de los mensajes no altera el resultado final (convergencia)")
    void convergenceDoesNotDependOnDeliveryOrder() {
        // Tres escrituras concurrentes, una por nodo, entregadas en el orden natural de la cola.
        cluster.partition(List.of(List.of("n1"), List.of("n2"), List.of("n3")));
        cluster.create("n1", "k", coffee(1));
        cluster.create("n2", "k", coffee(2));
        cluster.create("n3", "k", coffee(3));
        cluster.heal();
        cluster.deliverDue(true);

        assertTrue(cluster.snapshot().convergence().converged());
        assertEquals(3, qty("n1", "k")); // la última escritura en el tiempo gana en todas las réplicas
        assertEquals(3, qty("n2", "k"));
        assertEquals(3, qty("n3", "k"));
    }

    @Test
    @DisplayName("Los borrados se replican como lápidas (tombstones)")
    void deletesReplicateAsTombstones() {
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);

        cluster.delete("n2", "sku-1");
        cluster.deliverDue(true);

        assertThrows(LabException.NotFound.class, () -> cluster.read("n1", "sku-1"));
        assertThrows(LabException.NotFound.class, () -> cluster.read("n3", "sku-1"));
        cluster.snapshot().nodes().forEach(n -> assertTrue(n.records().get(0).deleted()));
        assertTrue(cluster.snapshot().convergence().converged());
    }

    @Test
    @DisplayName("Hinted handoff: lo destinado a un nodo caído espera en cola y se entrega al volver")
    void hintedHandoffDeliversToRecoveredNode() {
        cluster.nodeDown("n3");
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);

        ClusterSnapshot during = cluster.snapshot();
        assertTrue(during.nodes().get(2).records().isEmpty());
        assertEquals(1, during.pending().size());
        assertEquals("bloqueado (nodo caído)", during.pending().get(0).status());

        cluster.nodeUp("n3");
        cluster.deliverDue(true);

        assertEquals(10, qty("n3", "sku-1"));
        assertTrue(cluster.snapshot().convergence().converged());
    }

    @Test
    @DisplayName("Anti-entropía: repara la divergencia sin depender de la cola de replicación")
    void antiEntropyRepairsDivergence() {
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));
        cluster.update("n1", "sku-1", coffee(99));
        cluster.update("n2", "sku-1", coffee(20));
        cluster.heal(); // en AP, heal NO sincroniza por sí solo

        assertFalse(cluster.snapshot().convergence().converged());
        int repaired = cluster.antiEntropy();

        assertTrue(repaired > 0);
        assertTrue(cluster.snapshot().convergence().converged());
        assertEquals(1, cluster.snapshot().stats().conflictsResolved());
        cluster.deliverDue(true); // los mensajes tardíos ya no cambian nada
        assertTrue(cluster.snapshot().convergence().converged());
        assertEquals(20, qty("n1", "sku-1"));
    }
}
