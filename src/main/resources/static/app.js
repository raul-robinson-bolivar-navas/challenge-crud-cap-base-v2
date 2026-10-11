/* Laboratorio CAP & BASE · frontend
 * Sin framework ni build. El servidor es la única fuente de verdad: cada segundo se pide el estado y se repinta.
 * Secciones: 1) utilidades  2) API  3) pintado del clúster  4) consola CRUD  5) pruebas guiadas  6) experimentos  7) arranque
 */

/* ───────────── 1) Utilidades ───────────── */

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[c]));
const sleep = (ms) => new Promise((res) => setTimeout(res, ms));
const fmtClock = (c) => {
    const e = Object.entries(c || {});
    return e.length ? e.map(([k, v]) => k + ':' + v).join(' ') : '∅';
};
const GROUPS = ['A', 'B', 'C'];

/** Qué significa cada tipo de evento del registro (se usa en la leyenda y en los tooltips). */
const EVENT_HELP = {
    WRITE: 'Se guardó un dato en un nodo',
    READ: 'Se leyó un dato',
    REJECT: 'Un nodo vivo rechazó la petición (CP sin quórum)',
    REPLICATE: 'Un cambio viajó de un nodo a otro',
    CONFLICT: 'Dos escrituras concurrentes chocaron; se conservó una (LWW)',
    REPAIR: 'Se corrigió una copia atrasada',
    PARTITION: 'La red se partió',
    HEAL: 'La red se reparó',
    NODE: 'Un nodo se cayó o volvió',
    MODE: 'Cambió la estrategia CP/AP',
    CONFIG: 'Cambió la configuración',
    RESET: 'Laboratorio reiniciado'
};

/* ───────────── 2) API ───────────── */

/** fetch que NO lanza en 4xx/5xx: los errores del laboratorio (503, 404…) son parte de la demo. */
async function api(method, path, body) {
    const res = await fetch(path, {
        method,
        headers: body ? {'Content-Type': 'application/json'} : {},
        body: body ? JSON.stringify(body) : undefined
    });
    let json = null;
    try {
        json = await res.json();
    } catch (e) { /* respuesta sin cuerpo */
    }
    return {status: res.status, ok: res.ok, json};
}

let state = null;          // último estado del clúster
let latDragging = false;   // true mientras se arrastra el slider (evita que el refresco lo "salte")
let lastSig = '';          // firma del último pintado, para no repintar si nada cambió
let lastEventsSig = '';

let online = true;         // ¿responde el backend?
let lastSync = null;       // hora de la última sincronización correcta

async function refresh() {
    let s, ev;
    try {
        [s, ev] = await Promise.all([api('GET', '/api/cluster/state'), api('GET', '/api/cluster/events?limit=40')]);
    } catch (e) {
        online = false;
        renderChrome();
        return;
    }
    online = s.ok;
    if (s.ok) {
        lastSync = new Date();
        state = s.json;
        const sig = JSON.stringify(state) + latDragging;
        if (sig !== lastSig) {
            lastSig = sig;
            renderState();
        }
    }
    renderChrome();
    if (ev.ok) {
        const sig = JSON.stringify(ev.json);
        if (sig !== lastEventsSig) {
            lastEventsSig = sig;
            renderEvents(ev.json);
        }
    }
}

/* ───────────── 3) Pintado del clúster ───────────── */

function renderState() {
    const cp = state.mode === 'CP';

    // Paso 1: modo, latencia y explicación
    document.querySelectorAll('#modeSeg button').forEach((b) => {
        const on = b.dataset.mode === state.mode;
        b.classList.toggle('on', on);
        b.setAttribute('aria-pressed', on);
    });
    if (!latDragging) $('#lat').value = state.latencyMs;
    $('#latv').textContent = state.latencyMs + ' ms';
    $('#banner').className = 'banner ' + (cp ? 'cp' : 'ap');
    $('#banner').innerHTML = `<b>${cp ? 'Modo CP · Consistencia' : 'Modo AP · BASE'}</b>
        <span>${esc(state.summary)}</span>`;

    // Paso 2: situación en una frase
    const partitions = new Map();
    state.nodes.forEach((n) => {
        if (!partitions.has(n.partition)) partitions.set(n.partition, []);
        partitions.get(n.partition).push(n.id);
    });
    const partitioned = partitions.size > 1;
    const downIds = state.nodes.filter((n) => !n.up).map((n) => n.id);
    const netTxt = partitioned
        ? `<span class="chip warn">Red partida: ${[...partitions.values()].map((g) => '{' + g.join(', ') + '}').join(' │ ')}</span>`
        : '<span class="chip ok">Red conectada</span>';
    const downTxt = downIds.length
        ? `<span class="chip bad">Caídos: ${downIds.join(', ')}</span>`
        : '<span class="chip ok">Todos los nodos arriba</span>';
    $('#situation').innerHTML = `<b>Situación:</b> ${netTxt} ${downTxt}`;
    $('#btnHeal').disabled = !partitioned;

    // Tarjetas de nodo
    const divergent = new Set(state.convergence.divergentKeys);
    $('#nodes').className = 'grid3' + (partitioned ? ' partitioned' : '');
    $('#nodes').innerHTML = state.nodes.map((n) => nodeCard(n, cp, partitioned, divergent)).join('');

    // Indicadores (KPI)
    const c = state.convergence, st = state.stats;
    const up = state.nodes.filter((n) => n.up).length;
    const kpi = (cls, label, value, sub, tip) => `<div class="kpi ${cls}" title="${esc(tip)}">
        <span class="kpi-label">${label}</span><b class="kpi-value">${value}</b><span class="kpi-sub">${sub}</span></div>`;
    $('#stats').innerHTML =
        kpi(up === state.totalNodes ? 'ok' : 'bad', 'Nodos activos', `${up}/${state.totalNodes}`,
            `quórum ${state.quorum}`, 'Nodos vivos y mayoría necesaria en CP') +
        kpi(c.converged ? 'ok' : 'warn', 'Copias', c.converged ? 'Iguales' : 'Distintas',
            c.converged ? 'convergidas' : esc(c.divergentKeys.join(', ')), '¿Todas las réplicas guardan exactamente lo mismo?') +
        kpi(st.pendingReplications ? 'warn' : '', 'En cola', st.pendingReplications,
            'replicaciones', 'Mensajes de replicación esperando ser entregados') +
        kpi('', 'Entregadas', st.replicationsDelivered, 'replicaciones', 'Mensajes de replicación ya entregados') +
        kpi(st.conflictsResolved ? 'warn' : '', 'Conflictos', st.conflictsResolved,
            'resueltos (LWW)', 'Escrituras concurrentes resueltas con Last-Write-Wins') +
        kpi(st.rejectedOperations ? 'bad' : '', 'Rechazadas', st.rejectedOperations,
            'peticiones 503', 'Peticiones que un nodo vivo se negó a atender');

    // Cola de replicación
    $('#pending').innerHTML = state.pending.length
        ? `<table class="pending"><thead><tr><th>Replicación en cola</th><th>Ítem</th><th>Estado</th></tr></thead><tbody>${
            state.pending.map((p) => {
                const flying = p.status === 'en tránsito';
                return `<tr><td>${esc(p.from)} → ${esc(p.to)}</td><td>${esc(p.key)}</td>
                    <td><span class="chip ${flying ? '' : 'warn'}">${esc(p.status)}${flying ? ' · ' + p.dueInMs + ' ms' : ''}</span></td></tr>`;
            }).join('')}</tbody></table>`
        : '';
    sqTopology();
}

