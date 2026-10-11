package com.pragma.caplab.domain;

/**
 * Estrategia del clúster frente a una partición de red (teorema CAP).
 * <ul>
 *   <li>{@link #CP}: prioriza Consistencia. Exige quórum; la minoría rechaza peticiones.</li>
 *   <li>{@link #AP}: prioriza Disponibilidad. Cada nodo responde con su copia local (BASE).</li>
 * </ul>
 *
 * @author Raul Bolivar Navas.
 */
public enum Mode {
    CP,
    AP
}
