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

/** Fase 3: eliminación (DELETE) bajo CP y AP, lápidas, resurrección y reconciliación. */
class DeleteTest {

    private ClusterService cluster;

    @BeforeEach
    void setUp() {
        cluster = new ClusterService(Clock.systemUTC(), List.of("n1", "n2", "n3"), 0);
    }

    private static ItemData coffee(int qty) {
        return new ItemData("Café", qty);
    }

    private boolean tombstoneIn(String node, String key) {
        return cluster.snapshot().nodes().stream().filter(n -> n.id().equals(node)).findFirst().orElseThrow()
                .records().stream().anyMatch(r -> r.key().equals(key) && r.deleted());
    }

    @Test
    @DisplayName("AP: DELETE se confirma localmente, replica asíncronamente y otros nodos ven el ítem hasta que llega la lápida")
    void apDeleteIsAsynchronous() {
        cluster.setMode(Mode.AP);
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);

        OpResult<?> deleted = cluster.delete("n1", "sku-1");

        assertEquals(1, deleted.meta().nodesContacted());
        assertEquals(2, cluster.snapshot().pending().size());
        // estado blando: n3 todavía sirve el ítem borrado
        assertEquals(10, cluster.read("n3", "sku-1").data().data().quantity());
        assertThrows(LabException.NotFound.class, () -> cluster.read("n1", "sku-1"));

        cluster.deliverDue(true);

        assertThrows(LabException.NotFound.class, () -> cluster.read("n3", "sku-1"));
        assertTrue(cluster.snapshot().convergence().converged());
    }

    @Test
    @DisplayName("AP: un nodo aislado acepta el DELETE y la lápida llega al sanar la red (hinted handoff)")
    void apDeleteDuringPartitionIsDeliveredAfterHeal() {
        cluster.setMode(Mode.AP);
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));

        cluster.delete("n1", "sku-1");
        cluster.deliverDue(true);
        assertFalse(tombstoneIn("n2", "sku-1"));
        assertEquals(10, cluster.read("n2", "sku-1").data().data().quantity());

        cluster.heal();
        cluster.deliverDue(true);

        assertTrue(tombstoneIn("n2", "sku-1"));
        assertTrue(tombstoneIn("n3", "sku-1"));
        assertTrue(cluster.snapshot().convergence().converged());
    }

    @Test
    @DisplayName("AP: la lápida no se 'resucita' con un mensaje de replicación tardío del valor anterior")
    void tombstoneIsNotResurrectedByLateMessages() {
        cluster.setMode(Mode.AP);
        cluster.create("n1", "sku-1", coffee(10));
        cluster.update("n1", "sku-1", coffee(11)); // mensajes viejos quedan en cola
        cluster.delete("n1", "sku-1");

        cluster.deliverDue(true); // llegan create, update y delete a n2/n3
        cluster.antiEntropy();

        for (String node : List.of("n1", "n2", "n3")) {
            assertThrows(LabException.NotFound.class, () -> cluster.read(node, "sku-1"));
        }
        assertTrue(cluster.snapshot().convergence().converged());
    }

    @Test
    @DisplayName("AP: DELETE concurrente con UPDATE se resuelve por Last-Write-Wins y se registra el conflicto")
    void concurrentDeleteAndUpdateResolvedByLww() {
        cluster.setMode(Mode.AP);
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));

        cluster.delete("n1", "sku-1");
        cluster.update("n2", "sku-1", coffee(50)); // escritura posterior en el tiempo → gana
        cluster.heal();
        cluster.antiEntropy();

        assertEquals(1, cluster.snapshot().stats().conflictsResolved());
        assertTrue(cluster.snapshot().convergence().converged());
        for (String node : List.of("n1", "n2", "n3")) {
            assertEquals(50, cluster.read(node, "sku-1").data().data().quantity());
        }
    }

    @Test
    @DisplayName("CP: DELETE exige quórum; la minoría lo rechaza y la mayoría lo replica síncronamente")
    void cpDeleteRequiresQuorum() {
        cluster.create("n1", "sku-1", coffee(10));
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));

        assertThrows(LabException.QuorumUnavailable.class, () -> cluster.delete("n1", "sku-1"));
        OpResult<?> ok = cluster.delete("n2", "sku-1");
        assertEquals(2, ok.meta().nodesContacted());
        assertThrows(LabException.NotFound.class, () -> cluster.read("n3", "sku-1"));

        cluster.heal(); // anti-entropía propaga la lápida a n1
        assertTrue(tombstoneIn("n1", "sku-1"));
        assertTrue(cluster.snapshot().convergence().converged());
    }

    @Test
    @DisplayName("Read-repair: una lectura por quórum propaga la lápida a la réplica que no la tenía")
    void readRepairPropagatesTombstone() {
        cluster.setMode(Mode.AP);
        cluster.create("n1", "sku-1", coffee(10));
        cluster.deliverDue(true);
        cluster.delete("n1", "sku-1"); // la lápida solo existe en n1; replicación aún en cola
        cluster.setMode(Mode.CP);

        assertFalse(tombstoneIn("n2", "sku-1"));
        assertThrows(LabException.NotFound.class, () -> cluster.read("n2", "sku-1"));

        assertTrue(tombstoneIn("n2", "sku-1"));
        assertTrue(tombstoneIn("n3", "sku-1"));
        assertTrue(cluster.events(300).stream().anyMatch(e -> e.type().equals("REPAIR")));
    }

    @Test
    @DisplayName("Tras borrar se puede recrear el mismo id, y la nueva versión gana a la lápida")
    void recreateAfterDelete() {
        cluster.setMode(Mode.AP);
        cluster.create("n1", "sku-1", coffee(10));
        cluster.delete("n1", "sku-1");
        cluster.create("n1", "sku-1", coffee(7));
        cluster.deliverDue(true);

        for (String node : List.of("n1", "n2", "n3")) {
            assertEquals(7, cluster.read(node, "sku-1").data().data().quantity());
        }
        assertTrue(cluster.snapshot().convergence().converged());
    }
}
