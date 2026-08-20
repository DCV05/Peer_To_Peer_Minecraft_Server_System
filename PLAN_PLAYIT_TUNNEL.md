# Bloque D — URL pública opcional vía playit.gg integrado en la app

> Objetivo: que cualquier amigo con el Minecraft Java de serie pueda entrar
> escribiendo una dirección fija (`algo.ply.gg`), sin VPN, sin abrir puertos y
> sin instalar nada. Opcional por servidor: quien no lo active, nada cambia.

## Base técnica verificada (código real, no docs)

Del repo oficial `playit-cloud/playit-minecraft-plugin` (~5.3K líneas Java, MIT;
lib core BSD-2):

- `gg.playit.api` — cliente REST de api.playit.gg con Jackson. **Java puro.**
- `gg.playit.control` — canal de control por **UDP** (`DatagramSocket`) con los
  servidores de playit: keepalive + aviso "nuevo cliente". **Java puro.**
- `gg.playit.messages` — encoding binario del protocolo. **Java puro.**
- `PlayitTcpTunnel` — puente de datos; usa netty SOLO porque el plugin Bukkit
  inyecta en el pipeline del server. Nosotros lo sustituimos por un puente
  `Socket`↔`Socket` clásico (~100 líneas): conexión TCP saliente al punto de
  playit con la `connectionKey` + copia bidireccional hacia `localhost:25565`.

Flujo de claim confirmado en `PlayitKeysSetup`:
1. Generar claim code aleatorio (16 bytes hex).
2. Abrir `https://playit.gg/claim/{code}` en el navegador — el usuario acepta
   con cuenta o como **invitado** (sin registro). Una sola vez.
3. La app pollea `claimSetup` hasta la aceptación y canjea con `claimExchange`
   → `secret_key`. Desde ahí, todo por API sin navegador.

## Decisiones de diseño

- **Opt-in por servidor**: selector en la página Backups del dashboard
  ("Public URL: Off / playit.gg"). Por defecto Off.
- **La clave del túnel viaja en el repo del mundo** (`p2pmss/playit-agent.json`
  en la rama principal): quien tenga el candado levanta EL MISMO túnel → la
  dirección pública no cambia aunque cambie el host. El repo privado ya es la
  frontera de confianza (quien lo tiene puede hostear el mundo entero).
- **Ciclo ligado al server**: túnel arriba en `startHostServices()` (donde ya
  arrancan heartbeat y autosave), abajo en `turnOffServer()`. Si el túnel cae,
  reintento con backoff; el server nunca se ve afectado.
- **Vendor, no dependencia**: copiamos los 3 paquetes puros a `src/playit/`
  con su atribución de licencia en cabecera. Sin Gradle, sin netty, sin jar
  externo. Únicas deps nuevas: ninguna (Jackson ya está en el proyecto).

## Fases

### D1 — Vendor + puente TCP (`src/playit/`)
- Copiar `api/`, `control/`, `messages/` del plugin oficial (atribución MIT/BSD
  en cada fichero). Adaptar package a `playit.*` y quitar `jakarta.validation`
  y `commons-io` (usos triviales).
- `playit/TcpBridge.java` nuevo: por cada "nuevo cliente" del canal de control,
  abrir TCP al claim address de playit + TCP a `localhost:<puerto MC>` y copiar
  bytes en dos hilos daemon. Cierre limpio en ambos sentidos.
- Property `p2pmss.playitApiBase` para mockear la API en tests (patrón HostLock).

### D2 — `playit/PlayitTunnel.java` (gestor)
Máquina de estados: `DISABLED → NEEDS_CLAIM → CLAIMING → READY → ONLINE`
(+ `ERROR` con retry). API:
- `ensureClaimed(onUrl)` — genera claim code, entrega la URL para abrir el
  navegador, pollea hasta el secret. Guarda el secret.
- `ensureTunnel()` — busca/crea por API el túnel "minecraft-java" hacia el
  puerto local y devuelve la dirección pública fija.
- `start()/stop()` — canal de control + bridges; hilo daemon con backoff.
- `publicAddress()` — para el dashboard.

### D3 — Clave compartida por el repo
- `p2pmss/playit-agent.json` = `{ "secret_key": ..., "tunnel_address": ... }`
  commiteado en la rama del mundo (NO machine-local): entra en el flujo normal
  de backup/pull, así el secret y la dirección llegan solos al siguiente host.
- Si el fichero no existe al activar la opción → flujo de claim (una vez por
  mundo, lo hace el primero que lo activa).

### D4 — Integración MainFrame + dashboard
- Selector "PUBLIC URL" en la página Backups (Off / playit.gg), persistido como
  el intervalo de autosave.
- `startHostServices()`: si activado → `PlayitTunnel.start()`; actividad
  "Public URL online: X.ply.gg". `turnOffServer()`: `stop()`.
- Tile/fila en el dashboard con la dirección y botón COPY. Si falta el claim,
  botón "AUTHORIZE PLAYIT" que abre el navegador y muestra el progreso.

### D5 — Tests (patrón HostLockTest, mock HttpServer)
- Claim: setup→pending→accepted→exchange→secret persistido; rechazo y timeout.
- Tunnel ensure: reutiliza túnel existente / crea uno nuevo; dirección correcta.
- TcpBridge: bytes cruzan en ambos sentidos y cierre limpio (sockets locales).
- Canal de control UDP real: NO se testea unitario (servidores de playit);
  queda cubierto por el e2e manual.

### D6 — E2E manual (con Víctor)
1. Daniel activa Public URL en farmland → claim como invitado → dirección fija.
2. Amigo con Minecraft vanilla entra por `X.ply.gg`. Jugar unos minutos.
3. STOP → candado liberado → Víctor arranca → misma dirección, sin reclaim.

## Riesgos y mitigación
- **Protocolo de control puede evolucionar**: vendorizamos la versión del plugin
  oficial mantenido; si playit rompe compatibilidad, el fallo es visible en la
  actividad del dashboard y el server sigue jugable en LAN/directo.
- **Secret en el repo**: documentar en README que quien tiene el repo controla
  el túnel (coherente con que ya controla el mundo). Regenerable desde la web
  de playit si se filtra.
- **UDP bloqueado en alguna red** (raro en casas): mensaje claro en actividad.

## Estimación
D1 ~2-3 h (vendor + bridge) · D2 ~2 h · D3 ~1 h · D4 ~2 h · D5 ~2 h.
Total ~1 jornada y media de trabajo efectivo.