/** Cabecera y pie: estado resumido del laboratorio, siempre visible. */
function renderChrome() {
    const top = $('#topStatus'), foot = $('#footStatus');
    if (!online || !state) {
        const msg = '<span class="chip bad">● Sin conexión con el servidor</span>';
        top.innerHTML = msg;
        foot.innerHTML = msg;
        return;
    }
    const cp = state.mode === 'CP';
    const partitioned = new Set(state.nodes.map((n) => n.partition)).size > 1;
    const down = state.nodes.filter((n) => !n.up).length;
    top.innerHTML = `<span class="chip ${cp ? 'cp' : 'ap'}">${cp ? 'CP' : 'AP · BASE'}</span>
        <span class="chip ${partitioned ? 'warn' : 'ok'}">${partitioned ? 'Red partida' : 'Red conectada'}</span>
        <span class="chip ${down ? 'bad' : 'ok'}">${down ? down + ' nodo(s) caído(s)' : 'Nodos OK'}</span>
        <span class="chip ${state.convergence.converged ? 'ok' : 'warn'}">${state.convergence.converged ? 'Convergido' : 'Divergente'}</span>`;
    foot.innerHTML = `<span class="dot ok"></span> Conectado · sincronizado ${lastSync ? lastSync.toLocaleTimeString() : '–'}
        <span class="sep">|</span> Modo <b>${state.mode}</b>
        <span class="sep">|</span> Latencia <b>${state.latencyMs} ms</b>
        <span class="sep">|</span> Quórum <b>${state.quorum}/${state.totalNodes}</b>`;
}

function nodeCard(n, cp, partitioned, divergent) {
    const reach = state.nodes.filter((m) => m.up && m.partition === n.partition).length;
    let status;
    if (!n.up) status = '<span class="chip bad">Caído</span>';
    else if (!cp) status = '<span class="chip ok">Atiende · usa su copia local</span>';
    else if (reach >= state.quorum) status = `<span class="chip ok">Atiende · ve ${reach} de ${state.totalNodes} (quórum ${state.quorum})</span>`;
    else status = `<span class="chip bad">Rechaza · ve ${reach} de ${state.totalNodes} (quórum ${state.quorum})</span>`;

    const rows = n.records.map((r) => `<tr class="${divergent.has(r.key) ? 'div' : ''} ${r.deleted ? 'tomb' : ''}">
        <td>${esc(r.key)}</td><td>${r.deleted ? '<i>borrado</i>' : esc(r.name)}</td>
        <td class="num">${r.deleted ? '—' : r.quantity}</td><td class="clk">${esc(fmtClock(r.vectorClock))}</td></tr>`).join('')
        || '<tr><td colspan="4" class="muted">Sin datos</td></tr>';

    return `<div class="node g${n.partition} ${n.up ? '' : 'off'}">
        <div class="nodehead"><h3>${esc(n.id)}</h3>
            <div class="row">
                ${partitioned ? `<span class="chip">Partición ${GROUPS[n.partition] || n.partition}</span>` : ''}
                <button class="btn sm" data-act="isolate" data-node="${esc(n.id)}" ${n.up ? '' : 'disabled'}
                        title="Corta la red de ${esc(n.id)} con los demás (sigue vivo)">Aislar</button>
                <button class="btn sm" data-act="${n.up ? 'down' : 'up'}" data-node="${esc(n.id)}"
                        title="${n.up ? 'Apaga el nodo' : 'Vuelve a encender el nodo'}">${n.up ? 'Tumbar' : 'Levantar'}</button>
            </div>
        </div>
        <div class="node-status">${status}</div>
        <table><thead><tr><th>ID</th><th>Nombre</th><th>Cant.</th><th title="Reloj vectorial: cuántas escrituras ha visto de cada nodo">Reloj</th></tr></thead>
        <tbody>${rows}</tbody></table>
        ${divergent.size && n.records.some((r) => divergent.has(r.key)) ? '<div class="hint">Fila ámbar = esta copia difiere de otras réplicas.</div>' : ''}
    </div>`;
}

function renderEvents(list) {
    $('#log').innerHTML = list.slice().reverse().map((e) => `<div>
        <time>${new Date(e.at).toLocaleTimeString()}</time>
        <span class="t t-${esc(e.type)}" title="${esc(EVENT_HELP[e.type] || '')}">${esc(e.type)}</span>
        <span>${esc(e.message)}</span></div>`).join('')
        || '<span class="muted">Sin eventos todavía.</span>';
}

function renderLegend() {
    $('#legend').innerHTML = '<span class="muted">Tipos de evento: </span>' + Object.keys(EVENT_HELP)
        .map((t) => `<span class="legend-item"><span class="t t-${t}">${t}</span> ${esc(EVENT_HELP[t])}</span>`).join('');
}

/* ───────────── 4) Consola CRUD ───────────── */

/**
 * Traduce la respuesta del backend a una frase simple: qué pasó y por qué.
 * Devuelve {cls, icon, text} donde cls es ok | warn | bad.
 */
