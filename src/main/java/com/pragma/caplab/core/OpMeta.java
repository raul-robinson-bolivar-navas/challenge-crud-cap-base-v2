package com.pragma.caplab.core;

import com.pragma.caplab.domain.Mode;
import java.util.List;

/**
 * Metadatos "didácticos" de cada operación: qué nodo la atendió, cuántos nodos participaron,
 * si el dato devuelto es obsoleto respecto a la verdad global y cuánto tardó.
 *
 * @param servedBy       nodo coordinador (aquel al que se conectó el cliente)
 * @param mode           modo del clúster al ejecutar
 * @param nodesContacted nodos que participaron en la operación
 * @param required       participantes exigidos (quórum en CP, 1 en AP)
 * @param contactedNodes ids de los nodos que participaron
 * @param stale          true si lo devuelto es más viejo que la verdad global (solo detectable por el laboratorio)
 * @param note           explicación en lenguaje natural de lo ocurrido
 * @param latencyMs      latencia medida de la operación
 *
 * @author Raul Bolivar Navas.
 */
public record OpMeta(
        String servedBy,
        Mode mode,
        int nodesContacted,
        int required,
        List<String> contactedNodes,
        boolean stale,
        String note,
        long latencyMs) {

    public OpMeta withLatency(long ms) {
        return new OpMeta(servedBy, mode, nodesContacted, required, contactedNodes, stale, note, ms);
    }
}
