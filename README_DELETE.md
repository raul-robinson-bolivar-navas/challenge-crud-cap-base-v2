# Fase 3 — Eliminación (DELETE) y optimización: CAP, BASE y reconciliación

Este documento demuestra cómo se elimina un registro en el clúster simulado de 3 nodos (`n1`, `n2`, `n3`),
qué decisión CAP toma cada modo y cómo se reconcilian las réplicas.

> **Nota de alcance:** el dominio del laboratorio son *ítems de inventario* (`/api/items`), no "usuarios".
> La mecánica de borrado es idéntica para cualquier entidad; basta con leer "ítem" como "usuario".

## 1. Cómo funciona el DELETE

`DELETE /api/items/{id}?node=n1` → `ClusterService#delete` → `write(..., Kind.DELETE)`.

El borrado **no elimina físicamente** el dato: escribe una **lápida (tombstone)**
(`VersionedRecord.tombstone`), que es una versión más del registro con `deleted=true`, su reloj vectorial y
su timestamp. Así el borrado viaja por los mismos canales que cualquier escritura y puede ganarle (o perderle)
a otras versiones de forma determinista.

| | **CP** | **AP (BASE)** |
|---|---|---|
| Coordinador | exige **quórum** (2/3) | cualquier nodo vivo |
| Replicación de la lápida | **síncrona** a todos los alcanzables | **asíncrona**: se confirma en local y se encola hacia los demás (`ReplicationMessage`) |
| Respuesta | `nodesContacted ≥ 2`, `required = 2` | `nodesContacted = 1`, `required = 1` |
| Minoría de una partición | `503 CP_QUORUM_UNAVAILABLE` | acepta el DELETE |
| Lectura posterior | 404 en todos los nodos alcanzables | 404 en el coordinador; **otros nodos pueden seguir sirviendo el ítem** hasta que llegue la lápida (estado blando) |
| `DELETE` de algo inexistente | `404 NOT_FOUND` | `404 NOT_FOUND` (según la vista del nodo) |

## 2. Decisión CAP documentada

El modo es **conmutable en caliente** (`POST /api/cluster/mode`), por lo que la decisión es explícita y por escenario:

- **CP — Consistencia sobre Disponibilidad.** Un borrado solo se acepta si una mayoría lo confirma. Ante una partición,
  el nodo minoritario *rechaza* el DELETE en vez de crear una divergencia. Recomendado para datos donde un borrado
  "a medias" es inaceptable (cuentas, saldos, permisos).
- **AP — Disponibilidad sobre Consistencia (BASE).** Cualquier nodo vivo acepta el DELETE y la lápida se propaga
  después (*hinted handoff* mientras el destino esté caído/aislado). Se tolera que otros nodos muestren el ítem durante la
  ventana de replicación. Recomendado para catálogos, carritos, contenido.

Coste PACELC: CP paga latencia en cada DELETE (espera la replicación síncrona); AP responde en ~0 ms.

## 3. Reconciliación (cómo convergen las réplicas tras un borrado)

| Mecanismo | Dónde | Cuándo actúa |
|---|---|---|
| **Replicación asíncrona + hinted handoff** | `deliverDue`, `ReplicationScheduler` (cada 100 ms) | Entrega la lápida en cuanto el destino es alcanzable; si no, queda en cola |
| **Read-repair** | `ClusterService#readRepair` (en `read`, rama CP) | Una lectura por quórum devuelve la versión más reciente (también una lápida) y la escribe en las réplicas que no la tenían |
| **Anti-entropía** | `ClusterService#antiEntropy` | Une todas las versiones de cada clave entre nodos mutuamente alcanzables. En CP se ejecuta automáticamente al `heal` y `nodeUp`; en AP se dispara manualmente (`POST /api/cluster/anti-entropy`, botón del panel) |
| **Merge determinista** | `VersionedRecord#merge` | Semirretículo conmutativo, asociativo e idempotente: el orden de llegada no importa, así que una lápida **no resucita** por un mensaje tardío |
| **Detección + resolución de conflictos** | `VectorClock#compare` + LWW | DELETE concurrente con UPDATE: se registra un evento `CONFLICT` y gana la escritura con mayor `(timestamp, origen)` |

## 4. Demostración paso a paso

```bash
H='Content-Type: application/json'
curl -s -XPOST localhost:8080/api/cluster/reset >/dev/null
```

