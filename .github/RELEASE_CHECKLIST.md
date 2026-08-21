# Checklist de humo por release (5 minutos)

Lo que ningún test automático puede ver. Pasarla entre publicar la release y
avisar al equipo de que actualice.

## Automático (lo cubre el CI — solo confirmar que está en verde)

- [ ] Suite rápida en verde (push a `dev`/`main`)
- [ ] Job `e2e-minecraft` en verde (server real + jugador bot)
- [ ] Gate de tests de la release en verde y 3 instaladores adjuntos

## Manual — quien publica (1 máquina)

- [ ] La app instalada detecta la release nueva en ~1 min y muestra el diálogo
- [ ] UPDATE NOW: descarga, cierra con backup y el instalador arranca solo
- [ ] macOS: la .app nueva queda en Aplicaciones y se relanza sola (sin arrastrar)
- [ ] Windows: la versión nueva REEMPLAZA a la vieja y los accesos directos siguen
- [ ] La versión del sidebar es la nueva

## Manual — con el mundo real (2 personas o 2 máquinas)

- [ ] La tarjeta del mundo muestra el estado correcto (FREE / LIVE con aforo)
- [ ] Con un host activo: COPY IP copia la dirección del túnel y funciona en el juego
- [ ] PLAY abre el launcher con el perfil "P2PMSS · <mundo>" y la versión correcta
- [ ] SAVE & CLOSE: backup confirmado y el otro peer puede arrancar al momento

## Si algo falla

Settings → COPY REPORT y pegar el informe en el chat del equipo.
