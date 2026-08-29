/*
 * Endershare: el visor 3D de BlueMap se actualiza solo.
 *
 * El mod avisa por WebSocket cuando BlueMap re-renderiza tiles (mensaje
 * "maptile"); aqui se agrupan los avisos y se llama a bluemap.updateMap(),
 * que limpia la cache de tiles y recarga el mapa actual manteniendo la
 * camara. Si el visor cambiara por dentro en una version futura, esto
 * simplemente no hace nada y el mapa sigue funcionando como estatico.
 */
(function ()
{
	"use strict";

	var UPDATE_DEBOUNCE_MS = 5000;
	var RECONNECT_MS = 5000;
	var HOOK_RETRY_MS = 500;
	var WS_PLAYERS_FRESH_MS = 5000;
	var pending = false;
	var playerSet = null;
	var wsPlayers = null;
	var wsPlayersAt = 0;
	var applying = false;

	function connect()
	{
		// En https (GitHub Pages) el unico ws:// permitido es localhost: le vale
		// al host que mira su propio server; los demas ven el mapa estatico
		var url = location.protocol === "https:"
				? "ws://localhost:25565/ws"
				: "ws://" + location.host + "/ws";
		var socket;
		try
		{
			socket = new WebSocket( url );
		}
		catch( failed )
		{
			window.setTimeout( connect, RECONNECT_MS );
			return;
		}
		socket.onopen = function ()
		{
			socket.send( JSON.stringify( { type: "hello", from: "visor3d", ts: Date.now(), payload: { client: "map3d" } } ) );
		};
		socket.onmessage = function ( raw )
		{
			var message;
			try
			{
				message = JSON.parse( raw.data );
			}
			catch( bad )
			{
				return;
			}
			if( message.type === "maptile" )
				scheduleUpdate();
			else if( message.type === "players" )
				applyPlayers( message.payload.players || [] );
		};
		socket.onclose = function ()
		{
			window.setTimeout( connect, RECONNECT_MS );
		};
	}

	function scheduleUpdate()
	{
		if( pending )
			return;
		pending = true;
		window.setTimeout( function ()
		{
			pending = false;
			try
			{
				if( window.bluemap && typeof window.bluemap.updateMap === "function" )
					window.bluemap.updateMap();
			}
			catch( failed )
			{
				console.warn( "[endershare-live]", failed );
			}
		}, UPDATE_DEBOUNCE_MS );
	}

	/**
	 * Jugadores en vivo desde el WebSocket, pintados con el pipeline propio del
	 * visor: se llama al mismo updateFromPlayerData que usa su poll de
	 * players.json. En GitHub Pages ese fichero es estatico y vacio, asi que el
	 * wrapper ademas sustituye los datos del poll por los del WS mientras esten
	 * frescos, para que el poll no borre los marcadores.
	 */
	function hookPlayers()
	{
		var viewer = window.bluemap;
		var manager = viewer && viewer.playerMarkerManager;
		var set = manager && manager.getPlayerMarkerSet && manager.getPlayerMarkerSet();
		if( !set )
		{
			window.setTimeout( hookPlayers, HOOK_RETRY_MS );
			return;
		}
		playerSet = set;
		var prototype = Object.getPrototypeOf( set );
		if( !prototype.__endershareLivePlayers && typeof prototype.updateFromPlayerData === "function" )
		{
			var original = prototype.updateFromPlayerData;
			prototype.updateFromPlayerData = function ( data )
			{
				if( !applying && wsPlayers && Date.now() - wsPlayersAt < WS_PLAYERS_FRESH_MS )
					data = wsPlayers;
				return original.call( this, data );
			};
			prototype.__endershareLivePlayers = true;
		}
	}

	function applyPlayers( players )
	{
		var mapId = null;
		try
		{
			mapId = window.bluemap.mapViewer.map.data.id;
		}
		catch( sinMapa )
		{
		}
		wsPlayers = {
			players: players.map( function ( player )
			{
				return {
					uuid: player.uuid,
					name: player.nick,
					foreign: mapId ? String( player.dim ).indexOf( mapId ) < 0 : false,
					position: { x: player.x, y: player.y, z: player.z },
					rotation: { pitch: 0, yaw: player.yaw, roll: 0 }
				};
			} )
		};
		wsPlayersAt = Date.now();
		if( playerSet )
		{
			applying = true;
			try
			{
				playerSet.updateFromPlayerData( wsPlayers );
			}
			catch( failed )
			{
				console.warn( "[endershare-live]", failed );
			}
			finally
			{
				applying = false;
			}
		}
	}

	connect();
	hookPlayers();
})();
