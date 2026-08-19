# PLAN — Host-lock en GitHub (lease con latido) + convivencia con el discovery UDP

> Objetivo: que "alguien está jugando" quede registrado en GitHub como un lock con
> caducidad, de modo que el candado funcione por internet normal (sin VPN) y el
> discovery UDP quede como segunda opinión rápida en la LAN virtual.

## 0. Investigación previa (estado real del código)

### Mecanismo actual de "alguien juega"
- `MainFrame.startServerFromDashboard()` (línea ~826): broadcast UDP
  `DISCOVER: <networkName>` a `255.255.255.255:<puerto>` con timeout de 3 s
  (`NetworkDiscoverClient`). Si un peer responde `HERE;ONLINE=…`, aborta el
  arranque. El responder (`DiscoveryResponder`) lo crea `ForgeUtils:318` cuando
  el server está listo, y SOLO contesta si el puerto acepta conexiones.
- No existe ningún registro persistente: sin VPN común, el candado no ve nada.
  La única red de seguridad es el push rechazado (non-fast-forward) al parar —
  tarde, porque ahí ya hay dos historias del mundo.

### Infraestructura reutilizable para el lock
- `GitUtils` ya tiene un cliente HTTP GitHub completo: `authenticatedRequest()`
  (Bearer + API version), `githubApiBase()` parametrizable con la property
  `p2pmss.githubApiBase`, y `HTTP_CLIENT` compartido.
- Los tests (`GitHubApiTest`) ya levantan un `HttpServer` local y apuntan la
  property al mock: el lock se testea con el mismo patrón, sin red.
- El owner/repo NO está modelado: hay que derivarlo de la URL del remote
  (`remote.origin.url` en la config, ya accesible vía `hasRemoteOrigin`).
- El `PullResult`/`RemoteRefUpdate` verificado y el backup por lotes NO se tocan.

### Decisión de diseño: Contents API con CAS, no plumbing JGit
Dos opciones investigadas:
- (A) Rama huérfana manipulada con JGit local (ObjectInserter + push de ref):
  exige fetch/plumbing, más código, y el peer necesita el clone para consultar.
- (B) **GitHub Contents API sobre una rama `host-lock`** (elegida):
  - `GET /repos/{o}/{r}/contents/p2pmss/host-lock.json?ref=host-lock` → leer.
  - `PUT` con `sha` esperado → escritura **atómica** (409 si otro escribió antes:
    el propio 409 es el árbitro de la carrera).
  - `DELETE` con `sha` → liberar.
  - Cero JGit, reutiliza `authenticatedRequest`, testeable con el mock existente,
    y un peer puede consultar el lock sin tener el mundo clonado.
  - Los latidos generan commits SOLO en la rama `host-lock` (JSON pequeño);
    el historial del mundo queda intacto.

## 1. Contrato del lock

Fichero `p2pmss/host-lock.json` en la rama `host-lock`:
```json
{
  "host_nickname": "DCV05",
  "machine": "<hostname local>",
  "started_at": "<ISO-8601>",
  "lease_seconds": 600
}
```
- **Frescura**: NO se confía en relojes locales. La edad del lock se calcula con
  la fecha de committer del último commit del fichero, que devuelve la propia
  API de GitHub (fuente de verdad única).
- **Lease**: 600 s por defecto. **Latido**: re-PUT (con CAS) cada 180 s mientras
  Forge corre — el contenido no cambia, el commit refresca la fecha.
- Lock con edad > lease ⇒ muerto ⇒ cualquier peer puede robarlo (takeover con
  PUT sobre su `sha`).
- Lock fresco pero `host_nickname` == yo ⇒ es mi propio lock huérfano (crash y
  reinicio): se retoma sin esperar.

## 2. Flujos

### START (`startServerFromDashboard`)
1. Discovery UDP (como hoy): si hay `HERE`, abortar ya — ni se toca GitHub.
2. **NUEVO — adquirir lock** (solo si GitHub selected + sesión + remote):
   - GET del lock. 404 ⇒ libre. 200 ⇒ fresco-de-otro → abortar mostrando
     "X está jugando desde HH:MM" (fase REMOTE_HOST); caducado o mío → takeover.
   - PUT con `sha` esperado (sin `sha` si 404). 409 ⇒ otro ganó la carrera →
     releer y abortar mostrando quién.
   - Si la rama `host-lock` no existe: crearla una vez
     (`POST /git/refs` con el sha del HEAD de la rama por defecto).
