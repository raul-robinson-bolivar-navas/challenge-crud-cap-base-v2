package com.pragma.caplab.core;

/**
 * Entrada del registro de eventos del clúster.
 *
 * @param type WRITE, READ, REJECT, REPLICATE, CONFLICT, REPAIR, PARTITION, HEAL, NODE, MODE, CONFIG, RESET
 *
 * @author Raul Bolivar Navas.
 */
public record ClusterEvent(long seq, long at, String type, String node, String message) {
}
