package com.pragma.caplab.core;

import com.pragma.caplab.domain.VersionedRecord;

/**
 * Mensaje de replicación asíncrona pendiente (en AP). Mientras el destino no sea alcanzable
 * permanece en la cola, igual que un "hinted handoff" en Dynamo/Cassandra.
 *
 * @author Raul Bolivar Navas.
 */
record ReplicationMessage(
        long id,
        String from,
        String to,
        VersionedRecord record,
        long deliverAt
) {
}