3. Backup + pull + Forge (sin cambios).
4. Al llegar el "Done" de Forge: arrancar el timer de latido (180 s).
   Latido con 409 (robo anómalo) ⇒ aviso rojo en activity, no matar el server.

### STOP (`turnOffServer`)
1. `/stop` + backup verificado (sin cambios, con el fix reciente).
2. **NUEVO — liberar lock**: DELETE con `sha`. Si falla: aviso no bloqueante
   (el lease caducará solo a los 10 min).

### Degradación
- Sin red al arrancar: GET falla ⇒ mismo tratamiento que hoy un pull fallido
  (no arrancar; el candado es parte de la protección del mundo).
- Crash del host: sin latido ⇒ caduca a los 10 min ⇒ takeover.
- App vieja en otro peer: ignora el lock (misma limitación que el discovery:
  solo protege entre apps actualizadas — documentarlo en el README).

## 3. Fases de implementación

### F0 — owner/repo desde el remote (~40 líneas + tests)
- `GitUtils.remoteRepoFullName(Path)`: parsea `remote.origin.url`
  (`https://github.com/{owner}/{repo}.git` y variante sin `.git`) → `owner/repo`.
- Tests: URL https, sin .git, remote ausente.

### F1 — `jgit/HostLock` (~200 líneas + tests, el grueso)
- API: `record Status(boolean locked, boolean mine, boolean stale, String host, Instant heartbeat)`
  y métodos `read`, `acquire`, `heartbeat`, `release` — todos contra Contents API
  con CAS por `sha`, más `ensureLockBranch`.
- Tests con `HttpServer` mock (patrón `GitHubApiTest`): libre→adquiere ·
  ocupado-fresco→rechaza con nombre · caducado→takeover · mío→retoma ·
  carrera (PUT→409)→rechaza · release borra · latido refresca · GET con red
  caída→acquire falso.

### F2 — Integración MainFrame + dashboard (~120 líneas)
- Acquire tras el discovery y antes del backup; release tras el backup del stop.
- Timer de latido (arrancar en `markServerStarted`, parar en stop/crash).
- Dashboard: reutilizar fase `REMOTE_HOST` con detalle "Locked by X since HH:MM";
  línea en RECENT ACTIVITY en cada transición (acquired / refused / released /
  taken over). El dashboard no inventa: si el lock no se pudo leer, se dice.

### F3 — Robustez fina (~40 líneas)
- Releer-y-mostrar tras 409; tolerancia a `heartbeat` con el server ya parado;
  no dejar el timer vivo si Forge muere solo (hook en el watcher de proceso).

### F4 — Gate E2E manual (requiere aprobación para tocar GitHub real)
- Dos carpetas de server locales sobre el MISMO repo (`farmland_mc` o uno de
  prueba): arrancar A → intentar B (debe rechazar con nombre y hora) → parar A
  (lock liberado) → B arranca → matar A a lo bestia → esperar lease → B roba.
- Actualizar README (sección "How hosting is arbitrated") y añadir el lease a
  la ventana de settings como opcional futuro (no en esta pasada).

## 4. Riesgos y descartes
- **Rate limit**: latido cada 3 min = 20 requests/h por host — irrelevante
  (límite 5000/h del PAT).
- **Relojes locales**: descartado usarlos; la edad sale de las fechas de commit
  que devuelve GitHub.
- **Contents API en rama con historial creciente**: commits de ~300 bytes cada
  3 min de juego; aceptable. Si algún día molesta, se trunca la rama con un
  force-update puntual (fuera de alcance).
- **No se toca**: backup por lotes, preflight, pull, discovery (solo se ordena
  su prioridad), TokenStore.

## 5. Criterios de cierre
- Suite completa verde (52 actuales + ~10 nuevos).
- Gate F4 ejecutado con evidencia (capturas/log de los dos peers).
- `mvn package` regenerado y README actualizado.
