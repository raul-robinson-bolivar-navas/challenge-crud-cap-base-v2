package com.pragma.caplab.domain;

/**
 * Resultado de comparar dos relojes vectoriales (relación "happened-before").
 *
 * @author Raul Bolivar Navas.
 * */
public enum Ordering {
    BEFORE,
    AFTER,
    EQUAL,
    CONCURRENT
}
