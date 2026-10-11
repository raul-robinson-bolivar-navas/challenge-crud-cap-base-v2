package com.pragma.caplab.lab;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Experimentos Guiados.
 * No modifican el estado del panel interactivo.
 *
 * @author Raul Bolivar Navas.
 * */
@RestController
@RequestMapping("/api/lab/scenarios")
public class LabController {

    private final ScenarioRunner runner;

    public LabController(ScenarioRunner runner) {
        this.runner = runner;
    }

    @GetMapping
    public List<ScenarioRunner.Info> catalog() {
        return runner.catalog();
    }

    @GetMapping("/{id}")
    public ScenarioRunner.Report run(@PathVariable String id) {
        return runner.run(id);
    }
}
