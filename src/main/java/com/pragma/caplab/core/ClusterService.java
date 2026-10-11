package com.pragma.caplab.core;

import com.pragma.caplab.domain.ItemData;
import com.pragma.caplab.domain.LabException;
import com.pragma.caplab.domain.MergeResult;
import com.pragma.caplab.domain.Mode;
import com.pragma.caplab.domain.VectorClock;
import com.pragma.caplab.domain.VersionedRecord;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Clúster simulado en memoria: N nodos con su propio almacén, particiones de red, caídas de nodo
 * y dos estrategias intercambiables en caliente.
 *
 * <h2>CP (Consistencia + Tolerancia a particiones)</h2>
 * Cada operación exige un <b>quórum</b> (mayoría) de nodos alcanzables. Las escrituras se replican de
 * forma síncrona a todos los alcanzables y las lecturas devuelven la versión más reciente del quórum.
 * Un nodo en minoría <b>rechaza</b> la petición: sacrifica Disponibilidad para no servir datos incoherentes.
 *
 * <h2>AP (Disponibilidad + Tolerancia a particiones) — filosofía BASE</h2>
 * <ul>
 *   <li><b>B</b>asically <b>A</b>vailable: cualquier nodo vivo atiende leyendo/escribiendo en su copia local.</li>
 *   <li><b>S</b>oft state: las réplicas pueden diverger temporalmente (se observa en el panel).</li>
 *   <li><b>E</b>ventual consistency: la replicación asíncrona (con "hinted handoff") y la resolución de
 *       conflictos (relojes vectoriales + LWW) hacen que todas las réplicas converjan al sanar la red.</li>
 * </ul>
 *
 * Todas las operaciones públicas son síncronas y están serializadas; la latencia de red simulada se
 * aplica en la capa reactiva ({@code ItemService}) para mantener este núcleo determinista y testeable.
 *
 * @author Raul Bolivar Navas.
 */
public class ClusterService {

    private static final int MAX_EVENTS = 300;

    private enum Kind { CREATE, UPDATE, DELETE }

    private static final class Node {
        final String id;
        boolean up = true;
        int partition = 0;
        final Map<String, VersionedRecord> store = new TreeMap<>();

        Node(String id) {
            this.id = id;
        }
    }

    private final Clock clock;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<ReplicationMessage> queue = new ArrayList<>();
    private final Deque<ClusterEvent> events = new ArrayDeque<>();
    private final Set<String> conflictIds = new HashSet<>();

    private Mode mode = Mode.CP;
    private long latencyMs;
    private long lastTimestamp;
    private long sequence;
    private long delivered;
    private long rejected;

    public ClusterService(Clock clock, List<String> nodeIds, long latencyMs) {
        this.clock = clock;
        this.latencyMs = latencyMs;
        nodeIds.forEach(id -> nodes.put(id, new Node(id)));
    }

    // ------------------------------------------------------------------ CRUD

    public synchronized OpResult<VersionedRecord> create(String nodeId, String key, ItemData data) {
        return write(nodeId, key, Kind.CREATE, data);
    }

    public synchronized OpResult<VersionedRecord> update(String nodeId, String key, ItemData data) {
        return write(nodeId, key, Kind.UPDATE, data);
    }

    public synchronized OpResult<VersionedRecord> delete(String nodeId, String key) {
        return write(nodeId, key, Kind.DELETE, null);
    }

    public synchronized OpResult<VersionedRecord> read(String nodeId, String key) {
        Node coordinator = coordinator(nodeId);
        List<Node> reachable = reachableFrom(coordinator);
        VersionedRecord record;
        String note;
        List<String> contacted;

        if (mode == Mode.CP) {
            requireQuorum(coordinator, reachable, "READ " + key);
            record = best(reachable, key);
            if (record != null) {
                readRepair(reachable, record);
            }
            contacted = ids(reachable);
            note = "Lectura por quórum: se consultaron %d nodos y se devolvió la versión más reciente."
                    .formatted(reachable.size());
        } else {
            record = coordinator.store.get(key);
            contacted = List.of(coordinator.id);
            note = "Lectura local sin coordinación: responde siempre, pero puede ser un dato obsoleto.";
        }

        if (record == null || record.deleted()) {
            throw notFound(coordinator, key);
        }
        boolean stale = isStale(record, oracle(key));
        log("READ", coordinator.id, "GET %s → qty=%d%s".formatted(
                key, record.data().quantity(), stale ? " (OBSOLETO)" : ""));
        return new OpResult<>(record, new OpMeta(coordinator.id, mode, contacted.size(),
                mode == Mode.CP ? quorum() : 1, contacted, stale, note, 0));
    }

