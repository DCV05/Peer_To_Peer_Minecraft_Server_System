package link;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.WorldStatusScanner;
import jgit.HostLock;

/**
 * Buscador permanente del canal endershare-link (R4 del plan): mientras la app
 * este abierta y no haya conexion, prueba candidatos cada pocos segundos y se
 * conecta solo en cuanto alguien levante el mundo. Candidatos, por orden: el
 * server local si esta encendido, la ultima direccion que funciono, y las
 * direcciones que publican los hosts en el candado (tunnel de playit o IP).
 * El WebSocket vive en el MISMO puerto del juego, asi que toda direccion
 * jugable es tambien candidata del link.
 */
public final class LinkSeeker
{

	public interface Ui
	{
		void status( String line );

		void peers( String line );

		void players( String line );

		void chat( String line );
	}

	private static final long TICK_SECONDS = 10;
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Preferences PREFERENCES = Preferences.userRoot().node( "endershare/link" );

	private final Supplier<Map<String, WorldStatusScanner.WorldStatus>> worldStatuses;
	private final Supplier<String> localAddress;
	private final Supplier<File> serverFolder;
	private final Supplier<String> nickname;
	private final Ui ui;

	private final HttpClient probeClient = HttpClient.newBuilder().connectTimeout( Duration.ofSeconds( 2 ) ).build();
	private ScheduledExecutorService scheduler;
	private volatile LinkClient active = null;
	private volatile boolean seeking = false;

	public LinkSeeker( Supplier<Map<String, WorldStatusScanner.WorldStatus>> worldStatuses, Supplier<String> localAddress,
			Supplier<File> serverFolder, Supplier<String> nickname, Ui ui )
	{
		this.worldStatuses = worldStatuses;
		this.localAddress = localAddress;
		this.serverFolder = serverFolder;
		this.nickname = nickname;
		this.ui = ui;
	}

	public synchronized void start()
	{
		if( scheduler != null )
			return;
		scheduler = Executors.newSingleThreadScheduledExecutor( runnable ->
		{
			Thread thread = new Thread( runnable, "endershare-link-seeker" );
			thread.setDaemon( true );
			return thread;
		} );
		scheduler.scheduleWithFixedDelay( this::tick, 2, TICK_SECONDS, TimeUnit.SECONDS );
		ui.status( "BUSCANDO" );
	}

	public synchronized void stop()
	{
		if( scheduler != null )
			scheduler.shutdownNow();
		scheduler = null;
		disconnect();
	}

	public void sendChat( String text )
	{
		LinkClient client = active;
		if( client != null && text != null && !text.isBlank() )
			client.sendChat( text.trim() );
	}

	// ---- Bucle -------------------------------------------------------------

	private void tick()
	{
		if( active != null || seeking )
			return;
		seeking = true;
		try
		{
			for( String candidate : candidates() )
			{
				if( probe( candidate ) && connect( candidate ) )
					return;
			}
			ui.status( "BUSCANDO" );
		}
		catch( Exception ignored )
		{
			// el siguiente tick lo reintenta
		}
		finally
		{
			seeking = false;
		}
	}

	private Set<String> candidates()
	{
		Set<String> candidates = new LinkedHashSet<>();

		String local = localAddress.get();
		if( local != null && !local.isBlank() )
			candidates.add( local );

		String lastGood = PREFERENCES.get( "last_good_address", "" );
		if( !lastGood.isBlank() )
			candidates.add( lastGood );

		for( WorldStatusScanner.WorldStatus status : worldStatuses.get().values() )
		{
			HostLock.HostDetails details = status.details();
			if( status.hosted() && details != null && details.tunnelAddress() != null && !details.tunnelAddress().isBlank() )
				candidates.add( details.tunnelAddress() );
		}

		return candidates;
	}

	private boolean probe( String address )
	{
		try
		{
			HttpRequest request = HttpRequest.newBuilder( URI.create( "http://" + address + "/ping" ) )
					.timeout( Duration.ofSeconds( 2 ) ).GET().build();
			HttpResponse<String> response = probeClient.send( request, HttpResponse.BodyHandlers.ofString() );
			return response.statusCode() == 200 && response.body().contains( "endershare-link" );
		}
		catch( Exception unreachable )
		{
			return false;
		}
	}

	private boolean connect( String address )
	{
		try
		{
			LinkClient client = LinkClient.connect( address, safeNickname(), readToken(), new LinkClient.Listener()
			{
				@Override
				public void onConnected( boolean authed, String world )
				{
					PREFERENCES.put( "last_good_address", address );
					ui.status( "CONECTADO" + (authed ? "" : " (solo lectura)") + " · " + world + " · " + address );
				}

				@Override
				public void onChat( String from, String text )
				{
					ui.chat( "<" + from + "> " + text );
				}

				@Override
				public void onPresence( String peersLine )
				{
					ui.peers( peersLine );
				}

				@Override
				public void onPlayers( String playersLine )
				{
					ui.players( playersLine );
				}

				@Override
				public void onEvent( String description )
				{
					ui.chat( "-- " + description );
				}

				@Override
				public void onChunks( String dimension, java.util.List<int[]> chunkCoords )
				{
					// Solo cuenta lo que carga el server propio: es lo que se publica
					if( address.startsWith( "localhost" ) )
						MapPublisher.noteLoadedChunks( dimension, chunkCoords );
				}

				@Override
				public void onClosed()
				{
					active = null;
					ui.status( "BUSCANDO" );
					ui.peers( "—" );
					ui.players( "—" );
				}
			} );
			active = client;
			return true;
		}
		catch( Exception noWebSocket )
		{
			return false;
		}
	}

	private void disconnect()
	{
		LinkClient client = active;
		active = null;
		if( client != null )
			client.close();
	}

	private String safeNickname()
	{
		String nick = nickname.get();
		return nick == null || nick.isBlank() ? "anon" : nick;
	}

	/**
	 * El token viaja con el mundo en config/endershare-link.json: si el peer
	 * tiene la carpeta del server abierta lo lee de ahi; sin carpeta se entra
	 * en solo lectura.
	 */
	private String readToken()
	{
		try
		{
			File folder = serverFolder.get();
			if( folder == null )
				return "";
			Path config = folder.toPath().resolve( "config" ).resolve( "endershare-link.json" );
			if( !Files.exists( config ) )
				return "";
			return JSON.readTree( Files.readString( config, StandardCharsets.UTF_8 ) ).path( "token" ).asText( "" );
		}
		catch( Exception unreadable )
		{
			return "";
		}
	}

}