function explain(r, op) {
    const j = r.json || {}, m = j.meta, mode = (j.mode || m?.mode || state?.mode);
    const cp = mode === 'CP';
    if (r.ok) {
        if (m?.stale) {
            return {
                cls: 'warn', icon: '⚠',
                text: `Respondió, pero con un dato <b>viejo</b>: ${esc(m.servedBy)} aún no tiene la última versión. ` +
                    'Esto es AP/BASE: prefiere contestar con lo que tiene antes que no contestar.'
            };
        }
        const verb = {create: 'creó', read: 'leyó', list: 'listó', update: 'actualizó', delete: 'borró'}[op];
        const how = cp
            ? `Se confirmó con <b>${m?.nodesContacted}</b> nodo(s) (se exigían ${m?.required}), por eso el dato es fiable.`
            : `Se atendió solo con la copia local de ${esc(m?.servedBy)}; los demás nodos se enteran después (replicación asíncrona).`;
        return {cls: 'ok', icon: '✔', text: `Correcto: se ${verb} el dato. ${how}`};
    }
    switch (j.code) {
        case 'CP_QUORUM_UNAVAILABLE':
            return {
                cls: 'bad', icon: '⛔',
                text: 'El nodo está vivo pero <b>no ve a la mayoría</b> de los nodos. En CP prefiere <b>rechazar</b> antes que arriesgar ' +
                    'un dato incorrecto: sacrifica la <b>Disponibilidad</b> para conservar la <b>Consistencia</b>.'
            };
        case 'NODE_DOWN':
            return {cls: 'bad', icon: '⛔', text: 'El nodo al que te conectaste está <b>caído</b>. Elige otro nodo o levántalo.'};
        case 'NOT_FOUND':
            return {
                cls: 'warn', icon: '❔',
                text: cp
                    ? 'Ese ID no existe.'
                    : 'Ese ID no existe <b>en este nodo</b>. Puede que exista en otro y la replicación aún no haya llegado (<i>soft state</i>).'
            };
        case 'ALREADY_EXISTS':
            return {cls: 'warn', icon: '❔', text: 'Ya existe un ítem con ese ID en este nodo. Usa «Actualizar» o cambia el ID.'};
        case 'VALIDATION_ERROR':
            return {cls: 'warn', icon: '❔', text: 'Datos inválidos: revisa nombre y cantidad (cantidad ≥ 0).'};
        default:
            return {cls: 'warn', icon: '❔', text: esc(j.message || 'La operación no se pudo completar.')};
    }
}

function showResult(label, r, ms, op) {
    const j = r.json || {};
    const m = j.meta;
    const ex = explain(r, op);
    const httpCls = r.status < 300 ? 'ok' : r.status === 503 ? 'bad' : 'warn';
    let html = `<div class="verdict ${ex.cls}"><span class="verdict-icon">${ex.icon}</span><div>${ex.text}</div></div>
        <div class="reshead"><span class="chip ${httpCls}">HTTP ${r.status}</span><code>${esc(label)}</code>
        <span class="muted">${ms} ms medidos en el navegador</span></div>`;
    if (m) {
        html += `<div class="chips">
            <span class="chip">Atendido por ${esc(m.servedBy)}</span>
            <span class="chip" title="Nodos que participaron / nodos exigidos">Participaron ${m.nodesContacted} · exigidos ${m.required}</span>
            <span class="chip ${m.stale ? 'warn' : 'ok'}">${m.stale ? 'Dato obsoleto ⚠' : 'Dato vigente'}</span>
            <span class="chip" title="Latencia que el servidor simuló">Latencia ${m.latencyMs} ms</span></div>
            <p class="note"><b>Nota del sistema:</b> ${esc(m.note)}</p>`;
    } else if (j.code) {
        html += `<div class="chips"><span class="chip ${httpCls}">${esc(j.code)}</span></div>
            <p class="note"><b>Mensaje:</b> ${esc(j.message)}</p>`;
    }
    html += `<details><summary>Ver respuesta JSON</summary><pre>${esc(JSON.stringify(j.data !== undefined ? j.data : (j.details || j), null, 2))}</pre></details>`;
    $('#result').className = '';
    $('#result').innerHTML = html;
}

/**
 * Ejecuta una operación CRUD. Sin `p` toma los valores del formulario; con `p`
 * ({node, id, name, qty}) los vuelca antes en el formulario (así se ve qué se envió).
 */
async function crud(op, p) {
    if (p) {
        if (p.node !== undefined) $('#fNode').value = p.node;
        if (p.id !== undefined) $('#fId').value = p.id;
        if (p.name !== undefined) $('#fName').value = p.name;
        if (p.qty !== undefined) $('#fQty').value = p.qty;
    }
    const node = $('#fNode').value, id = $('#fId').value.trim();
    const body = {name: $('#fName').value, quantity: Number($('#fQty').value)};
    const enc = encodeURIComponent(id), q = '?node=' + node;
    const t0 = performance.now();
    let r, label;
    if (op === 'create') {
        r = await api('POST', '/api/items' + q, {id: id || undefined, ...body});
        label = `POST /api/items${q}`;
    } else if (op === 'read') {
        r = await api('GET', `/api/items/${enc}${q}`);
        label = `GET /api/items/${id}${q}`;
    } else if (op === 'list') {
        r = await api('GET', '/api/items' + q);
        label = `GET /api/items${q}`;
    } else if (op === 'update') {
        r = await api('PUT', `/api/items/${enc}${q}`, body);
        label = `PUT /api/items/${id}${q}`;
    } else {
        r = await api('DELETE', `/api/items/${enc}${q}`);
        label = `DELETE /api/items/${id}${q}`;
    }
    showResult(label, r, Math.round(performance.now() - t0), op);
    await refresh();
    sqSyncForm(node, id, $('#fName').value, $('#fQty').value);
    sqRecord({op, node, id, r, label: label.replace('/api', '').replace(/\?node=\w+/, '')});
    return r;
}

/* ───────────── 5) Pruebas guiadas ─────────────
 * Cada paso: title (qué hago), why (qué debo observar), expect (resultado esperado, en texto),
 * run() ejecuta la acción y devuelve la respuesta, check(r, state) devuelve true si fue lo esperado
 * o un texto explicando la diferencia.
 */

const ctl = {
    reset: () => api('POST', '/api/cluster/reset'),
    mode: (mode) => api('POST', '/api/cluster/mode', {mode}),
    latency: (ms) => api('POST', '/api/cluster/latency', {ms}),
    isolate: (node) => api('POST', '/api/cluster/partition',
        {groups: [[node], ['n1', 'n2', 'n3'].filter((i) => i !== node)]}),
    heal: () => api('POST', '/api/cluster/heal'),
    down: (node) => api('POST', `/api/cluster/nodes/${node}/down`),
    up: (node) => api('POST', `/api/cluster/nodes/${node}/up`),
    replicate: () => api('POST', '/api/cluster/replicate-now')
};

/** Ejecuta varias llamadas de control y devuelve la primera que falle (o la última). */
async function seq(...fns) {
    let r;
    for (const f of fns) {
        r = await f();
        if (!r.ok) return r;
    }
    return r;
}

const expectStatus = (status, hint) => (r) =>
    r.status === status ? true : `Se esperaba HTTP ${status} pero llegó HTTP ${r.status}. ${hint || ''}`;

const expectQty = (qty) => (r) => {
    if (!r.ok) return `Se esperaba HTTP 200 pero llegó HTTP ${r.status}.`;
    if (r.json.data.quantity !== qty) return `Se esperaba cantidad ${qty} pero el nodo devolvió ${r.json.data.quantity}.`;
    return true;
};

