package com.pragma.caplab.api;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Prueba end-to-end del CRUD y del panel de control a través de HTTP. La replicación se avanza a mano. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"lab.replication.auto=false", "lab.latency-ms=0"})
@AutoConfigureWebTestClient
class ApiIntegrationTest {

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void reset() {
        client.post().uri("/api/cluster/reset").exchange().expectStatus().isOk();
        client.post().uri("/api/cluster/mode").bodyValue(Map.of("mode", "CP")).exchange().expectStatus().isOk();
    }

    private void createItem(String node, String id, int qty) {
        client.post().uri("/api/items?node={n}", node)
                .bodyValue(Map.of("id", id, "name", "Café", "quantity", qty))
                .exchange().expectStatus().isCreated();
    }

    @Test
    @DisplayName("CP: el CRUD funciona con quórum y la respuesta explica cuántos nodos participaron")
    void crudInCpMode() {
        createItem("n1", "sku-1", 10);

        client.get().uri("/api/items/sku-1?node=n2").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.quantity").isEqualTo(10)
                .jsonPath("$.meta.mode").isEqualTo("CP")
                .jsonPath("$.meta.nodesContacted").isEqualTo(3)
                .jsonPath("$.meta.required").isEqualTo(2)
                .jsonPath("$.meta.stale").isEqualTo(false);

        client.put().uri("/api/items/sku-1?node=n3")
                .bodyValue(Map.of("name", "Café", "quantity", 12))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.quantity").isEqualTo(12);

        client.delete().uri("/api/items/sku-1?node=n1").exchange().expectStatus().isOk();
        client.get().uri("/api/items/sku-1?node=n2").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("CP: durante una partición el nodo minoritario responde 503 y la mayoría sigue sirviendo")
    void minorityGets503InCpMode() {
        createItem("n1", "sku-1", 10);
        client.post().uri("/api/cluster/partition")
                .bodyValue(Map.of("groups", new String[][] {{"n1"}, {"n2", "n3"}}))
                .exchange().expectStatus().isOk();

        client.get().uri("/api/items/sku-1?node=n1").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("CP_QUORUM_UNAVAILABLE")
                .jsonPath("$.mode").isEqualTo("CP")
                .jsonPath("$.details.requiredQuorum").isEqualTo(2);

        client.get().uri("/api/items/sku-1?node=n2").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("AP: lectura obsoleta antes de replicar y convergencia después")
    void staleReadThenConvergenceInApMode() {
        client.post().uri("/api/cluster/mode").bodyValue(Map.of("mode", "AP")).exchange().expectStatus().isOk();
        createItem("n1", "sku-1", 10);

        client.get().uri("/api/items/sku-1?node=n3").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.message").value(m -> org.junit.jupiter.api.Assertions.assertTrue(
                        m.toString().contains("replicación")));

        client.post().uri("/api/cluster/replicate-now").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.delivered").isEqualTo(2);

        client.get().uri("/api/items/sku-1?node=n3").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.quantity").isEqualTo(10)
                .jsonPath("$.meta.mode").isEqualTo("AP")
                .jsonPath("$.meta.nodesContacted").isEqualTo(1);

        client.get().uri("/api/cluster/state").exchange()
                .expectBody().jsonPath("$.convergence.converged").isEqualTo(true);
    }

    @Test
    @DisplayName("AP: el nodo aislado sigue aceptando escrituras")
    void isolatedNodeStaysAvailableInApMode() {
        client.post().uri("/api/cluster/mode").bodyValue(Map.of("mode", "AP")).exchange().expectStatus().isOk();
        createItem("n1", "sku-1", 10);
        client.post().uri("/api/cluster/replicate-now").exchange().expectStatus().isOk();
        client.post().uri("/api/cluster/partition")
                .bodyValue(Map.of("groups", new String[][] {{"n1"}, {"n2", "n3"}}))
                .exchange().expectStatus().isOk();

        client.put().uri("/api/items/sku-1?node=n1")
                .bodyValue(Map.of("name", "Café", "quantity", 99))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.meta.required").isEqualTo(1);
    }

    @Test
    @DisplayName("Validaciones: cuerpo inválido, nodo desconocido y topología incorrecta")
    void validation() {
        client.post().uri("/api/items")
                .bodyValue(Map.of("name", "", "quantity", -1))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        client.get().uri("/api/items?node=zz").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("UNKNOWN_NODE");

        client.post().uri("/api/cluster/partition")
                .bodyValue(Map.of("groups", new String[][] {{"n1"}, {"n2"}}))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOPOLOGY");
    }

    @Test
    @DisplayName("Los escenarios guiados se ejecutan de forma aislada y devuelven su transcripción")
    void scenarios() {
        client.get().uri("/api/lab/scenarios").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(3);

        client.get().uri("/api/lab/scenarios/ap-partition").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("ap-partition")
                .jsonPath("$.steps.length()").isNotEmpty()
                .jsonPath("$.conclusions.length()").isNotEmpty();

        client.get().uri("/api/lab/scenarios/nope").exchange().expectStatus().isNotFound();
    }
}
