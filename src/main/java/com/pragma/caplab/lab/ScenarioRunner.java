package com.pragma.caplab.lab;

import com.pragma.caplab.core.ClusterEvent;
import com.pragma.caplab.core.ClusterService;
import com.pragma.caplab.core.ClusterSnapshot;
import com.pragma.caplab.core.OpResult;
import com.pragma.caplab.domain.ItemData;
import com.pragma.caplab.domain.LabException;
import com.pragma.caplab.domain.Mode;
import com.pragma.caplab.domain.VersionedRecord;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Experimentos Guiados.
 * Cada escenario se ejecuta sobre un clúster NUEVO y aislado (no toca el estado del panel interactivo)
 * y devuelve una transcripción paso a paso con la lección de cada paso.
 *
 * @author Raul Bolivar Navas.
 */
@Component
public class ScenarioRunner {

    public record Info(String id, String title, String goal) {
    }

    public record Step(int n, String action, String result, String insight) {
    }

    public record Report(String id, String title, String goal, List<Step> steps, List<String> conclusions) {
    }

    private static final List<Info> CATALOG = List.of(
            new Info("cp-partition", "CP: partición de red",
                    "Ver cómo un sistema CP sacrifica la disponibilidad del lado minoritario para mantener la consistencia."),
            new Info("ap-partition", "AP / BASE: partición, divergencia y convergencia",
                    "Ver un sistema AP seguir disponible, divergir (estado blando) y converger al sanar (consistencia eventual)."),
            new Info("cp-node-failures", "CP: tolerancia a caídas de nodos (quórum)",
                    "Ver cuántos nodos puede perder un clúster de 3 con quórum de 2 antes de dejar de servir."));

    public List<Info> catalog() {
        return CATALOG;
    }

    public Report run(String id) {
        return switch (id) {
            case "cp-partition" -> cpPartition();
            case "ap-partition" -> apPartition();
            case "cp-node-failures" -> cpNodeFailures();
            default -> throw new LabException.NotFound("No existe el escenario '%s'.".formatted(id));
        };
    }

    // ─── SCENARIOS ────────────────────────────────────

