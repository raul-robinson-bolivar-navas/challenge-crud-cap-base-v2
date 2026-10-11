package com.pragma.caplab.domain;

/**
 * Payload del recurso que gestiona el CRUD (un ítem de inventario).
 *
 * @author Raul Bolivar Navas.
 * */
public record ItemData(String name, int quantity) {
}