    public synchronized OpResult<List<VersionedRecord>> list(String nodeId) {
        Node coordinator = coordinator(nodeId);
        List<Node> reachable = reachableFrom(coordinator);
        Map<String, VersionedRecord> view = new TreeMap<>();
        List<String> contacted;
        String note;

        if (mode == Mode.CP) {
            requireQuorum(coordinator, reachable, "LIST");
            for (Node n : reachable) {
                n.store.values().forEach(r -> view.merge(r.key(), r, (a, b) -> a.merge(b).result()));
            }
            contacted = ids(reachable);
            note = "Listado por quórum sobre %d nodos.".formatted(reachable.size());
        } else {
            view.putAll(coordinator.store);
            contacted = List.of(coordinator.id);
            note = "Listado de la copia local del nodo.";
        }

        int staleItems = 0;
        for (String key : oracleKeys()) {
            if (isStale(view.get(key), oracle(key))) {
                staleItems++;
            }
        }
        if (staleItems > 0) {
            note += " ⚠ %d ítem(s) desactualizado(s) o ausente(s) respecto a la verdad global.".formatted(staleItems);
        }
        List<VersionedRecord> live = view.values().stream().filter(r -> !r.deleted()).toList();
        log("READ", coordinator.id, "LIST → %d ítem(s)%s".formatted(live.size(), staleItems > 0 ? " (con datos obsoletos)" : ""));
        return new OpResult<>(live, new OpMeta(coordinator.id, mode, contacted.size(),
                mode == Mode.CP ? quorum() : 1, contacted, staleItems > 0, note, 0));
    }

    private OpResult<VersionedRecord> write(String nodeId, String key, Kind kind, ItemData data) {
        Node coordinator = coordinator(nodeId);
        List<Node> reachable = reachableFrom(coordinator);
        String opName = kind + " " + key;

        VersionedRecord current;
        if (mode == Mode.CP) {
            requireQuorum(coordinator, reachable, opName);
            current = best(reachable, key);
        } else {
            current = coordinator.store.get(key);
        }

        boolean exists = current != null && !current.deleted();
        if (kind == Kind.CREATE && exists) {
            throw new LabException.AlreadyExists(key, coordinator.id);
        }
        if (kind != Kind.CREATE && !exists) {
            throw notFound(coordinator, key);
        }

        VectorClock clockBase = current == null ? VectorClock.empty() : current.clock();
        VectorClock newClock = clockBase.increment(coordinator.id);
        long timestamp = nextTimestamp();
        VersionedRecord record = kind == Kind.DELETE
                ? VersionedRecord.tombstone(key, newClock, timestamp, coordinator.id)
                : VersionedRecord.of(key, data, newClock, timestamp, coordinator.id);

        List<String> contacted;
        String note;
        int required;
        if (mode == Mode.CP) {
            reachable.forEach(n -> n.store.put(key, record));
            contacted = ids(reachable);
            required = quorum();
            note = "Replicación síncrona confirmada por %d de %d nodos (quórum %d)."
                    .formatted(reachable.size(), nodes.size(), required);
            log("WRITE", coordinator.id, "%s ✓ replicado síncronamente en %s".formatted(describe(kind, record), contacted));
        } else {
            coordinator.store.put(key, record);
            int queued = 0;
            for (Node n : nodes.values()) {
                if (n != coordinator) {
                    queue.add(new ReplicationMessage(++sequence, coordinator.id, n.id, record, clock.millis() + latencyMs));
                    queued++;
                }
            }
            contacted = List.of(coordinator.id);
            required = 1;
            note = "Confirmado con la copia local; replicación asíncrona encolada hacia %d nodo(s). "
                    .formatted(queued) + "Los demás aún pueden ver el valor anterior (estado blando).";
            log("WRITE", coordinator.id, "%s ✓ confirmado localmente, %d replicación(es) en cola".formatted(
                    describe(kind, record), queued));
        }
        return new OpResult<>(record, new OpMeta(coordinator.id, mode, contacted.size(), required, contacted, false, note, 0));
    }

    // ------------------------------------------------------------ replicación

