# Plan: versión funcional mínima

## Objetivo

Dejar la rama `NoAI` utilizable con el flujo GitHub actual basado en PAT, sin OAuth nuevo, sin reescribir la arquitectura y sin tocar la configuración Git global del equipo.

El resultado mínimo debe permitir:

1. Iniciar sesión con una cuenta GitHub concreta.
2. Crear y enlazar un repositorio privado.
3. Invitar a otro host y clonar el servidor.
4. Descargar el estado remoto antes de arrancar Forge.
5. Subir y verificar el estado al apagar el servidor.
6. Ejecutar el servidor en macOS y Windows sin romper el flujo existente.

## Alcance

Máximo cinco archivos de producción:

- `src/view/MainFrame.java`
- `src/jgit/GitUtils.java`
- `src/view/GitWindows.java`
- `src/jgit/TokenStore.java`
- `src/minecraftServerManagement/ForgeUtils.java`

Se conserva el arreglo de identidad ya presente en `GitUtils.java`: identidad local por repositorio y autor/committer obtenidos desde la sesión de la aplicación.

Quedan fuera de esta versión: OAuth/Device Flow, rediseño de Google Drive, autosave periódico, Git LFS, refactor general de Swing y cambios visuales no necesarios.

## Fase 1 — Corregir los cuatro bloqueantes

### 1. Sincronización GitHub antes de arrancar y al apagar

En `MainFrame.java`:

- Sustituir las comparaciones `== "GitHub"` por una única comprobación por valor.
- Eliminar la dependencia incorrecta de `cloudProvider`, que representa Google Drive, para entrar en el flujo GitHub.
- Quitar el `pull` asíncrono lanzado sin esperar al abrir el servidor.
- Ejecutar y verificar el `pull` justo antes de arrancar Forge. Si falla o hay conflicto, no arrancar el servidor y mostrar un error recuperable.
- Al pulsar `Off`, esperar a que Forge termine y comprobar el resultado real del commit/push. No mostrar éxito si el remoto lo rechazó.

### 2. Creación de repositorio recuperable

En `GitUtils.java` y `MainFrame.java`:

- Validar el `POST /user/repos`: solo `201 Created` es éxito.
- Leer `clone_url` con Jackson; eliminar el parser manual para esta operación.
- No crear `.git` local hasta confirmar que GitHub creó el remoto.
- Si GitHub responde `401`, `403`, `422` o `5xx`, mostrar el motivo y permitir reintentar.
- Encadenar las fases: remoto creado → init local → identidad local → origin → commit → push verificado.
- Pasar `server.properties` y `user_jvm_args.txt` como rutas relativas al índice. Actualmente las rutas absolutas lanzan `InvalidPathException` después de haber creado y empujado el repositorio.
- Aplicar las mismas marcas locales después de clonar un repositorio invitado.

### 3. Verificar el resultado real de cada push

En `GitUtils.java`:

- Crear un helper que recorra los `PushResult`/`RemoteRefUpdate` de JGit.
- Aceptar únicamente estados exitosos (`OK` o `UP_TO_DATE`).
- Tratar `REJECTED_NONFASTFORWARD`, `REJECTED_*` y `NOT_ATTEMPTED` como fallo visible.
- Si no hay cambios nuevos, no forzar un commit vacío, pero sí intentar subir commits locales pendientes.
- Usar `try-with-resources` para cerrar instancias `Git`.

### 4. Persistencia de invitaciones

En `GitUtils.java`:

- Leer por completo `joined_repos.properties`, cerrar la lectura y después abrir la escritura.
- Conservar todos los repositorios aceptados y evitar duplicados. La implementación actual conserva únicamente el último.

## Fase 2 — Endurecer el login sin cambiar de sistema

En `GitWindows.java` y `TokenStore.java`:

- Mantener el PAT; no implementar OAuth en esta versión.
- Hacer un único `GET /user`, exigir estado `200` y obtener de esa respuesta el `login` real.
- Impedir guardar un nickname distinto del propietario del token.
- Considerar `401`, `403` y errores de red como sesión inválida o no verificable; nunca aceptarlos como login correcto.
- Guardar metadata y token de forma coherente: el login solo termina si ambos ficheros se escribieron correctamente.
- `sessionIsOpened()` debe validar token, nickname y email legibles, no solo la existencia de `credentials.dat`.
- Preservar una sesión existente válida; no cerrarla ni borrarla durante la migración.
- No mostrar el PAT completo en la ventana de perfil y usar un campo de contraseña en el login.
- Manejar listas de invitaciones nulas o respuestas REST de error sin `NullPointerException`.

El uso de Keychain/AES-GCM queda recomendado para una versión posterior: mejora seguridad, pero no es necesario para desbloquear el funcionamiento inmediato.

## Fase 3 — Arranque mínimo multiplataforma

En `ForgeUtils.java` y `MainFrame.java`:

- Reconocer `run.sh`/`start.sh` además de `run.bat`/`start.bat`.
- En Windows ejecutar `cmd.exe /c run.bat nogui`.
- En macOS/Linux ejecutar `/bin/sh run.sh nogui`.
- Mantener un fallback para servidores antiguos que arrancan con `java -jar`.
- No tokenizar manualmente una línea de `.bat`; actualmente `%*` y los argument files modernos de Forge pueden llegar como argumentos literales inválidos.
- Resolver la carpeta `mods` con `Path.resolve("mods")`.
- Actualizar `actualServerPort` cada vez que se cambia de servidor reciente.

Si la primera entrega se limita expresamente a Windows, esta fase se puede reducir; para probar la aplicación en el Mac actual es necesaria.

## Fase 4 — Verificación antes de usar datos reales

### Gate A: compilación

- Construir con Java 21 en una copia limpia temporal.
- `mvn test` y `mvn package` deben terminar en `BUILD SUCCESS`.
- No sobrescribir el JAR anterior hasta superar todos los gates.

### Gate B: JGit local, sin GitHub

Simular dos hosts con un remoto bare temporal:

1. Crear, commitear y empujar desde host A.
2. Clonar desde host B y verificar identidad/configuración local.
3. Empujar un cambio desde A y exigir que B haga pull antes de arrancar.
4. Crear divergencia y comprobar que un push rechazado se reporta como fallo.
5. Verificar autor y committer de ambos hosts.
6. Verificar las marcas locales de `server.properties` y `user_jvm_args.txt`.
7. Guardar dos invitaciones y comprobar que ambas persisten.

### Gate C: proceso Forge

- Probar primero con scripts inocuos `run.sh` y `run.bat` que simulen la salida `Done`.
- Después abrir un servidor Forge de prueba y verificar arranque, consola, `/stop` y cierre limpio.

### Gate D: GitHub real

Solo con aprobación explícita:

1. Introducir el PAT personal desde la propia aplicación; no usar la sesión `gh` de Hellomatik.
2. Confirmar que `GET /user` devuelve la cuenta esperada.
3. Crear un repositorio privado de prueba con nombre inequívoco.
4. Ejecutar create → push → invite → accept → clone → pull → stop → push.
5. Verificar por API que el último commit llegó y tiene el autor esperado.
6. No borrar automáticamente el repositorio de prueba.

## Criterios de aceptación

- La configuración Git global del Mac no cambia.
- Un error REST no deja la interfaz en un estado sin reintento.
- El botón `On` no arranca con un pull fallido o conflictivo.
- El botón `Off` no indica éxito si GitHub rechazó el push.
- Dos hosts pueden intercambiar dos ciclos consecutivos sin perder commits.
- La sesión existente sigue disponible después de actualizar la aplicación.
- El JAR nuevo se entrega separado del anterior.
- No se hace commit, push ni publicación sin aprobación explícita.

## Orden de implementación propuesto

1. Fase 1 completa.
2. Gates A y B.
3. Fase 2.
4. Repetir Gates A y B.
5. Fase 3 y Gate C.
6. Revisión del diff completo.
7. Gate D con autorización del usuario.
8. Generar el JAR versionado final sin sobrescribir el artefacto anterior.