const RECIPES = [
    {
        id: 'cp-partition',
        title: 'CP: la red se parte y un nodo vivo se niega a responder',
        goal: 'Ver que CP sacrifica la Disponibilidad para no servir nunca un dato incorrecto.',
        steps: [
            {
                title: 'Reiniciar y poner modo CP (latencia 400 ms)',
                why: 'Parte de un clúster limpio y con la estrategia de Consistencia.',
                expect: 'Modo CP activo, sin datos.',
                run: () => seq(ctl.reset, () => ctl.mode('CP'), () => ctl.latency(400))
            },
            {
                title: 'Crear sku-1 (cantidad 10) conectado a n1',
                why: 'En CP la escritura espera a que los nodos alcanzables la guarden antes de confirmar.',
                expect: 'HTTP 201 y «Participaron 3 · exigidos 2»: los 3 nodos tienen el dato.',
                run: () => crud('create', {node: 'n1', id: 'sku-1', name: 'Café', qty: 10}),
                check: expectStatus(201)
            },
            {
                title: 'Aislar n1 de la red',
                why: 'n1 sigue vivo pero solo se ve a sí mismo: 1 de 3 nodos, menos que el quórum (2).',
                expect: 'Red partida: {n1} │ {n2, n3}. La tarjeta de n1 dice «Rechaza».',
                run: () => ctl.isolate('n1')
            },
            {
                title: 'Leer sku-1 conectado a n1 (la minoría)',
                why: 'n1 podría tener el dato, pero no puede confirmar que sea el último.',
                expect: 'HTTP 503 CP_QUORUM_UNAVAILABLE: vivo, pero prefiere fallar.',
                run: () => crud('read', {node: 'n1', id: 'sku-1'}),
                check: expectStatus(503, 'n1 debería estar aislado.')
            },
            {
                title: 'Actualizar sku-1 a 20 conectado a n2 (la mayoría)',
                why: 'n2 y n3 se ven entre sí: 2 de 3, hay quórum.',
                expect: 'HTTP 200 con «Participaron 2 · exigidos 2».',
                run: () => crud('update', {node: 'n2', id: 'sku-1', name: 'Café', qty: 20}),
                check: expectStatus(200)
            },
            {
                title: 'Leer sku-1 conectado a n3',
                why: 'La mayoría nunca sirve datos viejos.',
                expect: 'Cantidad 20 y «Dato vigente».',
                run: () => crud('read', {node: 'n3', id: 'sku-1'}),
                check: expectQty(20)
            },
            {
                title: 'Reparar la red',
                why: 'En CP, al sanar, los nodos rezagados se ponen al día solos.',
                expect: 'Red conectada. n1 recibe la cantidad 20.',
                run: () => ctl.heal()
            },
            {
                title: 'Leer sku-1 conectado a n1',
                why: 'Nunca se sirvió un dato incoherente: n1 falló mientras estuvo aislado y ahora responde con el valor correcto.',
                expect: 'Cantidad 20, «Dato vigente».',
                run: () => crud('read', {node: 'n1', id: 'sku-1'}),
                check: expectQty(20)
            }
        ]
    },
    {
        id: 'ap-partition',
        title: 'AP / BASE: todos responden, hay conflicto y luego convergen',
        goal: 'Ver Basically Available, Soft state y Eventual consistency, y el precio: se pierde una escritura.',
        steps: [
            {
                title: 'Reiniciar y poner modo AP (latencia 2000 ms)',
                why: 'La latencia alta hace visible que la replicación tarda.',
                expect: 'Modo AP activo, sin datos.',
                run: () => seq(ctl.reset, () => ctl.mode('AP'), () => ctl.latency(2000))
            },
            {
                title: 'Crear sku-1 (cantidad 10) conectado a n1',
                why: 'En AP se guarda solo en n1 y la copia a los demás viaja en segundo plano.',
                expect: 'HTTP 201 inmediato; en la cola aparecen mensajes n1 → n2 y n1 → n3.',
                run: () => crud('create', {node: 'n1', id: 'sku-1', name: 'Café', qty: 10}),
                check: expectStatus(201)
            },
            {
                title: 'Leer sku-1 conectado a n3, de inmediato (menos de 2 s)',
                why: 'La réplica aún no llegó: estado blando.',
                expect: 'HTTP 404 «aún no llegó la replicación».',
                run: () => crud('read', {node: 'n3', id: 'sku-1'}),
                check: (r) => r.status === 404 ? true
                    : 'La replicación ya había llegado (pasaron más de 2 s): eso también es consistencia eventual. ' +
                    'Repite desde el paso 1 y ejecuta este paso enseguida para ver la lectura vacía.'
            },
            {
                title: 'Replicar ahora',
                why: 'Fuerza la entrega para que los 3 nodos tengan sku-1 antes de provocar el conflicto.',
                expect: 'Copias iguales (convergidas), cola vacía.',
                run: () => ctl.replicate(),
                check: (r, st) => st.convergence.converged ? true : 'Las copias aún difieren; ejecuta «Replicar ahora» otra vez.'
            },
            {
                title: 'Aislar n1 de la red',
                why: 'Ahora n1 y {n2, n3} no pueden hablarse, pero AP sigue atendiendo en ambos lados.',
                expect: 'Red partida: {n1} │ {n2, n3}.',
                run: () => ctl.isolate('n1')
            },
            {
                title: 'Actualizar sku-1 a 99 conectado a n1',
                why: 'Escritura en el lado minoritario: AP la acepta (Basically Available).',
                expect: 'HTTP 200.',
                run: () => crud('update', {node: 'n1', id: 'sku-1', name: 'Café', qty: 99}),
                check: expectStatus(200)
            },
            {
                title: 'Actualizar sku-1 a 20 conectado a n2',
                why: 'Escritura concurrente en el otro lado de la partición.',
                expect: 'HTTP 200. Ahora hay dos «verdades» distintas.',
                run: () => crud('update', {node: 'n2', id: 'sku-1', name: 'Café', qty: 20}),
                check: expectStatus(200)
            },
            {
                title: 'Replicar ahora (con la red partida)',
                why: 'La partición bloquea n1 ↔ n2/n3: los mensajes quedan esperando (hinted handoff).',
                expect: 'Copias distintas: n1=99, n2=n3=20. Mensajes «bloqueado (partición de red)».',
                run: () => ctl.replicate(),
                check: (r, st) => !st.convergence.converged ? true : 'Las copias ya coinciden; la partición debería estar activa.'
            },
            {
                title: 'Leer sku-1 conectado a n1',
                why: 'n1 responde (disponible), pero con su valor local, que ya no es el vigente.',
                expect: 'HTTP 200 con «Dato obsoleto ⚠» y cantidad 99.',
                run: () => crud('read', {node: 'n1', id: 'sku-1'}),
                check: (r) => r.ok && r.json.meta.stale ? true : 'Se esperaba un dato marcado como obsoleto.'
            },
            {
                title: 'Reparar la red y replicar ahora',
                why: 'Los relojes vectoriales detectan que ambas escrituras fueron concurrentes; Last-Write-Wins elige una.',
                expect: 'Copias iguales y «Conflictos resueltos: 1». Evento CONFLICT en el registro.',
                run: () => seq(ctl.heal, ctl.replicate),
                check: (r, st) => st.convergence.converged && st.stats.conflictsResolved >= 1 ? true
                    : 'Aún no convergió; pulsa «Replicar ahora» y revisa el estado.'
            },
            {
                title: 'Leer sku-1 conectado a n1',
                why: 'Todas las réplicas coinciden. El precio de AP: la actualización a 99 se perdió.',
                expect: 'Cantidad 20 en n1 (la escritura de 99 fue descartada).',
                run: () => crud('read', {node: 'n1', id: 'sku-1'}),
                check: expectQty(20)
            }
        ]
    },
    {
        id: 'cp-node-failures',
        title: 'CP: cuántas caídas aguanta el quórum',
        goal: 'Ver que con 3 nodos CP tolera 1 caída, pero no 2.',
        steps: [
            {
                title: 'Reiniciar y poner modo CP (latencia 400 ms)',
                why: 'Clúster limpio con consistencia fuerte.',
                expect: 'Modo CP activo, sin datos.',
                run: () => seq(ctl.reset, () => ctl.mode('CP'), () => ctl.latency(400))
            },
            {
                title: 'Crear sku-1 (cantidad 10) conectado a n1',
                why: 'Con los 3 nodos arriba todo funciona.',
                expect: 'HTTP 201.',
                run: () => crud('create', {node: 'n1', id: 'sku-1', name: 'Café', qty: 10}),
                check: expectStatus(201)
            },
            {
                title: 'Tumbar n3',
                why: 'Quedan 2 de 3: todavía hay mayoría.',
                expect: 'n3 «Caído».',
                run: () => ctl.down('n3')
            },
            {
                title: 'Actualizar sku-1 a 20 conectado a n1',
                why: '2 de 3 nodos alcanzan el quórum.',
                expect: 'HTTP 200 con «Participaron 2 · exigidos 2».',
                run: () => crud('update', {node: 'n1', id: 'sku-1', name: 'Café', qty: 20}),
                check: expectStatus(200)
            },
            {
                title: 'Tumbar también n2',
                why: 'Ahora solo n1 está vivo: 1 de 3, sin mayoría.',
                expect: 'n2 y n3 «Caído»; n1 «Rechaza».',
                run: () => ctl.down('n2')
            },
            {
                title: 'Actualizar sku-1 a 30 conectado a n1',
                why: 'n1 vive pero no puede garantizar consistencia a solas.',
                expect: 'HTTP 503 CP_QUORUM_UNAVAILABLE.',
                run: () => crud('update', {node: 'n1', id: 'sku-1', name: 'Café', qty: 30}),
                check: expectStatus(503)
            },
            {
                title: 'Levantar n2',
                why: 'Vuelve la mayoría (n1 + n2).',
                expect: 'n1 vuelve a «Atiende».',
                run: () => ctl.up('n2')
            },
            {
                title: 'Actualizar sku-1 a 30 conectado a n1',
                why: 'Se recuperó el quórum, sin intervención adicional.',
                expect: 'HTTP 200.',
                run: () => crud('update', {node: 'n1', id: 'sku-1', name: 'Café', qty: 30}),
                check: expectStatus(200)
            },
            {
                title: 'Levantar n3',
                why: 'El nodo rezagado se pone al día por sí solo.',
                expect: 'Los 3 nodos arriba y con cantidad 30 (copias iguales).',
                run: () => ctl.up('n3'),
                check: async (r, st) => st.convergence.converged ? true : 'n3 aún no está al día; pulsa «Anti-entropía».'
            }
        ]
    }
];

