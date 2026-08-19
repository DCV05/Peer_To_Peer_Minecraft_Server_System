# PLAN MAESTRO — Host-lock GitHub + Autosave en caliente + Soporte Fabric

> Tres bloques en orden de dependencia. El A (lock) está a medias; B y C parten
> de infraestructura que la app YA tiene. Todo sin mods: la app es el orquestador
> externo que habla con la consola vanilla del server (stdin), igual en Forge que
> en Fabric.

## Investigación (hecha, con fuentes)

- `save-off` / `save-all flush` / `save-on` son comandos **vanilla de Java
  Edition**: los trae el jar del server que Forge/Fabric envuelven. Sin mods.
  (minecraft.wiki/w/Commands/save; la clase `SaveAllCommand` aparece en los
  mappings de Fabric porque es del server vanilla.)
- Prior art de "mundo → git": **FastBack** (mod Fabric; valida el concepto y sus
  gotchas: desactiva delta compression y poda agresivamente para controlar el
  tamaño) y **nicolaschan/minecraft-backup** (la variante SIN mods: script
  externo por consola/RCON con la misma secuencia — el patrón de esta app).
- Aviso de la comunidad: GitHub va justo con mundos grandes (recomiendan <500MB;
  farmland ya va en 6,4GiB y funciona, pero el autosave frecuente acelera el
  crecimiento del repo → intervalo por defecto conservador y trim futuro).
- **Fabric es MÁS fácil de instalar que Forge**: un único jar de server
  descargable por URL determinista de la API meta:
  `https://meta.fabricmc.net/v2/versions/loader/{game}/{loader}/{installer}/server/jar`
  (drop-in del server.jar vanilla, arranca con `java -jar`). Versiones
  listables en JSON: `/v2/versions/game` y `/v2/versions/loader/{game}`.
- **Windows**: un fichero abierto bloquea borrar/renombrar, NO leer — la JVM
  abre con `FILE_SHARE_READ|WRITE`, y git solo lee el worktree. `session.lock`
  (el único candado intencionado de Minecraft) está git-ignorado. Único ruido:
  el antivirus reteniendo un fichero ms al escanear → reintento al siguiente
  tick (cubierto en B1). El ecosistema entero de backups en caliente de
  Windows (plugins, paneles de hosting) valida el enfoque desde hace años.
- **Por qué a Víctor "nada le iba"**: su `activeAutoSave()` commitea a ciegas
  inmediatamente después de mandar `save-all flush`, sin esperar el
  "Saved the game" de la consola → copiaba en plena escritura del volcado.
  La idea era correcta; el fix es la espera confirmada (B1).
- **"En pack" (petición de Víctor)**: verificado en el repo real — ya se sube
  el server COMPLETO, no solo el mundo (world/ + libraries/ con Forge +
  config/ + mods/ cuando exista + run.sh/run.bat + eula + ops/whitelist/bans +
  usercache). Excluidos: logs/, crash-reports/, world-import-backups/,
  session.lock, *.tmp. Locales por máquina a propósito: server.properties y
  user_jvm_args.txt (RAM/puerto de cada peer no se pisan). El autosave usa el
  MISMO commit de árbol completo: nada que añadir aquí.

## Estado ya implementado (sin commitear)

- `GitUtils.remoteRepoFullName()` + `parseRepoFullName()` (F0 del lock).
- `jgit/HostLock.java` completo: lease en rama `host-lock` vía Contents API con
  CAS por sha (GitHub arbitra las carreras), latido 300 s, lease 900 s,
  frescura por fechas de GitHub (nunca reloj local), takeover de locks
  caducados, retoma del lock propio tras crash, release que nunca borra el
  lock de otro.
- Helpers HTTP de GitUtils abiertos a package (`authenticatedRequest`,
  `githubApiBase`, `HTTP_CLIENT`, `JSON_MAPPER`, `encodePathSegment`).

---

## BLOQUE A — Terminar el host-lock (commits con caducidad)

### A1. Tests de HostLock (`tests/jgit/HostLockTest.java`)
Mock `HttpServer` local apuntado por `p2pmss.githubApiBase` (patrón
`GitHubApiTest`), implementando contents GET/PUT/DELETE con lógica de sha,
`/commits` con fecha controlable y refs. Escenarios:
libre→adquiere · rama inexistente→la crea y adquiere · fresco-de-otro→rechaza
nombrando al host · caducado→takeover · mío→retoma · carrera (PUT 409)→rechaza
con el ganador · latido refresca solo si es mío · release borra solo el mío ·
API inaccesible→acquire falla cerrado (no arranca).