    /**
     * Entrega los mensajes de replicación vencidos cuyo destino sea alcanzable.
     * Los que no lo sean permanecen en cola (hinted handoff).
     *
     * @param ignoreDelay true para entregar sin esperar la latencia simulada
     */
    public synchronized int deliverDue(boolean ignoreDelay) {
        long now = clock.millis();
        int count = 0;
        Iterator<ReplicationMessage> it = queue.iterator();
        while (it.hasNext()) {
            ReplicationMessage m = it.next();
            if (!ignoreDelay && m.deliverAt() > now) {
                continue;
            }
            Node source = nodes.get(m.from());
            Node target = nodes.get(m.to());
            if (!canTalk(source, target)) {
                continue;
            }
            it.remove();
            boolean changed = mergeInto(target, m.record());
            delivered++;
            count++;
            log("REPLICATE", target.id, "%s → %s: %s %s".formatted(
                    m.from(), m.to(), m.record().key(), changed ? "aplicado" : "ignorado (ya tenía una versión igual o más reciente)"));
        }
        return count;
    }

    /**
     * Anti-entropía: dentro de cada grupo de nodos mutuamente alcanzables, une todas las versiones
     * de cada clave y las copia a todos. Es la red de seguridad que garantiza la convergencia.
     */
    public synchronized int antiEntropy() {
        int repaired = 0;
        Map<Integer, List<Node>> groups = nodes.values().stream()
                .filter(n -> n.up)
                .collect(Collectors.groupingBy(n -> n.partition, LinkedHashMap::new, Collectors.toList()));
        for (List<Node> group : groups.values()) {
            Set<String> keys = new TreeSet<>();
            group.forEach(n -> keys.addAll(n.store.keySet()));
            for (String key : keys) {
                List<VersionedRecord> originals = group.stream()
                        .map(n -> n.store.get(key)).filter(r -> r != null).toList();
                for (Node n : group) {
                    for (VersionedRecord r : originals) {
                        if (mergeInto(n, r)) {
                            repaired++;
                        }
                    }
                }
            }
        }
        log("REPAIR", null, repaired > 0
                ? "Anti-entropía: %d copia(s) reparada(s)".formatted(repaired)
                : "Anti-entropía: las réplicas alcanzables ya estaban sincronizadas");
        return repaired;
    }

    // -------------------------------------------------------------- topología