const guide = {recipe: null, results: [], running: false};

function openRecipe(id) {
    guide.recipe = RECIPES.find((x) => x.id === id);
    guide.results = guide.recipe.steps.map(() => null);
    renderGuide();
}

function renderGuide() {
    document.querySelectorAll('#guideButtons button').forEach((b) =>
        b.classList.toggle('primary', guide.recipe && b.dataset.recipe === guide.recipe.id));
    const rc = guide.recipe;
    if (!rc) return;
    const next = guide.results.findIndex((x) => !x);
    const doneAll = next === -1;
    $('#guideOut').className = '';
    $('#guideOut').innerHTML = `<p><b>${esc(rc.title)}</b></p><p class="muted">${esc(rc.goal)}</p>
        <div class="row guide-actions">
            <button class="btn primary" data-act="guideAll" ${guide.running || doneAll ? 'disabled' : ''}>▶ Ejecutar todo</button>
            <button class="btn" data-act="guideRestart" ${guide.running ? 'disabled' : ''}>↺ Reiniciar guía</button>
            <span class="muted">${guide.results.filter(Boolean).length} / ${rc.steps.length} pasos</span>
        </div>
        <ol class="steps guide">${rc.steps.map((s, i) => {
        const res = guide.results[i];
        const cls = res ? (res.ok ? 'done' : 'mismatch') : (i === next ? 'current' : '');
        return `<li class="${cls}">
                <div class="step-head"><b>${esc(s.title)}</b>
                    <button class="btn sm ${i === next ? 'primary' : ''}" data-act="guideStep" data-i="${i}" ${guide.running ? 'disabled' : ''}>
                        ${res ? 'Repetir' : 'Ejecutar'}</button></div>
                <div class="res">${esc(s.why)}</div>
                <div class="ins">🎯 Esperado: ${esc(s.expect)}</div>
                ${res ? `<div class="outcome ${res.ok ? 'ok' : 'warn'}">${res.ok ? '✔ Resultado esperado'
            : '⚠ Distinto a lo esperado: ' + esc(res.msg)}</div>` : ''}
            </li>`;
    }).join('')}</ol>`;
}

/** Ejecuta un paso, lo compara con lo esperado y devuelve si coincidió. */
async function runGuideStep(i) {
    const s = guide.recipe.steps[i];
    guide.running = true;
    renderGuide();
    try {
        const r = await s.run();
        await refresh();
        const verdict = s.check ? await s.check(r, state) : (r.ok ? true : `La acción falló con HTTP ${r.status}.`);
        guide.results[i] = verdict === true ? {ok: true} : {ok: false, msg: verdict};
    } catch (e) {
        guide.results[i] = {ok: false, msg: 'Error inesperado: ' + e.message};
    } finally {
        guide.running = false;
        renderGuide();
    }
    return guide.results[i].ok;
}

async function runGuideAll() {
    for (let i = 0; i < guide.recipe.steps.length; i++) {
        if (guide.results[i]?.ok) continue;
        if (!(await runGuideStep(i))) return;   // se detiene en el primer paso que no coincide, para poder leerlo
        await sleep(500);
    }
}

/* ───────────── 6) Experimentos explicados (transcripción del backend) ───────────── */

