package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * El servidor que sirve el mapa desde dentro de la aplicacion.
 *
 * <p>Sustituye a una maquina virtual de Java entera con el renderizador dentro,
 * que para servir ficheros que ya estaban en disco gastaba 65 MB y un proceso.
 * Tiene que servir <b>exactamente lo mismo</b>: los caminos y las formas que se
 * comprueban aqui salen de capturar el trafico real del visor.</p>
 */
class MapWebServerTest
{
	@TempDir
	Path temporary;

	private Path webroot;
	private MapWebServer server;
	private String base;

	@BeforeEach
	void setUp() throws Exception
	{
		webroot = Files.createDirectories( temporary.resolve( "web" ) );
		Files.writeString( webroot.resolve( "index.html" ), "<html>el visor</html>" );
		Files.writeString( webroot.resolve( "settings.json" ), "{\"maps\":[\"overworld\"]}" );

		Path tiles = Files.createDirectories( webroot.resolve( "maps/overworld/tiles/0/x-3" ) );
		Files.write( tiles.resolve( "z3.json.gz" ), gzipped( "{\"tileGeometry\":{}}" ) );
		Files.createDirectories( webroot.resolve( "maps/overworld/tiles/1/x0" ) );
		Files.write( webroot.resolve( "maps/overworld/tiles/1/x0/z0.png" ), new byte[] { 1, 2, 3, 4 } );

		Path live = Files.createDirectories( webroot.resolve( "maps/overworld/live" ) );
		Files.writeString( live.resolve( "players.json" ), "{\"players\":[]}" );

		Files.writeString( temporary.resolve( "secreto.txt" ), "esto no se sirve jamas" );

		server = new MapWebServer( webroot );
		base = server.start();
	}

	@AfterEach
	void tearDown()
	{
		if( server != null )
			server.stop();
	}

	@Test
	void itListensOnlyOnThisMachine()
	{
		assertNotNull( base );
		assertTrue( base.startsWith( "http://127.0.0.1:" ),
			"El mapa no puede quedar abierto al exterior sin que nadie lo haya pedido" );
	}

	@Test
	void theRootIsTheViewer() throws Exception
	{
		Response response = get( "/" );

		assertEquals( 200, response.status );
		assertEquals( "<html>el visor</html>", new String( response.body, StandardCharsets.UTF_8 ) );
		assertTrue( response.type.startsWith( "text/html" ) );
	}

	@Test
	void compressedTilesTravelCompressed() throws Exception
	{
		// Descomprimirlos para volver a comprimirlos es trabajo por nada: se mandan
		// tal cual estan en disco
		Response response = get( "/maps/overworld/tiles/0/x-3/z3.json.gz" );

		assertEquals( 200, response.status );
		assertEquals( "gzip", response.encoding );
		assertEquals( "application/json", response.type );
	}

	@Test
	void theSuffixTheViewerAddsIsIgnored() throws Exception
	{
		// El visor pide cada tile como ".../z0.png?512245": ese numero cambia cuando
		// se redibuja el mapa. Si se tomara como parte del nombre, no habria mapa
		Response response = get( "/maps/overworld/tiles/1/x0/z0.png?512245" );

		assertEquals( 200, response.status );
		assertEquals( "image/png", response.type );
		assertEquals( 4, response.body.length );
	}

	@Test
	void askingAgainForSomethingUnchangedCostsAHeader() throws Exception
	{
		Response first = get( "/maps/overworld/tiles/1/x0/z0.png" );
		assertNotNull( first.etag );

		Response again = get( "/maps/overworld/tiles/1/x0/z0.png", first.etag );

		// El servidor de BlueMap mandaba la marca pero contestaba 200 con el fichero
		// entero: comprobado. Aqui una revalidacion no vuelve a mandar los bytes
		assertEquals( 304, again.status );
		assertEquals( 0, again.body.length );
	}

	@Test
	void livePlayersAreNeverCached() throws Exception
	{
		Response response = get( "/maps/overworld/live/players.json" );

		assertEquals( 200, response.status );
		assertTrue( response.cacheControl.contains( "no-store" ),
			"Cachear las posiciones deja los muñecos congelados en el mapa" );
	}

	@Test
	void livePlayersAreServedEvenWhenTheBrowserThinksItHasThem() throws Exception
	{
		Response first = get( "/maps/overworld/live/players.json" );

		Response again = get( "/maps/overworld/live/players.json", first.etag );

		assertEquals( 200, again.status, "Un 304 aqui congelaria a los jugadores" );
	}

	@Test
	void tilesTellTheBrowserItCanKeepThem() throws Exception
	{
		Response response = get( "/maps/overworld/tiles/1/x0/z0.png" );

		assertTrue( response.cacheControl.contains( "max-age" ) );
	}

	@Test
	void nothingOutsideTheMapFolderIsEverServed() throws Exception
	{
		// Sin esto, cualquiera que llegue a ese puerto se lleva ficheros del equipo
		assertNull( server.resolve( "/../secreto.txt" ) );
		assertNull( server.resolve( "/maps/../../secreto.txt" ) );
		assertNull( server.resolve( "/%2e%2e/secreto.txt" ) );

		assertEquals( 404, get( "/../secreto.txt" ).status );
	}

	@Test
	void whatDoesNotExistIsNotFound() throws Exception
	{
		assertEquals( 404, get( "/maps/overworld/tiles/0/x99/z99.json.gz" ).status );
	}

