//
// Endershare — posiciones de los jugadores para el mapa 3D.
//
// Escribe una foto del momento (no un historial) en
//   world/scripts/endershare_players.data/players.json
// una vez por segundo. Endershare la lee y la pinta en el mapa.
//
// Es lo mas liviano que se puede hacer: Carpet ya esta instalado, asi que no
// hace falta ningun mod nuevo, ni puertos, ni contraseñas. De los 20 ticks de
// cada segundo, 19 solo comprueban un resto y no hacen nada.
//
// Se enciende y se apaga a mano:
//   /script load endershare_players
//   /script unload endershare_players
//
__config() -> {
   'stay_loaded' -> true,
   'scope' -> 'global'
};

__on_tick() -> (
   if( tick_time() % 20 == 0,
      write_file('players', 'json',
         map( player('all'), {
            'uuid' -> str(query(_, 'uuid')),
            'name' -> str(query(_, 'name')),
            'x' -> query(_, 'x'),
            'y' -> query(_, 'y'),
            'z' -> query(_, 'z'),
            'yaw' -> query(_, 'yaw'),
            'pitch' -> query(_, 'pitch'),
            'health' -> query(_, 'health'),
            'dimension' -> str(query(_, 'dimension'))
         })
      )
   )
);