async function runScenario(id, btn) {
    document.querySelectorAll('#scnButtons button').forEach((b) => b.classList.toggle('primary', b === btn));
    const r = await api('GET', '/api/lab/scenarios/' + id);
    if (!r.ok) {
        $('#scnOut').textContent = 'No se pudo ejecutar el escenario.';
        return;
    }
    const s = r.json;
    $('#scnOut').className = '';
    $('#scnOut').innerHTML = `<p><b>${esc(s.title)}</b></p><p class="muted">${esc(s.goal)}</p>
        <ol class="steps">${s.steps.map((st) => `<li><b>${esc(st.action)}</b><div class="res">${esc(st.result)}</div>
        <div class="ins">💡 ${esc(st.insight)}</div></li>`).join('')}</ol>
        <div class="concl"><b>Conclusiones</b><ul>${s.conclusions.map((c) => `<li>${esc(c)}</li>`).join('')}</ul></div>`;
}

/* ───────────── 7) Eventos de usuario y arranque ───────────── */

/** Delegación: cualquier elemento con data-act dispara su acción. */
document.addEventListener('click', async (ev) => {
    const el = ev.target.closest('[data-act]');
    if (!el || el.disabled) return;
    const {act, node, mode, op, i} = el.dataset;
    if (act === 'mode') await api('POST', '/api/cluster/mode', {mode});
    else if (act === 'reset') await ctl.reset();
    else if (act === 'heal') await ctl.heal();
    else if (act === 'replicate') await ctl.replicate();
    else if (act === 'antientropy') await api('POST', '/api/cluster/anti-entropy');
    else if (act === 'down') await ctl.down(node);
    else if (act === 'up') await ctl.up(node);
    else if (act === 'isolate') await ctl.isolate(node);
    else if (act === 'upall') {
        for (const n of state.nodes.filter((k) => !k.up)) await ctl.up(n.id);
    } else if (act === 'seqCrud') {
        await crud(op, {node: $('#sqNode').value, id: $('#sqId').value.trim(), name: $('#sqName').value, qty: $('#sqQty').value});
        return;
    } else if (act === 'seqReplay') {
        sqReplay();
        return;
    } else if (act === 'seqNext') {
        sqNext();
        return;
    }
    else if (act === 'crud') {
        await crud(op);
        return;
    } else if (act === 'guideStep') {
        await runGuideStep(Number(i));
        return;
    } else if (act === 'guideAll') {
        await runGuideAll();
        return;
    } else if (act === 'guideRestart') {
        openRecipe(guide.recipe.id);
        return;
    }
    refresh();
});

$('#lat').addEventListener('input', (e) => {
    latDragging = true;
    $('#latv').textContent = e.target.value + ' ms';
});
$('#lat').addEventListener('change', async (e) => {
    await api('POST', '/api/cluster/latency', {ms: Number(e.target.value)});
    latDragging = false;
    refresh();
});

/* ───────────── 9) Diagrama de secuencia ─────────────
 * Dibuja, con datos reales de la última operación CRUD, qué mensajes cruzaron entre el cliente y los nodos.
 * Los datos salen de la respuesta (meta.contactedNodes, meta.stale, code) y del estado (partición, nodos caídos, cola).
 * Sólido = petición/escritura síncrona · discontinuo = respuesta · punteado = replicación asíncrona · ✖ = sin ruta.
 */

const SQ_CLIENT = 'Cliente';
const SQ_W = 900, SQ_TOP = 110, SQ_ROW = 56;
const SQ_GROUP_COLORS = ['#2f5bea', '#d9822b', '#a23bd1'];
const SQ = {rec: null, msgs: [], async: [], mode: 'auto', speed: 0.8, shown: 0, topoSig: ''};
const REDUCED_MOTION = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

const sqActors = () => [SQ_CLIENT, ...state.nodes.map((n) => n.id)];
const sqX = (actor) => {
    const a = sqActors();
    return 90 + a.indexOf(actor) * ((SQ_W - 180) / (a.length - 1));
};
const plain = (html) => String(html).replace(/<[^>]+>/g, '');

/** Convierte (operación + respuesta + estado) en la lista de mensajes del diagrama. */
function sqBuild(rec) {
    const {op, node, r} = rec;
    const j = r.json || {}, m = j.meta;
    const cp = (m?.mode || j.mode || state.mode) === 'CP';
    const write = ['create', 'update', 'delete'].includes(op);
    const me = state.nodes.find((n) => n.id === node);
    const msgs = [];

    msgs.push({
        kind: 'req', from: SQ_CLIENT, to: node, label: rec.label, cls: '',
        why: `El cliente se conecta a ${node} y le envía la petición.`
    });

    if (j.code === 'NODE_DOWN' || !me) {
        msgs.push({
            kind: 'fail', from: SQ_CLIENT, to: node, label: 'sin respuesta', cls: 'bad',
            why: `${node} está caído: la petición no llega a ningún sitio.`
        });
        msgs.push({
            kind: 'note', at: SQ_CLIENT, cls: 'bad', text: '503 NODE_DOWN',
            why: 'El cliente recibe un error: debe elegir otro nodo o levantar este.'
        });
        return msgs;
    }

    const others = state.nodes.filter((n) => n.id !== node);
    const unreachable = others.filter((n) => !n.up || n.partition !== me.partition);
    const reachable = others.filter((n) => !unreachable.includes(n));
    const failArrow = (n) => ({
        kind: 'fail', from: node, to: n.id, label: 'sin ruta', cls: 'bad',
        why: `${node} no puede alcanzar a ${n.id} (${n.up ? 'partición de red' : 'nodo caído'}).`
    });
    const early = r.status === 400 || j.code === 'UNKNOWN_NODE';

    if (!early && cp) {
        const seen = reachable.length + 1;
        const okQ = seen >= state.quorum;
        msgs.push({
            kind: 'note', at: node, cls: okQ ? 'ok' : 'bad',
            text: `Quórum: veo ${seen} de ${state.totalNodes} (exijo ${state.quorum})`,
            why: okQ ? `${node} ve a la mayoría, así que puede garantizar consistencia.`
                : `${node} está vivo pero en minoría: CP prefiere rechazar antes que arriesgar un dato incorrecto.`
        });
        if (j.code === 'CP_QUORUM_UNAVAILABLE') {
            unreachable.forEach((n) => msgs.push(failArrow(n)));
        } else {
            const contacted = m?.contactedNodes ? reachable.filter((n) => m.contactedNodes.includes(n.id)) : reachable;
            contacted.forEach((n) => {
                msgs.push({
                    kind: 'sync', from: node, to: n.id, cls: '', label: write ? 'escribir' : 'consultar versión',
                    why: write ? `${node} exige que ${n.id} guarde el cambio antes de confirmar (replicación síncrona).`
                        : `${node} pregunta a ${n.id} por su versión para devolver la más reciente.`
                });
                msgs.push({
                    kind: 'back', from: n.id, to: node, cls: '', label: write ? 'ack' : 'versión',
                    why: `${n.id} responde a ${node}.`
                });
            });
            unreachable.forEach((n) => msgs.push(failArrow(n)));
        }
    } else if (!early) {
        msgs.push({
            kind: 'note', at: node, cls: 'ap', text: 'Responde con su copia local',
            why: `En AP ${node} no consulta a nadie: contesta con lo que tiene (rápido, pero puede estar desactualizado).`
        });
    }

    const stale = !!m?.stale;
    const cls = r.ok ? (stale ? 'warn' : 'ok') : r.status === 503 ? 'bad' : 'warn';
    msgs.push({
        kind: 'res', from: node, to: SQ_CLIENT, cls,
        label: `${r.status}${j.code ? ' ' + j.code : ''}${stale ? ' · obsoleto ⚠' : ''}`,
        why: plain(explain(r, op).text)
    });

    if (!cp && r.ok && write) {
        const key = j.data?.key || rec.id;
        others.forEach((n) => msgs.push({
            kind: 'async', from: node, to: n.id, key, label: 'replicar (async)', cls: 'warn',
            why: `${node} replica «${key}» a ${n.id} en segundo plano; el cliente ya recibió su respuesta.`
        }));
    }
    return msgs;
}

