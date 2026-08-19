# Plan — Sustituir el login por PAT manual por OAuth Device Flow

Estado: propuesta. No implementado. Nada de código escrito todavía.

Referencia de partida: `hellomatik-org/hangar` → `scripts/templates/setup-git.sh`,
que resuelve el primer login de GitHub en una VM headless del Hangar.

---

## 1. Por qué Device Flow y no el flujo de siempre

P2PMSS se distribuye como un JAR a usuarios finales. Eso descarta cualquier
flujo que necesite `client_secret`, porque un secreto dentro de un binario
distribuido no es un secreto.

Comprobado contra la documentación de GitHub (agosto 2026):

| Flujo | ¿Necesita client_secret? | ¿Sirve aquí? |
|---|---|---|
| Web application flow | **Sí, obligatorio** (incluso usando PKCE) | No |
| Web application flow + PKCE | Sí — PKCE se recomienda, pero NO sustituye al secret | No |
| **Device flow** | **No.** "The `client_secret` is not needed for the device flow" | **Sí** |

GitHub sí soporta PKCE (`code_challenge` / `code_challenge_method=S256`) pero
sólo para el web flow, y aun así sigue exigiendo el `client_secret` en el
intercambio de código. Por tanto **device flow es el único camino limpio** para
una app de escritorio distribuida.

Efecto colateral positivo: el mismo argumento aplica al proveedor de Google, que
hoy sí embarca `credentials/GoolgeDriveCredentials.json` dentro del JAR
compilado (verificado: está en `target/*.jar`, que además está commiteado).

---

## 2. Cómo lo hace el Hangar (lo que copiamos)

```
setup-git (VM headless)
  |
  |-- 1. POST https://github.com/login/device/code
  |        client_id=<público>  scope="repo read:org gist workflow user:email"
  |        <-- { device_code, user_code, verification_uri, interval, expires_in }
  |
  |-- 2. Pinta caja ASCII: URL + código de 8 caracteres
  |
  |-- 3. Poll cada `interval` segundos:
  |        POST https://github.com/login/oauth/access_token
  |        client_id + device_code + grant_type=urn:ietf:params:oauth:grant-type:device_code
  |        <-- error=authorization_pending  -> seguir
  |        <-- error=slow_down              -> interval += 5
  |        <-- access_token                 -> listo
  |
  |-- 4. GET /user -> login, name, email
  |        si email == null -> GET /user/emails -> primary && verified
  |        si falla         -> <login>@users.noreply.github.com
  |
  '-- 5. Escribe git config + marca ~/.hangar-onboarded
```

Detalles del script que merece la pena robar tal cual:

- **Respeta `interval` de la respuesta** y sube +5s ante `slow_down`. Si no,
  GitHub empieza a rechazar.
- **Timeout por `expires_in`** (900s) con mensaje claro de "vuelve a ejecutar".
- **Cicatriz ya documentada en sus comentarios**: sin el scope `user:email`,
  `/user/emails` devuelve 404, y la versión anterior del script acababa
  escribiendo el JSON de error dentro de `git config user.email`. De ahí el
  fallback a `noreply`.

Lo que **no** copiamos: el Hangar reutiliza el `CLIENT_ID` de GitHub CLI
(`178c6fc778ccc68e1d6a`), por eso su caja dice "Autoriza GitHub CLI". Para
P2PMSS eso sería malo — el usuario vería que autoriza "GitHub CLI" desde una app
de Minecraft, y los scopes de gh son mucho más amplios de lo necesario.
App propia.

---

## 3. Qué cambia en P2PMSS (y qué no)

Lo importante: **el radio de impacto es un diálogo y una clase nueva.**

Todo el resto del código consume `TokenStore.getSavedUserData()`, que devuelve un
mapa `{nickname, email, token}`, y construye
`UsernamePasswordCredentialsProvider(nickname, token)`. Un token OAuth funciona
ahí exactamente igual que un PAT. No hay que tocar `GitUtils`, ni `MainFrame`, ni
el flujo de invitaciones, ni el push/pull.

```
ANTES                                  DESPUÉS
-----                                  -------
GitWindows.signIntoGitHubWnd           GitWindows.signIntoGitHubWnd
  3 JTextField (nick, email, token)      1 diálogo con user_code + botón
  checkNickname()   -> GET /users/x      GitHubDeviceFlow.requestDeviceCode()
  checkTokenValidity() -> GET /user      GitHubDeviceFlow.pollForToken()
  TokenStore.saveUserData(...)           GET /user + /user/emails
                                         TokenStore.saveUserData(...)   <-- igual
        |                                        |
        v                                        v
   data/credentials.dat (cifrado)         data/credentials.dat (cifrado)
   data/userData.properties               data/userData.properties
        |                                        |
        '--> todo lo demás sin cambios <---------'
```

Se pueden **borrar** `checkNickname()` y `checkTokenValidity()`: ya no hay nada
que validar a mano, porque el token lo emite GitHub y el login lo devuelve la
propia API.

