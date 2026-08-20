# Guía de estilo del proyecto

> Identificadores en inglés, comentarios en español. Aplica a TODO el código
> del repo (`src/` y `tests/`, incluido el vendorizado).

## Formato

- **Tabs** para indentar, finales de línea LF (lo fija `.editorconfig` y el formateador).
- **Llaves en línea nueva** (estilo Allman):
  ```java
  if( serverIsRunning )
  {
      stopServer();
  }
  ```
  Excepción: bloques triviales de una línea pueden ir en línea.
- **Espacio interior en los paréntesis** de condicionales y llamadas:
  `if( x )`, `for( int i = 0; i < n; i++ )`, `openServer( directory )`.
  Paréntesis vacíos sin espacio: `stop()`.
- Alineación vertical de `=` en grupos de asignaciones relacionadas cuando ayude a leer.

## Estructura del código

- **Un solo punto de salida** por método en clases de servicio/lógica: patrón
  `do { ... break; } while( false );` con una variable `result` que se retorna al
  final. Las validaciones van en cascada arriba, cada una asigna el error y hace
  `break`.
  ```java
  public UpdateOutcome check()
  {
      UpdateOutcome result = UpdateOutcome.none();
      do
      {
          if( releasesRepo() == null )
          {
              result = UpdateOutcome.failure( "missing-repo" );
              break;
          }
          // ... resto del flujo feliz al final del bloque
      }
      while( false );
      return result;
  }
  ```
  Ojo: si dentro hay un `for`/`switch`, el `break` sale del bucle interno, no del
  bloque. En esos métodos usa guard clauses + variable `result` única en vez de
  calcar el patrón con etiquetas.
- **Variables intermedias con nombre**: nada de expresiones complejas inline.
  Primero `String downloadUrl = ...;`, luego se usa. El nombre documenta.
- **Nombres descriptivos, sin siglas**: `serverDirectory`, no `srvDir`. También en
  los `catch`: `catch( IOException readFailure )`, nunca `catch( Exception e )`.
- **`final class` por defecto** salvo que la clase esté diseñada para heredarse.
- **Contratos de retorno uniformes** con `record` (resultado + error + datos), con
  código de error estable para la máquina separado del mensaje humano de la UI.
- Para records de 4+ campos (sobre todo con booleanos seguidos), factories con
  nombre (`ok(...)`, `failure(...)`) en vez de constructores posicionales.

## Comentarios y documentación

- **Comentarios en español y solo para el POR QUÉ**: decisiones, cicatrices,
  restricciones que el código no puede expresar. Nunca narran lo que hace la
  línea siguiente.
- **Cabecera de fichero** en las clases importantes: bloque breve con propósito,
  flujo y decisiones de diseño.
- En clases largas (>150 líneas o >8 métodos), **banners de sección**:
  ```java
  // ---- FASE 2 — Ciclo de vida del lock ----------------------------------
  ```
- Javadoc en los métodos públicos; los privados, solo si el porqué no es obvio.

## Errores y logging

- **Degradar, no caerse**: un fallo transitorio (red, disco) se registra y se
  continúa con un default seguro, con un comentario que justifique el default.
- Nada de `printStackTrace()` ni `System.out/err` como logging definitivo en
  código nuevo: usar un punto de registro con etiqueta estable.
- Los mensajes de error para máquina (`"missing-repo"`) y para humano
  ("Sign into GitHub again…") son campos distintos.

## Cómo se aplicó

- El commit de reformateo masivo está en `.git-blame-ignore-revs`
  (`git config blame.ignoreRevsFile .git-blame-ignore-revs` para que `git blame`
  lo salte).
- `src/app/UpdateChecker.java` es el ejemplo canónico del estilo completo.
