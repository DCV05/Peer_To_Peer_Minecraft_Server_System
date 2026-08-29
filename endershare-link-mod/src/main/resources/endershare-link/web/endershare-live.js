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
	var pending = false;

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

	connect();
})();
