package com.pragma.caplab.config;

import com.pragma.caplab.core.ClusterService;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LabConfig {

    @Bean
    ClusterService clusterService(@Value("${lab.latency-ms:400}") long latencyMs) {
        return new ClusterService(
                Clock.systemUTC(),
                List.of("n1", "n2", "n3"),
                latencyMs
        );
    }
}