function sqMsgSvg(m, i, animate) {
    const y = SQ_TOP + i * SQ_ROW;
    const shown = !animate || (SQ.mode === 'step' && i < SQ.shown);
    const delay = animate && SQ.mode === 'auto' && !REDUCED_MOTION ? `${(i * SQ.speed).toFixed(2)}s` : '0s';
    const base = `msg${shown ? ' show' : ''}${animate && SQ.mode === 'auto' ? ' play' : ''}`;
    const num = `<circle class="sq-badge" cx="0" cy="0" r="10"/><text class="sq-badge-t" x="0" y="4" text-anchor="middle">${i + 1}</text>`;

    if (m.kind === 'note') {
        const cx = sqX(m.at), w = 230;
        const x = Math.min(Math.max(cx - w / 2, 4), SQ_W - w - 4);
        return `<g class="${base} sq-note sq-${m.cls}" data-i="${i}" style="--d:${delay}">
            <rect x="${x}" y="${y - 18}" width="${w}" height="36" rx="8"/>
            <text x="${x + w / 2}" y="${y + 4}" text-anchor="middle">${esc(m.text)}</text>
            <g transform="translate(${x - 2},${y - 18})">${num}</g><title>${esc(m.why)}</title></g>`;
    }

    const x1 = sqX(m.from), x2 = sqX(m.to), dir = x2 > x1 ? 1 : -1;
    const id = m.kind === 'async' ? ` id="sq-a-${i}"` : '';
    const dash = {back: 'sq-dash', res: 'sq-dash', async: 'sq-dot-line', fail: 'sq-dash'}[m.kind] || '';
    const end = m.kind === 'fail' ? x2 - dir * 34 : x2 - dir * 4;
    const marker = m.kind === 'fail' ? '' : ` marker-end="url(#ar-${m.cls || 'n'})"`;
    const mid = (x1 + end) / 2;
    const cross = m.kind === 'fail'
        ? `<text class="sq-x" x="${end + dir * 14}" y="${y + 5}" text-anchor="middle">✖</text>` : '';
    const dot = m.kind === 'fail' ? '' : `<circle class="sq-dot" cx="${x1}" cy="${y}" r="5" style="--dx:${x2 - x1}px"/>`;
    return `<g${id} class="${base} sq-${m.cls || 'n'}" data-i="${i}" style="--d:${delay}">
        <line class="sq-line ${dash}" x1="${x1}" y1="${y}" x2="${end}" y2="${y}"${marker}/>
        ${cross}${dot}
        <text class="sq-label" x="${mid}" y="${y - 8}" text-anchor="middle"><tspan class="lbl">${esc(m.label)}</tspan></text>
        <g transform="translate(${x1 + dir * 18},${y})">${num}</g><title>${esc(m.why)}</title></g>`;
}

function sqRender(animate) {
    const stage = $('#sqStage');
    if (!state || !stage) return;
    const actors = sqActors();
    const count = Math.max(SQ.msgs.length, 1);
    const H = SQ_TOP + count * SQ_ROW + 10;
    const partitioned = new Set(state.nodes.map((n) => n.partition)).size > 1;
    const cp = state.mode === 'CP';

    let svg = `<svg class="sq-svg" viewBox="0 0 ${SQ_W} ${H}" role="img" aria-label="Diagrama de secuencia de la última operación">
        <defs>${['n', 'ok', 'warn', 'bad'].map((c) =>
        `<marker id="ar-${c}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
            <path class="mk mk-${c}" d="M0,0 L10,5 L0,10 z"/></marker>`).join('')}</defs>`;

    actors.forEach((a) => {
        const x = sqX(a);
        let sub = 'navegador', cls = 'client', stroke = '';
        if (a !== SQ_CLIENT) {
            const n = state.nodes.find((k) => k.id === a);
            const seen = state.nodes.filter((k) => k.up && k.partition === n.partition).length;
            cls = n.up ? 'node' : 'node off';
            sub = !n.up ? 'caído' : cp ? `ve ${seen}/${state.totalNodes} · ${seen >= state.quorum ? 'atiende' : 'rechaza'}` : 'copia local';
            if (partitioned) {
                sub += ` · ${GROUPS[n.partition] || n.partition}`;
                stroke = ` style="stroke:${SQ_GROUP_COLORS[n.partition % 3]}"`;
            }
        }
        svg += `<g class="sq-actor ${cls}"><rect x="${x - 78}" y="8" width="156" height="58" rx="10"${stroke}/>
            <text class="t1" x="${x}" y="32" text-anchor="middle">${esc(a)}</text>
            <text class="t2" x="${x}" y="52" text-anchor="middle">${esc(sub)}</text></g>
            <line class="sq-life${cls.includes('off') ? ' off' : ''}" x1="${x}" y1="68" x2="${x}" y2="${H - 6}"/>`;
    });

    if (!SQ.msgs.length) {
        svg += `<text class="sq-empty" x="${SQ_W / 2}" y="${SQ_TOP + 10}" text-anchor="middle">
            Ejecuta una operación CRUD (arriba) y verás aquí los mensajes que se cruzan.</text>`;
    }
    SQ.msgs.forEach((m, i) => {
        svg += sqMsgSvg(m, i, animate);
    });
    svg += '</svg>';
    stage.innerHTML = svg;

    SQ.async = SQ.msgs.map((m, i) => ({m, i})).filter((x) => x.m.kind === 'async');
    $('#sqList').innerHTML = SQ.msgs.map((m, i) => `<li data-i="${i}" class="${animate && SQ.mode === 'step' && i >= SQ.shown ? 'pending' : ''}">
        <b>${esc(m.label || m.text)}</b> <span class="muted">${esc(m.why)}</span>
        ${m.kind === 'async' ? `<span class="chip warn" id="sq-s-${i}">…</span>` : ''}</li>`).join('');
    sqUpdateAsync();
    $('#sqNext').disabled = SQ.mode !== 'step' || SQ.shown >= SQ.msgs.length;
}