    public synchronized void partition(List<List<String>> groups) {
        Map<String, Integer> assignment = new LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            for (String id : groups.get(i)) {
                if (!nodes.containsKey(id)) {
                    throw new LabException.UnknownNode(id);
                }
                if (assignment.put(id, i) != null) {
                    throw new LabException.InvalidTopology("El nodo %s aparece en más de un grupo.".formatted(id));
                }
            }
        }
        if (assignment.size() != nodes.size()) {
            throw new LabException.InvalidTopology("Todos los nodos deben aparecer exactamente una vez en los grupos.");
        }
        assignment.forEach((id, group) -> nodes.get(id).partition = group);
        log("PARTITION", null, "Partición de red: " + groups.stream()
                .map(g -> "{" + String.join(", ", g) + "}").collect(Collectors.joining(" | ")));
    }

    public synchronized void heal() {
        nodes.values().forEach(n -> n.partition = 0);
        log("HEAL", null, "La red se reparó: todos los nodos vuelven a verse");
        if (mode == Mode.CP) {
            antiEntropy();
        }
    }

    public synchronized void nodeDown(String id) {
        node(id).up = false;
        log("NODE", id, "El nodo %s se cayó".formatted(id));
    }

    public synchronized void nodeUp(String id) {
        node(id).up = true;
        log("NODE", id, "El nodo %s volvió a estar en línea".formatted(id));
        if (mode == Mode.CP) {
            antiEntropy();
        }
    }

    public synchronized void setMode(Mode newMode) {
        if (newMode != mode) {
            mode = newMode;
            log("MODE", null, "Modo del clúster cambiado a " + newMode
                    + (newMode == Mode.CP ? " (consistencia con quórum)" : " (disponibilidad con consistencia eventual)"));
        }
    }

    public synchronized void setLatencyMs(long ms) {
        latencyMs = ms;
        log("CONFIG", null, "Latencia de red simulada: " + ms + " ms");
    }

    public synchronized void reset() {
        nodes.values().forEach(n -> {
            n.up = true;
            n.partition = 0;
            n.store.clear();
        });
        queue.clear();
        events.clear();
        conflictIds.clear();
        delivered = 0;
        rejected = 0;
        log("RESET", null, "Laboratorio reiniciado (modo %s, latencia %d ms)".formatted(mode, latencyMs));
    }

    // ---------------------------------------------------------------- lectura

    public synchronized Mode mode() {
        return mode;
    }

    public synchronized long latencyMs() {
        return latencyMs;
    }

    public synchronized List<String> nodeIds() {
        return List.copyOf(nodes.keySet());
    }

    public synchronized List<ClusterEvent> events(int limit) {
        List<ClusterEvent> all = new ArrayList<>(events);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }

    public synchronized ClusterSnapshot snapshot() {
        long now = clock.millis();
        List<ClusterSnapshot.NodeView> nodeViews = nodes.values().stream()
                .map(n -> new ClusterSnapshot.NodeView(n.id, n.up, n.partition,
                        n.store.values().stream().map(ClusterSnapshot.RecordView::from).toList()))
                .toList();

        List<ClusterSnapshot.PendingView> pending = queue.stream().map(m -> {
            Node s = nodes.get(m.from());
            Node t = nodes.get(m.to());
            String status = !s.up || !t.up ? "bloqueado (nodo caído)"
                    : s.partition != t.partition ? "bloqueado (partición de red)"
                    : "en tránsito";
            return new ClusterSnapshot.PendingView(m.id(), m.from(), m.to(), m.record().key(),
                    Math.max(0, m.deliverAt() - now), status);
        }).toList();

        Set<String> divergent = new TreeSet<>();
        Set<String> allKeys = new TreeSet<>();
        nodes.values().forEach(n -> allKeys.addAll(n.store.keySet()));
        for (String key : allKeys) {
            VersionedRecord first = null;
            boolean firstSet = false;
            for (Node n : nodes.values()) {
                VersionedRecord r = n.store.get(key);
                if (!firstSet) {
                    first = r;
                    firstSet = true;
                } else if (first == null ? r != null : !first.sameContentAs(r)) {
                    divergent.add(key);
                }
            }
        }

        String summary = mode == Mode.CP
                ? "CP · Consistencia sobre Disponibilidad. Cada operación exige quórum (%d de %d nodos). ".formatted(quorum(), nodes.size())
                + "Un nodo en minoría rechaza peticiones (503) en vez de servir datos dudosos. "
                + "Costo (PACELC): cada operación espera ~%d ms de replicación síncrona.".formatted(latencyMs)
                : "AP · Disponibilidad sobre Consistencia (BASE). Todo nodo vivo atiende con su copia local y replica de forma asíncrona. "
                + "Puede haber lecturas obsoletas y conflictos que se resuelven al converger (LWW). "
                + "Ganancia (PACELC): latencia ≈ 0 ms en lecturas y escrituras.";

        return new ClusterSnapshot(mode, latencyMs, quorum(), nodes.size(), nodeViews, pending,
                new ClusterSnapshot.Convergence(divergent.isEmpty(), List.copyOf(divergent)),
                new ClusterSnapshot.Stats(conflictIds.size(), delivered, rejected, queue.size()),
                summary);
    }

    // ---------------------------------------------------------------- internos

    private int quorum() {
        return nodes.size() / 2 + 1;
    }

    private Node node(String id) {
        Node n = nodes.get(id);
        if (n == null) {
            throw new LabException.UnknownNode(id);
        }
        return n;
    }

    private Node coordinator(String id) {
        Node n = node(id);
        if (!n.up) {
            rejected++;
            log("REJECT", id, "Petición rechazada: el nodo %s está caído".formatted(id));
            throw new LabException.NodeDown(id);
        }
        return n;
    }

    private boolean canTalk(Node a, Node b) {
        return a.up && b.up && a.partition == b.partition;
    }

    private List<Node> reachableFrom(Node c) {
        return nodes.values().stream().filter(n -> canTalk(c, n)).toList();
    }

    private void requireQuorum(Node coordinator, List<Node> reachable, String operation) {
        if (reachable.size() < quorum()) {
            rejected++;
            log("REJECT", coordinator.id, "%s ✗ rechazada: solo alcanza %d de %d nodos (quórum %d) → se sacrifica la disponibilidad"
                    .formatted(operation, reachable.size(), nodes.size(), quorum()));
            throw new LabException.QuorumUnavailable(coordinator.id, reachable.size(), quorum(), nodes.size());
        }
    }

    private long nextTimestamp() {
        lastTimestamp = Math.max(lastTimestamp + 1, clock.millis());
        return lastTimestamp;
    }

    /** Une las versiones de {@code key} en los nodos dados (sin efectos secundarios). */
    private VersionedRecord best(Collection<Node> from, String key) {
        VersionedRecord acc = null;
        for (Node n : from) {
            VersionedRecord r = n.store.get(key);
            if (r != null) {
                acc = acc == null ? r : acc.merge(r).result();
            }
        }
        return acc;
    }

    /** Verdad global del laboratorio: todos los nodos (incluso caídos) más lo que aún viaja por la red. */
    private VersionedRecord oracle(String key) {
        VersionedRecord acc = best(nodes.values(), key);
        for (ReplicationMessage m : queue) {
            if (m.record().key().equals(key)) {
                acc = acc == null ? m.record() : acc.merge(m.record()).result();
            }
        }
        return acc;
    }

    private Set<String> oracleKeys() {
        Set<String> keys = new TreeSet<>();
        nodes.values().forEach(n -> keys.addAll(n.store.keySet()));
        queue.forEach(m -> keys.add(m.record().key()));
        return keys;
    }

    private static boolean isStale(VersionedRecord local, VersionedRecord truth) {
        return truth != null && (local == null || truth.timestamp() > local.timestamp());
    }

    private void readRepair(List<Node> reachable, VersionedRecord latest) {
        for (Node n : reachable) {
            VersionedRecord existing = n.store.get(latest.key());
            if (!latest.equals(existing)) {
                n.store.put(latest.key(), latest);
                log("REPAIR", n.id, "Read-repair: %s actualizó '%s' a la versión más reciente".formatted(n.id, latest.key()));
            }
        }
    }

    /** Aplica un registro entrante a un nodo resolviendo conflictos. Devuelve true si el nodo cambió. */
    private boolean mergeInto(Node target, VersionedRecord incoming) {
        VersionedRecord existing = target.store.get(incoming.key());
        if (existing == null) {
            target.store.put(incoming.key(), incoming);
            return true;
        }
        MergeResult merge = existing.merge(incoming);
        if (merge.conflict()) {
            registerConflict(target, merge, existing, incoming);
        }
        if (merge.changed()) {
            target.store.put(incoming.key(), merge.result());
        }
        return merge.changed();
    }

    private void registerConflict(Node where, MergeResult merge, VersionedRecord a, VersionedRecord b) {
        String idA = a.origin() + "@" + a.timestamp();
        String idB = b.origin() + "@" + b.timestamp();
        String conflictId = a.key() + "|" + (idA.compareTo(idB) < 0 ? idA + "|" + idB : idB + "|" + idA);
        if (conflictIds.add(conflictId)) {
            VersionedRecord winner = merge.result();
            VersionedRecord loser = merge.loser();
            log("CONFLICT", where.id, ("Conflicto en '%s': escrituras concurrentes de %s y %s (relojes %s vs %s). "
                    + "Last-Write-Wins conserva %s y descarta %s.").formatted(
                    a.key(), a.origin(), b.origin(), a.clock(), b.clock(), summarize(winner), summarize(loser)));
        }
    }

    private static String summarize(VersionedRecord r) {
        return r.deleted() ? "[borrado por " + r.origin() + "]"
                : "[%s qty=%d de %s]".formatted(r.data().name(), r.data().quantity(), r.origin());
    }

    private static String describe(Kind kind, VersionedRecord r) {
        return switch (kind) {
            case CREATE -> "POST %s (%s, qty=%d)".formatted(r.key(), r.data().name(), r.data().quantity());
            case UPDATE -> "PUT %s (%s, qty=%d)".formatted(r.key(), r.data().name(), r.data().quantity());
            case DELETE -> "DELETE %s".formatted(r.key());
        };
    }

    private LabException notFound(Node coordinator, String key) {
        VersionedRecord local = coordinator.store.get(key);
        VersionedRecord truth = oracle(key);
        String message = "El ítem '%s' no existe en la vista del nodo %s.".formatted(key, coordinator.id);
        if (isStale(local, truth) && !truth.deleted()) {
            message += " Sí existe en otra parte del clúster: la replicación aún no llegó a este nodo "
                    + "(consistencia eventual / lectura obsoleta).";
        }
        return new LabException.NotFound(message);
    }

    private static List<String> ids(Collection<Node> nodes) {
        return nodes.stream().map(n -> n.id).toList();
    }

    private void log(String type, String node, String message) {
        events.addLast(new ClusterEvent(++sequence, clock.millis(), type, node, message));
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }
}
