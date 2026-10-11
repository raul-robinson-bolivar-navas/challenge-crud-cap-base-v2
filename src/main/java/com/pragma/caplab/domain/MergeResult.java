package com.pragma.caplab.domain;

/**
 * Resultado de unir dos versiones de un mismo registro.
 *
 * @param result   registro resultante
 * @param changed  true si el resultado es distinto del registro local previo
 * @param conflict true si ambas versiones eran concurrentes (ninguna "vio" a la otra)
 * @param loser    versión descartada por Last-Write-Wins cuando hubo conflicto; null si no
 *
 * @author Raul Bolivar Navas.
 */
public record MergeResult(VersionedRecord result, boolean changed, boolean conflict, VersionedRecord loser) {
}
