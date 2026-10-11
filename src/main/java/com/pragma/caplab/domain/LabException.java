package com.pragma.caplab.domain;

import java.util.Map;

/**
 * Errores de negocio del laboratorio, con el código HTTP que les corresponde.
 *
 * @author Raul Bolivar Navas.
 * */
public abstract class LabException extends RuntimeException {

    private final int status;
    private final String code;

    protected LabException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return Map.of();
    }

    /** Modo CP: el nodo no alcanza a una mayoría. Es el "sacrificio" de la disponibilidad. */
    public static final class QuorumUnavailable extends LabException {
        private final int reachable;
        private final int required;

        public QuorumUnavailable(String node, int reachable, int required, int total) {
            super(503, "CP_QUORUM_UNAVAILABLE",
                    ("Modo CP: el nodo %s solo alcanza %d de %d nodos y se requiere un quórum de %d. "
                            + "Se rechaza la operación para no romper la consistencia "
                            + "(se sacrifica la disponibilidad).").formatted(node, reachable, total, required));
            this.reachable = reachable;
            this.required = required;
        }

        @Override
        public Map<String, Object> details() {
            return Map.of("reachableNodes", reachable, "requiredQuorum", required);
        }
    }

    public static final class NodeDown extends LabException {
        public NodeDown(String node) {
            super(503, "NODE_DOWN", "El nodo %s está caído y no puede atender peticiones.".formatted(node));
        }
    }

    public static final class NotFound extends LabException {
        public NotFound(String message) {
            super(404, "NOT_FOUND", message);
        }
    }

    public static final class AlreadyExists extends LabException {
        public AlreadyExists(String key, String node) {
            super(409, "ALREADY_EXISTS", "El ítem '%s' ya existe en la vista del nodo %s.".formatted(key, node));
        }
    }

    public static final class UnknownNode extends LabException {
        public UnknownNode(String node) {
            super(404, "UNKNOWN_NODE", "El nodo '%s' no existe en el clúster.".formatted(node));
        }
    }

    public static final class InvalidTopology extends LabException {
        public InvalidTopology(String message) {
            super(400, "INVALID_TOPOLOGY", message);
        }
    }
}
