package com.pragma.caplab.domain;

/**
 * Versión de un registro tal como la almacena una réplica.
 *
 * <p>La unión de dos versiones ({@link #merge}) es un <b>semirretículo</b>: conmutativa, asociativa
 * e idempotente. Esa es la propiedad que garantiza <b>consistencia eventual</b>: sin importar en qué
 * orden lleguen los mensajes de replicación, todas las réplicas terminan en el mismo estado.
 *
 * <ul>
 *   <li>Datos: gana la escritura con mayor (timestamp, origen) — Last-Write-Wins.</li>
 *   <li>Reloj vectorial: máximo componente a componente. Se usa para <i>detectar</i> conflictos
 *       (escrituras concurrentes) y para no re-detectar el mismo conflicto después de resolverlo.</li>
 * </ul>
 *
 * @param key       identificador del ítem
 * @param data      contenido (null si {@code deleted})
 * @param deleted   true si es una lápida (tombstone); el borrado también se replica
 * @param clock     reloj vectorial
 * @param timestamp marca temporal lógica global, estrictamente creciente
 * @param origin    nodo que coordinó la escritura
 *
 * @author Raul Bolivar Navas.
 */
public record VersionedRecord(
        String key,
        ItemData data,
        boolean deleted,
        VectorClock clock,
        long timestamp,
        String origin
) {

    public static VersionedRecord of(String key, ItemData data, VectorClock clock, long timestamp, String origin) {
        return new VersionedRecord(key, data, false, clock, timestamp, origin);
    }

    public static VersionedRecord tombstone(String key, VectorClock clock, long timestamp, String origin) {
        return new VersionedRecord(key, null, true, clock, timestamp, origin);
    }

    private int precedence(VersionedRecord other) {
        int byTime = Long.compare(timestamp, other.timestamp);
        return byTime != 0 ? byTime : origin.compareTo(other.origin);
    }

    public MergeResult merge(VersionedRecord incoming) {
        boolean concurrent = clock.compare(incoming.clock) == Ordering.CONCURRENT;
        boolean incomingWins = incoming.precedence(this) > 0;
        VersionedRecord winner = incomingWins ? incoming : this;
        VersionedRecord loser = incomingWins ? this : incoming;
        VersionedRecord result = new VersionedRecord(
                key, winner.data, winner.deleted, clock.merge(incoming.clock), winner.timestamp, winner.origin);
        boolean changed = !result.equals(this);
        return new MergeResult(result, changed, concurrent, concurrent ? loser : null);
    }

    /** Igualdad de contenido observable (ignora el reloj vectorial). Usada para medir convergencia. */
    public boolean sameContentAs(VersionedRecord other) {
        return other != null
                && deleted == other.deleted
                && timestamp == other.timestamp
                && java.util.Objects.equals(data, other.data);
    }
}