### A2. Integración en MainFrame
- `startServerFromDashboard`: tras el discovery UDP y antes del backup,
  `HostLock.acquire(remoteRepoFullName)`. Rechazado por peer → fase
  REMOTE_HOST con "X is already hosting (last heartbeat …)" + diálogo; fallo de
  red → `setDashboardFailure` (no arrancar: fail-closed como el pull). Server
  recién creado (sin remote aún): adquirir justo después de
  `configurePrivateBackup`.
- Latido: `java.util.Timer` daemon cada `HostLock.HEARTBEAT_SECONDS`; se arranca
  al confirmarse el arranque de Forge, se auto-cancela si el proceso muere solo
  (F3) y loguea en RECENT ACTIVITY latido ok/fallo.
- `turnOffServer`: parar el timer al pedir el stop; tras el backup verificado,
  `HostLock.release` — **el commit final de guardado invalida el último
  healthcheck al instante** (no se espera la caducidad); si el release falla,
  avisar de que caducará solo en 15 min.

### A3. Cierre del bloque
Suite completa verde · `mvn package` · commit+push a la rama del fork.

---

## BLOQUE B — Autosave del mundo en caliente (sin mods)

Ya existe el 80%: `GitUtils.activeAutoSave()` (hilo con `save-off` →
`save-all flush` → `/say` aviso → `autoCommitAndPush()` batched → `save-on`),
persistencia del intervalo en properties con mínimo de 2 min, y el combo de UI
("5 mins…24 h") oculto con `setVisible(false)`. Nadie lo arranca hoy.

### B1. Endurecer el hilo existente (`GitUtils.activeAutoSave`)
- **Esperar la confirmación real** del guardado: tras `save-all flush`, esperar
  la línea "Saved the game" en la consola (con timeout acotado) antes de
  commitear — hoy commitea a ciegas justo después de mandar el comando.
  Mecanismo: callback registrable en el lector de consola de ForgeUtils
  (`handleServerOutputLine` ya parsea líneas para presencia de jugadores).
- **`save-on` en `finally`**: si `autoCommitAndPush` lanza, hoy el server se
  queda con el guardado desactivado para siempre. Inaceptable.
- **Exclusión mutua** con el stop-backup y con cualquier commit concurrente:
  flag/lock simple; si el stop llega en mitad de un autosave, esperar a que
  termine el lote en curso.
- Un push de autosave fallido: reintentar al siguiente tick (no matar el hilo
  como hoy) y pintar el estado real en el dashboard (AMBER "AUTOSAVE RETRY").

### B2. Encendido y UI
- Arrancar `activeAutoSave()` cuando Forge dé el "Done" (mismo punto donde se
  arranca el latido del lock) y pararlo en el stop y si el proceso muere.
- Destapar `autoSaveIntervalLabel`/`autoSaveIntervalSelect` (quitar los
  `setVisible(false)`), añadir opción "Off", **default 10 min** (equilibrio
  pérdida-máxima vs crecimiento del repo; 5 min elegible en el combo).
- Dashboard: última copia en caliente en el tile BACKUP ("LIVE SAVE HH:MM").

### B3. Interacción con el lock (decisión de diseño)
El autosave commitea en la rama del mundo; el latido commitea en `host-lock`.
Son independientes, PERO un autosave exitoso también es prueba de vida →
oportunidad: si el autosave acaba de pushear hace <5 min, el latido puede
saltarse ese tick (menos ruido). Opcional, marcado como mejora, no bloqueante.

### B4. Tests
- Secuencia de comandos emitida en orden y `save-on` garantizado con push
  fallando (writer capturado, patrón ForgeUtilsTest).
- Espera de "Saved the game" con timeout.
- No-solape autosave/stop.

---

## BLOQUE C — Soporte Fabric en el wizard

### C1. Modelo de loader
- `LoaderKind { FORGE, FABRIC }` detectado por huella en la carpeta
  (`fabric-server.jar` / `libraries/net/minecraftforge`) y persistido en
  `recentServers`-metadata. Todo el flujo común (import, backup, lock,
  autosave, consola) es loader-agnóstico y NO se toca.

