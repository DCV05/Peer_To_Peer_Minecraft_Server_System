/*
 * Endershare: service worker del visor en GitHub Pages.
 *
 * 1. Si el host tiene su server encendido en esta misma maquina, los tiles se
 *    piden a localhost (frescos, en vivo); el navegador lo permite desde https.
 * 2. Si no, se pide al sitio principal; y si este responde 404, a los repos
 *    shard bajo el mismo origen (Pages corta en ~1 GB por sitio).
 */
var SHARDS = [ "/farmland-map-t1/", "/farmland-map-t2/" ];
var LOCAL = "http://localhost:25565/map/";
var localAlive = false;
var localCheckedAt = 0;

self.addEventListener( "install", function () { self.skipWaiting(); } );
self.addEventListener( "activate", function ( event ) { event.waitUntil( self.clients.claim() ); } );

async function probeLocal()
{
	if( Date.now() - localCheckedAt < 30000 )
		return localAlive;
	localCheckedAt = Date.now();
	try
	{
		var ping = await fetch( LOCAL.replace( "/map/", "/ping" ), { cache: "no-store" } );
		localAlive = ping.ok;
	}
	catch( down )
	{
		localAlive = false;
	}
	return localAlive;
}

self.addEventListener( "fetch", function ( event )
{
	var url = new URL( event.request.url );
	var site = url.pathname.indexOf( "/farmland-map/" );
	if( url.origin !== self.location.origin || site !== 0 || url.pathname.indexOf( "/maps/" ) < 0
			|| url.pathname.indexOf( "/tiles/" ) < 0 )
		return;
	var subpath = url.pathname.substring( "/farmland-map/".length );
	event.respondWith( ( async function ()
	{
		if( await probeLocal() )
		{
			try
			{
				var fresh = await fetch( LOCAL + subpath, { cache: "no-store" } );
				if( fresh.ok || fresh.status === 204 )
					return fresh;
			}
			catch( gone )
			{
				localAlive = false;
			}
		}
		var primary = await fetch( event.request );
		if( primary.ok || primary.status === 304 )
			return primary;
		if( url.pathname.indexOf( "/tiles/0/" ) < 0 )
			return primary;
		for( var i = 0; i < SHARDS.length; i++ )
		{
			var fallback = await fetch( SHARDS[i] + subpath );
			if( fallback.ok )
				return fallback;
		}
		return primary;
	} )() );
} );