---

## 4. Adaptación de headless a GUI

El Hangar pinta una caja ASCII porque está en una terminal. Aquí hay Swing, así
que el diálogo debería:

1. Mostrar el `user_code` en grande, con botón **Copiar código**.
2. Botón **Abrir GitHub** que lance `verification_uri` — y ojo, ya existe el
   helper: `ForgeUtils.openURL(String)`, el que usan para el EULA. Si GitHub
   devuelve `verification_uri_complete`, usar ese, que lleva el código
   pre-rellenado.
3. Barra de progreso indeterminada + "esperando autorización...".
4. **El polling va en un thread aparte, nunca en el EDT.** Toda actualización de
   UI con `SwingUtilities.invokeLater`. El proyecto ya usa este patrón en
   `MainFrame` para el arranque del servidor y las subidas a Drive.
5. Botón **Cancelar** que corte el bucle de polling.
6. Cuenta atrás de los 15 minutos de `expires_in`.

Errores del polling que hay que tratar explícitamente:

| Respuesta | Qué hacer |
|---|---|
| `authorization_pending` | Seguir esperando |
| `slow_down` | `interval += 5` y seguir |
| `expired_token` | Cerrar y ofrecer reintentar |
| `access_denied` | El usuario denegó; cerrar sin error feo |
| `access_token` presente | Éxito |

---

## 5. Scopes

Lo que P2PMSS necesita de verdad, mirando lo que hace hoy `GitUtils`:

| Operación | Endpoint | Scope |
|---|---|---|
| Crear repo privado | `POST /user/repos` | `repo` |
| Invitar colaborador | `PUT /repos/{o}/{r}/collaborators/{u}` | `repo` |
| Listar/aceptar invitaciones | `/user/repository_invitations` | `repo` |
| Push / pull / clone HTTPS | — | `repo` |
| Leer login | `GET /user` | (ninguno extra) |
| Leer email | `GET /user/emails` | `user:email` |

**Total: `repo user:email`.** Nada de `workflow`, `gist` ni `read:org` — eso el
Hangar los pide porque `gh` hace muchas más cosas.

Aviso que hay que asumir conscientemente: el scope `repo` de una OAuth App da
acceso a **todos** los repos privados del usuario. GitHub no ofrece nada más
fino para OAuth Apps. Por eso la recomendación del README de usar una cuenta
secundaria pasa de ser un consejo a ser casi obligatoria.

---

## 5.bis ¿Quién hostea la OAuth App? Nadie

Una OAuth App de GitHub **no se hostea**: no hay servidor, ni backend, ni coste.
Es un registro en la configuración de una cuenta de GitHub — `client_id`,
nombre, logo y scopes. Con device flow no hace falta absolutamente nada
desplegado, porque no hay redirección que recibir.

```
Google Drive (hoy)          -> LocalServerReceiver en localhost:8888
                               + landing pages en p2pmss.vercel.app/OAuth/{success,failed}
                               ^^^ esto SÍ está hosteado (Vercel)

GitHub device flow (plan)   -> nada. El "callback" es que la app pregunta
                               "¿ya?" cada `interval` segundos.
```

Detalle del formulario: GitHub pide **Homepage URL** y **Authorization callback
URL** al registrar la app aunque device flow no los use nunca. Se rellenan con
algo válido (URL del repo, o la de `p2pmss.vercel.app` que ya existe) y se
olvidan.

**Lo que sí hay que decidir: bajo qué cuenta se registra.** Esa cuenta pasa a ser
el operador de facto del login de todos los usuarios.

- Puede colgar de una cuenta personal o de una organización de la que seas admin
  (límite: 100 OAuth apps por cuenta u org).
- El dueño ve la lista de usuarios que han autorizado la app.
- Si esa cuenta se cierra, o GitHub suspende la app, **nadie más puede darse de
  alta**. Los tokens ya emitidos siguen funcionando; lo que se rompe es el login
  nuevo. Ironía a tener presente: una app cuyo valor es no depender de un host
  único acabaría dependiendo de un registro OAuth único.
- GitHub avisa: en el registro, sólo información que consideres pública.

Criterio: si el JAR se va a distribuir, la app debe colgar de la cuenta u
organización dueña del proyecto, no de una cuenta personal cualquiera. Si es
para un grupo cerrado de amigos, cualquier cuenta vale.

## 6. La decisión que hay que tomar: OAuth App vs GitHub App

Esta es la bifurcación real del plan.

| | OAuth App + device flow | GitHub App + device flow |
|---|---|---|
| Granularidad | Todo o nada: `repo` = todos los repos privados | El usuario elige **a qué repos** da acceso |
| Caducidad del token | Sin caducidad por defecto (opcional: 8h + refresh) | 8h + refresh token por defecto |
| Trabajo extra | Ninguno | Hay que implementar el refresh y guardar 2 tokens |
| `TokenStore` | Sirve tal cual (guarda un string) | Hay que ampliarlo (token + refresh + expiry) |
| Encaja con el modelo P2P | Sí | Mejor aún: el invitado sólo cede el repo del servidor |

