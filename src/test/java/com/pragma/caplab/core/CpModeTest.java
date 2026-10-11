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

/** Modo CP: consistencia con quórum. Ante una partición se sacrifica la disponibilidad de la minoría. */
class CpModeTest {

    private ClusterService cluster;

    @BeforeEach
    void setUp() {
        cluster = new ClusterService(Clock.systemUTC(), List.of("n1", "n2", "n3"), 0);
        cluster.setMode(Mode.CP);
    }

    private static ItemData coffee(int qty) {
        return new ItemData("Café", qty);
    }

    private int qty(String node, String key) {
        return cluster.read(node, key).data().data().quantity();
    }

    @Test
    @DisplayName("Con la red sana, la escritura se replica de forma síncrona en todos los nodos")
    void healthyClusterReplicatesSynchronously() {
        OpResult<?> result = cluster.create("n1", "sku-1", coffee(10));

        assertEquals(3, result.meta().nodesContacted());
        assertEquals(2, result.meta().required());
        ClusterSnapshot snapshot = cluster.snapshot();
        assertTrue(snapshot.convergence().converged());
        snapshot.nodes().forEach(node -> assertEquals(1, node.records().size()));
        assertEquals(0, snapshot.pending().size());
    }

    @Test
    @DisplayName("CAP: el lado minoritario de una partición rechaza lecturas y escrituras (sacrifica A)")
    void minoritySideRejectsReadsAndWrites() {
        cluster.create("n1", "sku-1", coffee(10));
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));

        LabException read = assertThrows(LabException.QuorumUnavailable.class, () -> cluster.read("n1", "sku-1"));
        assertEquals(503, read.status());
        assertEquals("CP_QUORUM_UNAVAILABLE", read.code());
        assertThrows(LabException.QuorumUnavailable.class, () -> cluster.update("n1", "sku-1", coffee(99)));
        assertThrows(LabException.QuorumUnavailable.class, () -> cluster.create("n1", "sku-2", coffee(1)));
        assertThrows(LabException.QuorumUnavailable.class, () -> cluster.list("n1"));
        assertEquals(4, cluster.snapshot().stats().rejectedOperations());
    }

    @Test
    @DisplayName("CAP: el lado mayoritario sigue disponible y nunca sirve datos obsoletos")
    void majoritySideKeepsServingFreshData() {
        cluster.create("n1", "sku-1", coffee(10));
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));

        cluster.update("n2", "sku-1", coffee(20));

        OpResult<?> read = cluster.read("n3", "sku-1");
        assertEquals(20, qty("n3", "sku-1"));
        assertFalse(read.meta().stale());
        // La réplica aislada conserva el valor viejo, pero nadie puede leerlo.
        var isolated = cluster.snapshot().nodes().get(0);
        assertEquals(10, (int) isolated.records().get(0).quantity());
    }

    @Test
    @DisplayName("Al sanar la red, la réplica aislada se pone al día antes de volver a servir")
    void healedClusterCatchesUp() {
        cluster.create("n1", "sku-1", coffee(10));
        cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));
        cluster.update("n2", "sku-1", coffee(20));
        assertFalse(cluster.snapshot().convergence().converged());

        cluster.heal();

        assertTrue(cluster.snapshot().convergence().converged());
        assertEquals(20, qty("n1", "sku-1"));
    }

    @Test
    @DisplayName("Quórum: con 3 nodos tolera 1 caída pero no 2")
    void toleratesOneFailureButNotTwo() {
        cluster.create("n1", "sku-1", coffee(10));

        cluster.nodeDown("n3");
        cluster.update("n1", "sku-1", coffee(5));
        assertEquals(5, qty("n2", "sku-1"));

        cluster.nodeDown("n2");
        assertThrows(LabException.QuorumUnavailable.class, () -> cluster.read("n1", "sku-1"));

        cluster.nodeUp("n2");
        assertEquals(5, qty("n1", "sku-1"));

        cluster.nodeUp("n3");
        assertTrue(cluster.snapshot().convergence().converged());
        assertEquals(5, qty("n3", "sku-1"));
    }

    @Test
    @DisplayName("Un nodo caído no atiende peticiones")
    void downNodeRejectsRequests() {
        cluster.nodeDown("n2");
        LabException e = assertThrows(LabException.NodeDown.class, () -> cluster.list("n2"));
        assertEquals(503, e.status());
    }

    @Test
    @DisplayName("Reglas del CRUD: duplicados, inexistentes y recreación tras borrar")
    void crudRules() {
        cluster.create("n1", "sku-1", coffee(10));
        assertThrows(LabException.AlreadyExists.class, () -> cluster.create("n2", "sku-1", coffee(1)));
        assertThrows(LabException.NotFound.class, () -> cluster.update("n1", "nope", coffee(1)));
        assertThrows(LabException.NotFound.class, () -> cluster.delete("n1", "nope"));

        cluster.delete("n2", "sku-1");
        assertThrows(LabException.NotFound.class, () -> cluster.read("n1", "sku-1"));
        assertTrue(cluster.list("n3").data().isEmpty());

        cluster.create("n3", "sku-1", coffee(7));
        assertEquals(7, qty("n1", "sku-1"));
    }

    @Test
    @DisplayName("La topología se valida: nodos desconocidos, repetidos u omitidos")
    void topologyValidation() {
        assertThrows(LabException.UnknownNode.class, () -> cluster.partition(List.of(List.of("n1", "zz"), List.of("n2", "n3"))));
        assertThrows(LabException.InvalidTopology.class, () -> cluster.partition(List.of(List.of("n1", "n2"), List.of("n2", "n3"))));
        assertThrows(LabException.InvalidTopology.class, () -> cluster.partition(List.of(List.of("n1"), List.of("n2"))));
    }
}
