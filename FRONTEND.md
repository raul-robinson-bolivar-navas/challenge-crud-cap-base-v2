# Frontend del laboratorio CAP & BASE

Panel web de una sola página para operar y **ver** el clúster simulado. Está organizado como un **flujo de 4 pasos**
(estrategia → clúster/fallos → CRUD → observación) más **pruebas guiadas** que se pueden ejecutar y verificar con un clic.

- **Archivos** (`src/main/resources/static/`): `index.html` (estructura), `styles.css`, `app.js`, `img/` (logo y favicon)
- **URL:** `http://localhost:8080/`
- **Dependencias:** ninguna. JavaScript vanilla, sin build, sin npm, sin CDN.

## Cómo usarlo

```bash
./gradlew bootRun
# abrir http://localhost:8080
```

Edita los archivos de `static/` con la app corriendo y recarga el navegador (en el IDE, recompila los recursos si hace falta).

## Estructura tipo dashboard

```
┌ Sidebar ─────┬ Cabecera: «Laboratorio / Vista» · chips de estado (modo, red, nodos, convergencia) ┐
│ Navegación   ├───────────────────────────────────────────────────────────────────────────────────┤
│ Estrategia   │ Contenido de la vista activa                                                     │
│ Latencia     ├───────────────────────────────────────────────────────────────────────────────────┤
│ Apariencia   │ Pie: conexión · última sincronización · modo · latencia · quórum · enlaces JSON   │
│ Reiniciar    └───────────────────────────────────────────────────────────────────────────────────┘
```

| Zona | Qué contiene |
|------|--------------|
| **Sidebar** | Navegación (Panel, Pruebas guiadas, Experimentos, Conceptos), **estrategia CP/AP**, **latencia**, **tema** (Automático/Claro/Oscuro) y **color de acento** (4), y **Reiniciar laboratorio** |
| **Cabecera** | Vista actual y chips de estado siempre visibles (CP/AP, red partida, nodos caídos, convergido/divergente) |
| **Pie** | Indicador de conexión con el servidor, hora de la última sincronización, modo, latencia, quórum y enlaces a `/api/cluster/state`, `/events` y `/api/lab/scenarios` |

### Vistas (router por hash)
| Ruta | Vista |
|------|-------|
| `#/panel` | Banner del modo, **KPIs**, clúster (nodos, fallos, replicación), consola CRUD y registro de eventos |
| `#/guias` | Pruebas guiadas ejecutables (con verificación del resultado esperado) |
| `#/experimentos` | Transcripciones del backend en un clúster aparte |
| `#/conceptos` | CRUD, CAP, BASE, CP vs AP, PACELC y cómo usar el laboratorio |

### Temas
- `data-theme` (`auto` | `light` | `dark`) y `data-accent` (`blue` | `teal` | `violet` | `orange`) en `<html>`; se guardan en `localStorage` (`lab.theme`, `lab.accent`) y se aplican con un script en `<head>` para evitar el destello.
- Los colores son variables CSS en `:root`; `auto` sigue `prefers-color-scheme`.
- En pantallas < 900 px el sidebar se vuelve un panel deslizable (botón ☰ en la cabecera).

### Resultado de una operación CRUD
Cada operación muestra primero un **veredicto en lenguaje simple** (✔ correcto · ⚠ dato viejo · ⛔ rechazado por falta de quórum · ❔ no encontrado)
que explica *por qué* ocurrió, y debajo: código HTTP, nodo que atendió, nodos participantes vs. exigidos, vigente/obsoleto, latencia,
la nota técnica del backend y el JSON (plegado).

### Pruebas guiadas (para probar y para exponer)
Definidas en `RECIPES` (`js/app.js`). Cada paso tiene:

| Campo | Para qué sirve |
|-------|----------------|
| `title` | Qué se hace |
| `why` | Qué concepto ilustra / qué observar |
| `expect` | Resultado esperado, en texto |
| `run()` | Ejecuta la acción (rellena el formulario CRUD para que veas qué se envía) |
| `check(r, state)` | Devuelve `true` o un texto explicando por qué no coincidió |

Recetas incluidas: **CP · partición**, **AP/BASE · divergencia y convergencia**, **CP · caídas de nodos**.
El primer paso de cada una reinicia el laboratorio. «Ejecutar todo» se detiene en el primer paso que no coincide.
Las tres recetas fueron verificadas contra el backend real (mismos códigos HTTP, cantidades, `stale` y conflictos).

> En la receta AP, el paso «Leer desde n3 de inmediato» depende de hacerlo en menos de la latencia (2 s); si tardas,
> la interfaz lo indica y explica que eso también es consistencia eventual.

## Cómo funciona por dentro

El servidor es la única fuente de verdad. `refresh()` pide `GET /api/cluster/state` y `/events?limit=40` al iniciar,
cada segundo y tras cada acción; solo repinta si la respuesta cambió (evita parpadeo).

| Sección de `app.js` | Contenido |
|---------------------|-----------|
| 1 · Utilidades | `esc()` (escapa todo texto externo), `EVENT_HELP` (significado de cada evento) |
| 2 · API | `api()` no lanza en 4xx/5xx; `refresh()` |
| 3 · Pintado | `renderState()`, `nodeCard()`, `renderEvents()`, `renderLegend()` |
| 4 · CRUD | `explain()` (veredicto), `showResult()`, `crud(op, params?)` |
| 5 · Pruebas guiadas | `ctl` (acciones de control), `RECIPES`, `runGuideStep()`, `runGuideAll()` |
| 6 · Experimentos | `runScenario()` |
| 7 · Arranque | Delegación de eventos por `data-act`, slider de latencia, `init()` |

### Endpoints
| Acción | Llamada |
|--------|---------|
| Modo / latencia | `POST /api/cluster/mode` `{mode}` · `POST /api/cluster/latency` `{ms}` |
| Aislar nodo | `POST /api/cluster/partition` `{groups:[[nodo],[los demás]]}` |
| Reparar / reiniciar | `POST /api/cluster/heal` · `/reset` |
| Tumbar / levantar | `POST /api/cluster/nodes/{id}/down` · `/up` |
| Replicación | `POST /api/cluster/replicate-now` · `/anti-entropy` |
| CRUD | `POST/GET/PUT/DELETE /api/items[/{id}]?node=` |
| Escenarios | `GET /api/lab/scenarios` · `/{id}` |

## Estilo
- Tema claro/oscuro automático (`prefers-color-scheme`) con variables CSS en `:root`.
- Colores con significado: azul = CP, verde = AP / correcto, ámbar = divergencia o dato viejo, rojo = rechazo o nodo caído.
- Responsive (`grid auto-fit`); regiones `aria-live` en la situación del clúster, el resultado y las pruebas guiadas.

## Cómo extenderlo
- **Nueva prueba guiada:** añadir un objeto a `RECIPES` (no requiere tocar el HTML; aparece como botón).
- **Nuevo escenario explicado:** registrarlo en `ScenarioRunner` (backend); aparece solo.
- **Nueva acción de control:** botón con `data-act="miAccion"` y una rama en el listener de `click`.

## Limitaciones conocidas
- Sondeo cada 1 s, no push.
- Sin autenticación (laboratorio local).
- Sin pruebas automáticas de UI; la lógica de las recetas se validó con un script contra la API real, falta una pasada visual
  en navegador (claro/oscuro y pantalla angosta).