    private Report cpPartition() {
        Info info = CATALOG.getFirst();
        Run r = new Run();
        r.cluster.setMode(Mode.CP);

        r.step("Modo CP · POST sku-1 (Café, qty=10) vía n1",
                () -> r.attempt(() -> r.cluster.create("n1", "sku-1", new ItemData("Café", 10))),
                "Con la red sana hay quórum (3 de 3): la escritura se replica de forma síncrona en todas las réplicas.");
        r.step("💥 Partición de red: {n1} | {n2, n3}",
                () -> {
                    r.cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));
                    return "n1 queda aislado (alcanza 1 de 3 nodos); n2 y n3 forman la mayoría (2 de 3).";
                },
                "La Partición (P) no es opcional en una red real: lo que se decide es qué sacrificar, C o A.");
        r.step("GET sku-1 vía n1 (lado minoritario)",
                () -> r.attempt(() -> r.cluster.read("n1", "sku-1")),
                "Se pierde la Disponibilidad (A): n1 está vivo pero prefiere fallar antes que responder sin certeza.");
        r.step("PUT sku-1 qty=99 vía n1 (lado minoritario)",
                () -> r.attempt(() -> r.cluster.update("n1", "sku-1", new ItemData("Café", 99))),
                "Aceptar esta escritura crearía una versión divergente imposible de reconciliar sin perder datos.");
        r.step("PUT sku-1 qty=20 vía n2 (lado mayoritario)",
                () -> r.attempt(() -> r.cluster.update("n2", "sku-1", new ItemData("Café", 20))),
                "La mayoría alcanza el quórum, así que el servicio continúa para quien esté de ese lado.");
        r.step("GET sku-1 vía n3",
                () -> r.attempt(() -> r.cluster.read("n3", "sku-1")),
                "Consistencia fuerte: la lectura siempre devuelve el último valor confirmado (stale=false).");
        r.step("Estado de las réplicas de sku-1",
                () -> r.replicas("sku-1"),
                "n1 conserva un valor viejo, pero nunca lo sirve: el dato obsoleto queda 'encerrado' en el lado sin quórum.");
        r.step("🩹 La red se repara (heal)",
                () -> {
                    r.cluster.heal();
                    return r.replicas("sku-1");
                },
                "En CP, al volver a verse los nodos, la anti-entropía pone al día a n1 antes de que vuelva a servir.");
        r.step("GET sku-1 vía n1",
                () -> r.attempt(() -> r.cluster.read("n1", "sku-1")),
                "n1 vuelve a estar disponible y ya devuelve el valor correcto: nunca se sirvió un dato incoherente.");

        return r.report(info, List.of(
                "CP = Consistencia + Tolerancia a particiones: durante la partición el lado minoritario deja de estar disponible.",
                "El quórum (mayoría) garantiza que dos lados de una partición nunca acepten escrituras a la vez (sin 'split brain').",
                "Costo adicional (PACELC, 'Else'): incluso sin partición, cada operación paga la latencia de coordinación entre nodos.",
                "Casos típicos: saldos bancarios, inventario crítico, locks distribuidos, metadatos de clúster (etcd, ZooKeeper, Consul)."));
    }

    private Report apPartition() {
        Info info = CATALOG.get(1);
        Run r = new Run();

        r.step("Cambiar el clúster a modo AP (BASE)",
                () -> {
                    r.cluster.setMode(Mode.AP);
                    return "Modo AP activo: cada nodo atiende con su copia local y replica en segundo plano.";
                },
                "Cambiamos la prioridad: Disponibilidad primero, consistencia eventual.");
        r.step("POST sku-1 (Café, qty=10) vía n1",
                () -> r.attempt(() -> r.cluster.create("n1", "sku-1", new ItemData("Café", 10))),
                "La escritura se confirma al instante con una sola copia (Basically Available); lo demás viaja en segundo plano.");
        r.step("GET sku-1 vía n3 (antes de que llegue la replicación)",
                () -> r.attempt(() -> r.cluster.read("n3", "sku-1")),
                "Estado blando (Soft state) y lectura obsoleta: el dato existe en el clúster pero n3 todavía no lo ve.");
        r.step("La replicación asíncrona avanza",
                () -> {
                    int delivered = r.cluster.deliverDue(true);
                    return "%d mensaje(s) entregados. %s".formatted(delivered, r.replicas("sku-1"));
                },
                "Consistencia eventual: sin nuevas escrituras, las réplicas convergen solas.");
        r.step("💥 Partición de red: {n1} | {n2, n3}",
                () -> {
                    r.cluster.partition(List.of(List.of("n1"), List.of("n2", "n3")));
                    return "Los dos lados ya no pueden comunicarse.";
                },
                "Misma falla que en el escenario CP; ahora veamos qué hace un sistema AP.");
        r.step("PUT sku-1 qty=99 vía n1 (lado minoritario)",
                () -> r.attempt(() -> r.cluster.update("n1", "sku-1", new ItemData("Café", 99))),
                "Disponibilidad total: n1 acepta la escritura aunque esté aislado (en CP se rechazó).");
        r.step("PUT sku-1 qty=20 vía n2 (lado mayoritario), concurrente con la anterior",
                () -> r.attempt(() -> r.cluster.update("n2", "sku-1", new ItemData("Café", 20))),
                "Ambos lados aceptan escrituras sobre el mismo ítem sin verse: nace un conflicto latente (relojes vectoriales concurrentes).");
        r.step("La replicación avanza dentro de cada lado",
                () -> {
                    int delivered = r.cluster.deliverDue(true);
                    ClusterSnapshot s = r.cluster.snapshot();
                    return "%d mensaje(s) entregados. %s · pendientes bloqueados: %d · convergido: %s".formatted(
                            delivered, r.replicas("sku-1"), s.stats().pendingReplications(),
                            s.convergence().converged() ? "sí" : "NO");
                },
                "Estado blando visible: n1 ve 99 mientras n2 y n3 ven 20. Los mensajes hacia el otro lado esperan en cola (hinted handoff).");
        r.step("GET sku-1 vía n1",
                () -> r.attempt(() -> r.cluster.read("n1", "sku-1")),
                "Responde (A), pero con un valor que el resto del clúster ya superó: marcado como obsoleto por el laboratorio.");
        r.step("🩹 La red se repara (heal)",
                () -> {
                    r.cluster.heal();
                    return "Red restablecida. En AP no hay sincronización inmediata: la cola pendiente empieza a entregarse.";
                },
                "A diferencia de CP, aquí la reparación es asíncrona: durante un rato las réplicas siguen distintas.");
        r.step("La replicación pendiente se entrega y se resuelven conflictos",
                () -> {
                    int delivered = r.cluster.deliverDue(true);
                    String conflict = r.cluster.events(300).stream()
                            .filter(e -> e.type().equals("CONFLICT")).map(ClusterEvent::message)
                            .reduce((first, second) -> second).orElse("(sin conflictos detectados)");
                    return "%d mensaje(s) entregados. %s%n%s".formatted(delivered, r.replicas("sku-1"), conflict);
                },
                "Los relojes vectoriales detectan la concurrencia y Last-Write-Wins elige el ganador de forma determinista en todas las réplicas.");
        r.step("GET sku-1 vía n1 y estado de convergencia",
                () -> {
                    String read = r.attempt(() -> r.cluster.read("n1", "sku-1"));
                    return read + " · convergido: " + (r.cluster.snapshot().convergence().converged() ? "sí ✅" : "NO");
                },
                "Consistencia eventual lograda: todas las réplicas coinciden. La escritura qty=99 se perdió (lost update).");

        return r.report(info, List.of(
                "BASE: Basically Available (todos respondieron), Soft state (las réplicas divergieron), Eventual consistency (convergieron al sanar).",
                "El precio de AP es doble: lecturas obsoletas durante la divergencia y posibles escrituras perdidas al resolver conflictos con LWW.",
                "Alternativas a LWW: CRDTs, 'siblings' estilo Dynamo/Riak para que decida la aplicación, o fusión semántica (p. ej. sumar deltas de inventario).",
                "Casos típicos: carrito de compras, catálogos, feeds, contadores de 'me gusta', sesiones, DNS (Dynamo, Cassandra, Riak, DynamoDB en modo eventual)."));
    }

    private Report cpNodeFailures() {
        Info info = CATALOG.get(2);
        Run r = new Run();
        r.cluster.setMode(Mode.CP);

        r.step("POST sku-1 (Café, qty=10) vía n1",
                () -> r.attempt(() -> r.cluster.create("n1", "sku-1", new ItemData("Café", 10))),
                "Clúster sano: 3 de 3 nodos confirman.");
        r.step("💥 Cae el nodo n3 (1 de 3 caídos)",
                () -> {
                    r.cluster.nodeDown("n3");
                    return "n3 fuera de línea.";
                },
                "Con N=3 el quórum es 2: el clúster tolera la caída de 1 nodo.");
        r.step("PUT sku-1 qty=5 vía n1",
                () -> r.attempt(() -> r.cluster.update("n1", "sku-1", new ItemData("Café", 5))),
                "Siguen disponibles 2 nodos = quórum alcanzado, la escritura procede.");
        r.step("💥 Cae también n2 (2 de 3 caídos)",
                () -> {
                    r.cluster.nodeDown("n2");
                    return "n2 fuera de línea; n1 queda solo.";
                },
                "Ahora solo queda 1 nodo vivo: ya no hay mayoría posible.");
        r.step("GET sku-1 vía n1",
                () -> r.attempt(() -> r.cluster.read("n1", "sku-1")),
                "n1 está vivo y tiene el dato, pero no puede garantizar que sea el último: rechaza (C sobre A).");
        r.step("🩹 Vuelve n2",
                () -> {
                    r.cluster.nodeUp("n2");
                    return "n2 en línea; vuelve a haber 2 de 3.";
                },
                "Recuperado el quórum, el servicio se restablece sin intervención.");
        r.step("GET sku-1 vía n1",
                () -> r.attempt(() -> r.cluster.read("n1", "sku-1")),
                "Lectura correcta por quórum (n1 + n2).");
        r.step("🩹 Vuelve n3",
                () -> {
                    r.cluster.nodeUp("n3");
                    return r.replicas("sku-1");
                },
                "n3 estaba atrasado (qty=10); al reincorporarse, la anti-entropía lo actualiza antes de volver a servir.");

        return r.report(info, List.of(
                "Un clúster de N nodos con quórum de mayoría tolera f = ⌊(N-1)/2⌋ fallos: con 3 nodos, 1; con 5 nodos, 2.",
                "Más nodos no mejoran la disponibilidad por sí solos si se pierde la mayoría: importa cuántos pueden fallar a la vez.",
                "Un nodo caído que reaparece con datos viejos nunca contamina lecturas: el quórum devuelve la versión más reciente."));
    }

    // ─── HELPERS ────────────────────────────────────

    private static final class Run {
        final ClusterService cluster = new ClusterService(Clock.systemUTC(), List.of("n1", "n2", "n3"), 0);
        final List<Step> steps = new ArrayList<>();

        void step(String action, Supplier<String> body, String insight) {
            steps.add(new Step(steps.size() + 1, action, body.get(), insight));
        }

        String attempt(Supplier<OpResult<VersionedRecord>> operation) {
            try {
                OpResult<VersionedRecord> result = operation.get();
                VersionedRecord record = result.data();
                String value = record.deleted() ? "borrado" : "qty=" + record.data().quantity();
                return "✅ OK · %s · nodos que participaron: %d (se requieren %d) · obsoleto: %s".formatted(
                        value, result.meta().nodesContacted(), result.meta().required(), result.meta().stale() ? "SÍ ⚠" : "no");
            } catch (LabException e) {
                return "⛔ %d %s — %s".formatted(e.status(), e.code(), e.getMessage());
            }
        }

        String replicas(String key) {
            return "Réplicas de " + key + ": " + cluster.snapshot().nodes().stream()
                    .map(n -> n.id() + "=" + n.records().stream().filter(x -> x.key().equals(key)).findFirst()
                            .map(x -> x.deleted() ? "borrado" : String.valueOf(x.quantity())).orElse("∅"))
                    .collect(Collectors.joining(" · "));
        }

        Report report(Info info, List<String> conclusions) {
            return new Report(info.id(), info.title(), info.goal(), List.copyOf(steps), conclusions);
        }
    }
}