### A. AP: DELETE asíncrono y estado blando
```bash
curl -s -XPOST localhost:8080/api/cluster/mode -H "$H" -d '{"mode":"AP"}' >/dev/null
curl -s -XPOST localhost:8080/api/cluster/latency -H "$H" -d '{"ms":5000}' >/dev/null
curl -s -XPOST 'localhost:8080/api/items?node=n1' -H "$H" -d '{"id":"sku-1","name":"Café","quantity":10}'
curl -s -XPOST localhost:8080/api/cluster/replicate-now >/dev/null     # n2 y n3 ya lo tienen

curl -s -XDELETE 'localhost:8080/api/items/sku-1?node=n1'              # meta.nodesContacted=1, replicación en cola
curl -si 'localhost:8080/api/items/sku-1?node=n1' | head -1           # 404 (coordinador)
curl -si 'localhost:8080/api/items/sku-1?node=n3' | head -1           # 200  ← dato "borrado" aún visible (soft state)
curl -s -XPOST localhost:8080/api/cluster/replicate-now >/dev/null
curl -si 'localhost:8080/api/items/sku-1?node=n3' | head -1           # 404 (consistencia eventual)
```

### B. AP: DELETE en un nodo aislado
```bash
curl -s -XPOST 'localhost:8080/api/items?node=n1' -H "$H" -d '{"id":"sku-2","name":"Té","quantity":5}' >/dev/null
curl -s -XPOST localhost:8080/api/cluster/replicate-now >/dev/null
curl -s -XPOST localhost:8080/api/cluster/partition -H "$H" -d '{"groups":[["n1"],["n2","n3"]]}' >/dev/null
curl -s -XDELETE 'localhost:8080/api/items/sku-2?node=n1'              # aceptado (Basically Available)
curl -s -XPOST localhost:8080/api/cluster/replicate-now                # replicación bloqueada por la partición
curl -s -XPOST localhost:8080/api/cluster/heal >/dev/null
curl -s -XPOST localhost:8080/api/cluster/replicate-now                # la lápida llega a n2 y n3
```

### C. CP: DELETE exige quórum
```bash
curl -s -XPOST localhost:8080/api/cluster/reset >/dev/null
curl -s -XPOST localhost:8080/api/cluster/mode -H "$H" -d '{"mode":"CP"}' >/dev/null
curl -s -XPOST 'localhost:8080/api/items?node=n1' -H "$H" -d '{"id":"sku-3","name":"Pan","quantity":1}' >/dev/null
curl -s -XPOST localhost:8080/api/cluster/partition -H "$H" -d '{"groups":[["n1"],["n2","n3"]]}' >/dev/null
curl -si -XDELETE 'localhost:8080/api/items/sku-3?node=n1' | head -1  # 503 CP_QUORUM_UNAVAILABLE
curl -si -XDELETE 'localhost:8080/api/items/sku-3?node=n2' | head -1  # 200 (2/3)
curl -s -XPOST localhost:8080/api/cluster/heal >/dev/null             # anti-entropía: n1 recibe la lápida
```

El panel (`/`) muestra el mismo flujo: filas marcadas como borradas, mensajes pendientes
(`bloqueado (partición de red)` / `en tránsito`) y los eventos `WRITE`, `REPLICATE`, `REPAIR` y `CONFLICT`.

## 5. Pruebas

Ejecutar con JDK 25 (el `toolchain` del proyecto): `./gradlew test`.

| Prueba | Qué demuestra |
|---|---|
| `DeleteTest#apDeleteIsAsynchronous` | En AP el DELETE se confirma en local; otros nodos siguen viendo el ítem hasta que llega la lápida |
| `DeleteTest#apDeleteDuringPartitionIsDeliveredAfterHeal` | Hinted handoff: el borrado hecho en el nodo aislado llega al sanar |
| `DeleteTest#tombstoneIsNotResurrectedByLateMessages` | Mensajes viejos no resucitan el ítem |
| `DeleteTest#concurrentDeleteAndUpdateResolvedByLww` | DELETE vs UPDATE concurrentes: conflicto detectado y resuelto por LWW |
| `DeleteTest#cpDeleteRequiresQuorum` | CP: la minoría rechaza el DELETE, la mayoría lo replica síncronamente |
| `DeleteTest#readRepairPropagatesTombstone` | Read-repair propaga la lápida en una lectura por quórum |
| `DeleteTest#recreateAfterDelete` | Un id borrado puede recrearse; la nueva versión gana a la lápida |
| `ApModeTest#deletesReplicateAsTombstones`, `CpModeTest` (reglas CRUD), `ApiIntegrationTest#crudInCpMode` | Cobertura previa del DELETE (unitaria y HTTP) |

## 6. Limitaciones conocidas (honestas)

- **Las lápidas no se purgan.** No hay garbage collection de tombstones (en sistemas reales se purgan tras un
  periodo mayor que el máximo desfase de replicación, p. ej. `gc_grace_seconds` en Cassandra). Aquí crecen sin límite.
- **LWW puede "perder" un borrado o una actualización** concurrente (es el precio de AP); la alternativa serían
  CRDTs o *siblings* resueltos por la aplicación.
- **El read-repair solo existe en el camino CP.** En AP la lectura es local y la reconciliación depende de la
  replicación en cola y de la anti-entropía manual; no hay anti-entropía periódica automática.
- Es una **simulación en proceso** (sin sockets ni disco): la durabilidad de las lápidas no está cubierta.