/** Actualiza el estado (en tránsito / bloqueado / entregado) de las flechas asíncronas con la cola real. */
function sqUpdateAsync() {
    if (!state) return;
    SQ.async.forEach(({m, i}) => {
        const p = state.pending.filter((x) => x.from === m.from && x.to === m.to && x.key === m.key)
            .sort((a, b) => b.id - a.id)[0];
        const inFlight = p && p.status === 'en tránsito';
        const cls = !p ? 'ok' : inFlight ? 'warn' : 'bad';
        const txt = !p ? 'entregado ✓' : inFlight ? `en tránsito · ${p.dueInMs} ms` : p.status;
        const g = $(`#sq-a-${i}`);
        if (g) {
            g.setAttribute('class', g.getAttribute('class').replace(/sq-(n|ok|warn|bad)\b/, 'sq-' + cls));
            g.querySelector('.sq-line')?.setAttribute('marker-end', `url(#ar-${cls})`);
            const l = g.querySelector('.lbl');
            if (l) l.textContent = `replicar · ${txt}`;
        }
        const s = $(`#sq-s-${i}`);
        if (s) {
            s.className = 'chip ' + cls;
            s.textContent = txt;
        }
    });
}

/** Registra una operación CRUD (la llama crud()) y la dibuja. */
function sqRecord(rec) {
    if (!state) return;
    SQ.rec = rec;
    SQ.msgs = sqBuild(rec);
    SQ.shown = 0;
    const ex = explain(rec.r, rec.op);
    $('#sqVerdict').className = 'verdict ' + ex.cls;
    $('#sqVerdict').innerHTML = `<span class="verdict-icon">${ex.icon}</span><div>${ex.text}</div>`;
    $('#sqVerdict').hidden = false;
    sqRender(true);
    if (SQ.mode === 'step') sqNext();
}

function sqNext() {
    if (SQ.shown >= SQ.msgs.length) return;
    const el = document.querySelector(`#sqStage .msg[data-i="${SQ.shown}"]`);
    if (el) {
        el.classList.add('show', 'play');
        el.style.setProperty('--d', '0s');
    }
    document.querySelectorAll('#sqList li').forEach((li) => {
        const k = Number(li.dataset.i);
        li.classList.toggle('pending', k > SQ.shown);
        li.classList.toggle('current', k === SQ.shown);
    });
    SQ.shown++;
    $('#sqNext').disabled = SQ.shown >= SQ.msgs.length;
}

function sqReplay() {
    SQ.shown = 0;
    sqRender(true);
    if (SQ.mode === 'step') sqNext();
}

/** Re-dibuja las líneas de vida solo si cambió la topología (modo, nodos caídos, particiones). */
function sqTopology() {
    const sig = state.mode + state.nodes.map((n) => n.id + n.up + n.partition).join();
    if (sig !== SQ.topoSig) {
        SQ.topoSig = sig;
        SQ.shown = SQ.msgs.length;
        sqRender(false);
    } else {
        sqUpdateAsync();
    }
}

function sqSyncForm(node, id, name, qty) {
    if ($('#sqNode')) {
        $('#sqNode').value = node;
        $('#sqId').value = id;
        $('#sqName').value = name;
        $('#sqQty').value = qty;
    }
}

/* ───────────── 8) Tema, acento y navegación ───────────── */

const root = document.documentElement;

function setTheme(theme) {
    root.dataset.theme = theme;
    $('#themeSel').value = theme;
    try { localStorage.setItem('lab.theme', theme); } catch (e) { /* sin almacenamiento */ }
}

function setAccent(accent) {
    root.dataset.accent = accent;
    document.querySelectorAll('#accentSwatches button').forEach((b) => b.classList.toggle('on', b.dataset.accent === accent));
    try { localStorage.setItem('lab.accent', accent); } catch (e) { /* sin almacenamiento */ }
}

const VIEWS = {panel: 'Panel', secuencia: 'Secuencia', guias: 'Pruebas guiadas', experimentos: 'Experimentos', conceptos: 'Conceptos'};

/** Router mínimo por hash: #/panel, #/guias, #/experimentos, #/conceptos. */
function route() {
    const name = (location.hash.match(/^#\/(\w+)/) || [])[1];
    const view = VIEWS[name] ? name : 'panel';
    document.querySelectorAll('.view').forEach((v) => { v.hidden = v.dataset.view !== view; });
    document.querySelectorAll('.side-nav a').forEach((a) => {
        const on = a.dataset.view === view;
        a.classList.toggle('on', on);
        if (on) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    $('#viewTitle').textContent = VIEWS[view];
    document.title = VIEWS[view] + ' · Laboratorio CAP & BASE';
    document.body.classList.remove('nav-open');
}

window.addEventListener('hashchange', route);
$('#themeSel').addEventListener('change', (e) => setTheme(e.target.value));
$('#accentSwatches').addEventListener('click', (e) => {
    const b = e.target.closest('[data-accent]');
    if (b) setAccent(b.dataset.accent);
});
$('#sqMode').addEventListener('change', (e) => {
    SQ.mode = e.target.value;
    sqReplay();
});
$('#sqSpeed').addEventListener('change', (e) => {
    SQ.speed = Number(e.target.value);
    sqReplay();
});
$('#menuBtn').addEventListener('click', () => document.body.classList.toggle('nav-open'));
$('#scrim').addEventListener('click', () => document.body.classList.remove('nav-open'));

(async function init() {
    setTheme(root.dataset.theme || 'auto');
    setAccent(root.dataset.accent || 'blue');
    route();
    renderLegend();

    $('#guideButtons').innerHTML = RECIPES.map((r) =>
        `<button class="btn" data-recipe="${r.id}" title="${esc(r.goal)}">${esc(r.title)}</button>`).join('');
    $('#guideButtons').addEventListener('click', (e) => {
        const b = e.target.closest('[data-recipe]');
        if (b) openRecipe(b.dataset.recipe);
    });

    const cat = await api('GET', '/api/lab/scenarios');
    if (cat.ok) {
        $('#scnButtons').innerHTML = cat.json.map((s) =>
            `<button class="btn" data-scn="${esc(s.id)}" title="${esc(s.goal)}">${esc(s.title)}</button>`).join('');
        $('#scnButtons').addEventListener('click', (e) => {
            const b = e.target.closest('[data-scn]');
            if (b) runScenario(b.dataset.scn, b);
        });
    }

    refresh();
    setInterval(refresh, 1000);
})();
