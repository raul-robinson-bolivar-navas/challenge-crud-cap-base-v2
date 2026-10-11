package com.pragma.caplab.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pragma.caplab.domain.LabException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScenarioRunnerTest {

    private final ScenarioRunner runner = new ScenarioRunner();

    private static boolean anyStepContains(ScenarioRunner.Report report, String text) {
        return report.steps().stream().anyMatch(s -> s.result().contains(text));
    }

    @Test
    @DisplayName("El catálogo expone los tres experimentos")
    void catalog() {
        assertEquals(3, runner.catalog().size());
    }

    @Test
    @DisplayName("cp-partition: la minoría falla con 503 y tras sanar todo es consistente")
    void cpPartition() {
        ScenarioRunner.Report report = runner.run("cp-partition");

        assertFalse(report.steps().isEmpty());
        assertTrue(anyStepContains(report, "CP_QUORUM_UNAVAILABLE"));
        assertTrue(report.steps().get(report.steps().size() - 1).result().contains("qty=20"));
        assertTrue(anyStepContains(report, "n1=10 · n2=20 · n3=20"));
        assertFalse(anyStepContains(report, "obsoleto: SÍ"));
    }

    @Test
    @DisplayName("ap-partition: disponible, diverge, detecta el conflicto y converge")
    void apPartition() {
        ScenarioRunner.Report report = runner.run("ap-partition");

        assertTrue(anyStepContains(report, "NOT_FOUND"));          // lectura obsoleta antes de replicar
        assertTrue(anyStepContains(report, "n1=99 · n2=20 · n3=20")); // estado blando
        assertTrue(anyStepContains(report, "obsoleto: SÍ"));       // lectura obsoleta durante la partición
        assertTrue(anyStepContains(report, "Conflicto en 'sku-1'"));
        String last = report.steps().get(report.steps().size() - 1).result();
        assertTrue(last.contains("qty=20"));
        assertTrue(last.contains("convergido: sí"));
        assertFalse(anyStepContains(report, "CP_QUORUM_UNAVAILABLE"));
    }

    @Test
    @DisplayName("cp-node-failures: tolera 1 caída, rechaza con 2 y se recupera")
    void cpNodeFailures() {
        ScenarioRunner.Report report = runner.run("cp-node-failures");

        assertTrue(anyStepContains(report, "CP_QUORUM_UNAVAILABLE"));
        assertTrue(anyStepContains(report, "n1=5 · n2=5 · n3=5"));
    }

    @Test
    @DisplayName("Un escenario inexistente responde 404")
    void unknownScenario() {
        assertThrows(LabException.NotFound.class, () -> runner.run("no-existe"));
    }
}