Recomendación: **empezar por OAuth App**, que es exactamente lo que hace el
Hangar y desbloquea el 100% de la funcionalidad actual sin trabajo extra. Dejar
GitHub App como evolución si molesta el scope amplio.

Si se activa la caducidad de tokens en la OAuth App, `TokenStore` deja de valer
tal cual: hoy cifra **un solo string**. Habría que guardar `access_token`,
`refresh_token` y `expires_at`, y refrescar antes de cada operación de red.

---

## 7. Sign out — asimetría que conviene conocer de antemano

Hoy `TokenStore.clear()` sólo borra ficheros locales. El PAT sigue vivo en
GitHub para siempre. Con OAuth mejora, pero no del todo:

- El endpoint para revocar de verdad (`DELETE /applications/{client_id}/token`)
  exige autenticación Basic con `client_id:client_secret` — y no tenemos secret.
- Solución realista: borrar local **y** abrir
  `https://github.com/settings/connections/applications/{client_id}` para que el
  usuario revoque el acceso con un clic.

Contraste: el proveedor de Google sí revoca de verdad
(`GoogleDriveCloudProvider.closeSession()` llama a `oauth2.googleapis.com/revoke`).
Conviene documentar la diferencia para no dar por hecho lo que no es.

---

## 8. Lo que OAuth NO arregla

Dos cosas que son ortogonales y siguen pendientes:

1. **La fuga del committer.** Comprobado empíricamente ejecutando JGit del propio
   jar contra un repo de prueba: `linkLocalRepoToExternal` (`GitUtils.java:157`)
   no fija autor, y `autoCommitAndPush` (`GitUtils.java:392`) fija autor pero no
   committer. Resultado: la identidad de `~/.gitconfig` global acaba en el
   historial aunque el login sea de otra cuenta. OAuth da el token y el login,
   pero si no se pasan explícitamente a JGit, esto sigue igual.
2. **Multi-cuenta.** Device flow permite reautenticar con otra cuenta, pero
   `TokenStore` sigue guardando una sola en rutas fijas.

Buena noticia: la fase 4 de abajo mata el punto 1 de paso, porque en cuanto se
tiene `login` y `email` de la API, fijarlos en el repo local es trivial.

---

## 9. Fases propuestas

| Fase | Qué | Dónde | Depende de |
|---|---|---|---|
| **F0** | Crear la OAuth App en GitHub y **marcar "Enable Device Flow"** (sin ese checkbox, `/login/device/code` falla). Anotar el `client_id`. | github.com, no es código | — |
| **F1** | Clase nueva sin UI: petición de device code + bucle de polling con manejo de `slow_down`/`expired_token`/`access_denied`. Probable ubicación: paquete `jgit` junto a `TokenStore`, o un paquete `auth` nuevo. | proyecto | F0 |
| **F2** | Reescribir `GitWindows.signIntoGitHubWnd`: fuera los 3 campos, dentro el diálogo de código + polling en thread. Borrar `checkNickname` y `checkTokenValidity`. | `view/GitWindows.java` | F1 |
| **F3** | Derivación de identidad: `GET /user`, fallback `/user/emails`, fallback `noreply`. **Validar el shape de la respuesta** antes de guardar, o se repite la cicatriz del Hangar (JSON de error guardado como email). | F1 + F2 | F2 |
| **F4** | Fijar `user.name`/`user.email` en el config **local** del repo al crear/clonar, y pasar autor + committer explícitos en los dos `commit()`. Arregla la fuga de `~/.gitconfig`. | `jgit/GitUtils.java` | F3 |
| **F5** | Sign out: borrado local + abrir la página de revocación de la app. | `jgit/TokenStore.java`, `view/MainFrame.java` | F2 |

F1 es autocontenida y se puede probar sola contra GitHub antes de tocar nada de
UI. Ese es el orden que menos riesgo tiene.

---

## 10. Riesgos y cosas a vigilar

- El `client_id` viaja en el JAR. Es público por diseño en device flow, no es una
  filtración — pero conviene que sea **propio**, no el de `gh`.
- GitHub aplica rate limit a la creación de device codes. Irrelevante en uso
  normal, relevante si se hace un bucle de pruebas.
- El polling debe ser cancelable: si el usuario cierra el diálogo, el thread
  tiene que morir, no quedarse latiendo contra GitHub 15 minutos.
- Un token OAuth revocado desde la web de GitHub deja la app con credenciales
  muertas y errores opacos. Merece un mensaje de "vuelve a iniciar sesión"
  cuando la API devuelva 401.
- El cifrado de `TokenStore` sigue siendo ofuscación (clave derivada de
  `user.name + os.name + user.home`, AES sin modo explícito). OAuth **reduce** el
  daño de una fuga — scopes acotados y revocable — pero no arregla el cifrado.