	@Test
	void theMarkChangesWhenTheFileChanges()
	{
		String before = MapWebServer.etagFor( 100, 1000 );

		assertFalse( before.equals( MapWebServer.etagFor( 101, 1000 ) ), "Otro tamaño es otro fichero" );
		assertFalse( before.equals( MapWebServer.etagFor( 100, 2000 ) ), "Otra fecha es otro fichero" );
		assertEquals( before, MapWebServer.etagFor( 100, 1000 ) );
	}

	@Test
	void everythingTheViewerLoadsHasItsType()
	{
		// Capturado del visor de verdad: si el tipo va mal, la pagina no arranca
		assertEquals( "text/javascript; charset=utf-8", MapWebServer.typeOf( "index-faa68fa5.js" ) );
		assertEquals( "text/css; charset=utf-8", MapWebServer.typeOf( "index-375a3a50.css" ) );
		assertEquals( "font/ttf", MapWebServer.typeOf( "Quicksand-06927fae.ttf" ) );
		assertEquals( "image/png", MapWebServer.typeOf( "logoCircle512.png" ) );
		assertEquals( "application/json", MapWebServer.typeOf( "settings.json" ) );
		assertEquals( "text/plain; charset=utf-8", MapWebServer.typeOf( "en.conf" ) );
	}

	@Test
	void itTakesTheExactPortItIsGiven() throws Exception
	{
		// Al relevar al renderizador se hereda SU puerto: si cambiara la direccion,
		// la pestaña que el usuario tiene abierta se quedaria en blanco
		int wanted = freePort();
		MapWebServer heir = new MapWebServer( webroot );
		try
		{
			assertEquals( "http://127.0.0.1:" + wanted + "/", heir.start( wanted ) );
		}
		finally
		{
			heir.stop();
		}
	}

	@Test
	void itWaitsForAPortThatIsStillBeingReleased() throws Exception
	{
		// El puerto lo acaba de soltar el renderizador al morir, y el sistema tarda
		// un instante en darlo por libre. Sin reintento, el relevo falla y el mapa
		// se queda sin servir justo cuando acaba de dibujarse
		int wanted = freePort();
		ServerSocket occupied = new ServerSocket();
		occupied.setReuseAddress( false );
		occupied.bind( new InetSocketAddress( InetAddress.getLoopbackAddress(), wanted ) );

		Thread release = new Thread( () ->
		{
			try
			{
				Thread.sleep( 400 );
				occupied.close();
			}
			catch( Exception ignored )
			{
				// El test falla solo si el servidor no consigue el puerto
			}
		} );
		release.start();

		MapWebServer heir = new MapWebServer( webroot );
		try
		{
			assertEquals( "http://127.0.0.1:" + wanted + "/", heir.start( wanted ) );
		}
		finally
		{
			heir.stop();
			release.join();
			occupied.close();
		}
	}

	@Test
	void itServesMoreAtOnceThanItHasProcessors() throws Exception
	{
		// Con hilos fijos, mas peticiones a la vez que hilos hacian cola. El visor
		// pide decenas de tiles de golpe al moverse por el mapa
		int atOnce = 32;
		var pool = java.util.concurrent.Executors.newFixedThreadPool( atOnce );
		try
		{
			var pending = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
			for( int each = 0; each < atOnce; each++ )
				pending.add( pool.submit( () -> get( "/maps/overworld/tiles/1/x0/z0.png" ).status ) );
			for( var one : pending )
				assertEquals( 200, one.get( 30, java.util.concurrent.TimeUnit.SECONDS ) );
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	private static int freePort() throws Exception
	{
		try (ServerSocket probe = new ServerSocket( 0 ))
		{
			return probe.getLocalPort();
		}
	}

	@Test
	void stoppingReleasesThePort()
	{
		server.stop();

		assertFalse( server.isRunning() );
		assertNull( server.address() );
	}

	// ---- utilidades ---------------------------------------------------------

	private record Response( int status, byte[] body, String type, String encoding, String etag, String cacheControl )
	{
	}

	private Response get( String path ) throws Exception
	{
		return get( path, null );
	}

	private Response get( String path, String ifNoneMatch ) throws Exception
	{
		HttpURLConnection connection = (HttpURLConnection) URI.create( base.substring( 0, base.length() - 1 ) + path )
				.toURL().openConnection();
		connection.setInstanceFollowRedirects( false );
		if( ifNoneMatch != null )
			connection.setRequestProperty( "If-None-Match", ifNoneMatch );
		int status = connection.getResponseCode();
		byte[] body = new byte[0];
		try (InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream())
		{
			if( in != null )
				body = in.readAllBytes();
		}
		catch( java.io.IOException empty )
		{
			// 304 y 404 no traen cuerpo
		}
		return new Response( status, body, connection.getHeaderField( "Content-Type" ),
				connection.getHeaderField( "Content-Encoding" ), connection.getHeaderField( "ETag" ),
				String.valueOf( connection.getHeaderField( "Cache-Control" ) ) );
	}

	private static byte[] gzipped( String content ) throws Exception
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream out = new GZIPOutputStream( bytes ))
		{
			out.write( content.getBytes( StandardCharsets.UTF_8 ) );
		}
		return bytes.toByteArray();
	}
}