### C2. Wizard "Create server": selector Forge | Fabric
- Rama Fabric: poblar Minecraft versions desde `meta.fabricmc.net/v2/versions/game`
  (estables) y loader versions desde `/v2/versions/loader/{game}`.
- Instalar = descargar UN jar de la URL determinista
  `/v2/versions/loader/{game}/{loader}/{installer}/server/jar` a
  `fabric-server.jar` + generar `run.sh`/`run.bat`
  (`java @user_jvm_args.txt -jar fabric-server.jar nogui`) + `user_jvm_args.txt`
  con `-Xmx4G` + eula gate (reusar paso 2 actual). Sin installer, sin log de
  instalación: más simple que Forge.
- `ForgeUtils.buildStartupCommand` ya ejecuta run.sh/run.bat → funciona sin
  cambios; renombrar conceptualmente (JavaDoc) sin romper API.

### C3. Tests
- Mock HTTP de meta.fabricmc.net (mismo patrón de HttpServer local +
  system property nueva `p2pmss.fabricMetaBase`).
- Descarga a jar + generación de scripts multiplataforma.
- Detección de loader por huella.

### C4. Nota de compatibilidad
El mundo farmland es Forge 1.19: NO migrar servers existentes de loader (los
mods no son compatibles). Fabric aplica a servers nuevos.

---

## Orden, estimación y criterios de cierre

1. **A** (~medio día): 1 test class nueva + ~150 líneas MainFrame. Cierra la
   promesa del lock ya empezada.
2. **B** (~medio día): ~120 líneas GitUtils/ForgeUtils/MainFrame + tests.
3. **C** (~1 día): wizard + ForgeUtils + tests con mock meta.

Cada bloque termina con: suite completa verde · `mvn package` · commit
en `feature/funcional-minima-dashboard` del fork · nota en README.
Gate E2E real (dos peers, GitHub real) al final de A y de B con aprobación
explícita.

## Checklist de ficheros por bloque

### Bloque A (host-lock)
- `tests/jgit/HostLockTest.java` — NUEVO (mock HttpServer, 9 escenarios).
- `src/view/MainFrame.java` — acquire en `startServerFromDashboard`, timer de
  latido (arrancar tras "Done", cancelar en stop/crash), release en
  `turnOffServer` tras el backup verificado, mensajes de activity.
- (ya hechos: `src/jgit/HostLock.java`, `GitUtils.remoteRepoFullName`).

### Bloque B (autosave en caliente)
- `src/minecraftServerManagement/ForgeUtils.java` — callback registrable de
  líneas de consola (hook en `getServerOutputs`) para esperar "Saved the game".
- `src/jgit/GitUtils.java` — `activeAutoSave()`: espera confirmada con timeout,
  `save-on` en finally, exclusión mutua con el stop, reintento en fallo de push.
- `src/view/MainFrame.java` — arrancar autosave tras "Done", pararlo en stop;
  destapar `autoSaveIntervalLabel`/`Select` + opción Off + default 10 min;
  "LIVE SAVE HH:MM" en tile BACKUP.
- `tests/jgit/GitUtilsAutoSaveTest.java` — NUEVO (writer capturado).

### Bloque C (Fabric)
- `src/minecraftServerManagement/LoaderKind.java` — NUEVO (detección por huella).
- `src/minecraftServerManagement/FabricInstaller.java` — NUEVO (meta API +
  descarga del jar + generación run.sh/run.bat/user_jvm_args).
- `src/view/dashboard/ForgeVersionWizard.java` — selector de loader + catálogo
  Fabric.
- `tests/minecraftServerManagement/FabricInstallerTest.java` — NUEVO (mock meta
  con property `p2pmss.fabricMetaBase`).

## Riesgos
- Crecimiento del repo con autosave frecuente (mundos grandes): default 10 min,
  documentar, y "world trim/squash" como mejora futura (prior art: FastBack).
- Latido/autosave compartiendo el rate limit del PAT: ~30 req/h en el peor
  caso — irrelevante frente a 5000/h.
- Apps desactualizadas en otros peers ignoran lock y autosave: protege solo
  entre versiones nuevas (documentar en README, igual que el discovery).
