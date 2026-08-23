package app;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * La cara de cada jugador para el mapa.
 *
 * <p>Quien las descarga normalmente es el mod dentro del servidor; como aqui el
 * renderizador va por fuera, se bajan de Mojang. De cada jugador se guardan dos
 * cosas: la cara recortada, para la chincheta del visor, y la skin entera, que
 * es lo que necesita el muñeco 3D.</p>
 *
 * <p>Se descarga <b>una sola vez por jugador</b> y se guarda en la carpeta de la
 * aplicacion, asi que sirve para todos los mapas y sobrevive a que se rehaga el
 * mapa entero. Mientras llega —y si no hay internet, o la cuenta no tiene skin—
 * se pone la cara generica y el jugador sale como chincheta plana.</p>
 *
 * <p>La descarga <b>nunca</b> ocurre en el hilo que publica las posiciones: ese
 * va una vez por segundo y un servidor de Mojang lento lo dejaria clavado.</p>
 */
public final class PlayerSkins
{
	private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
	/** Si Mojang tarda mas que esto, no merece la pena esperar: ya hay cara generica. */
	private static final Duration TIMEOUT = Duration.ofSeconds( 8 );
	/** Los tests ponen esta propiedad para no salir a internet. */
	private static final String OFFLINE_PROPERTY = "endershare.skinDownloads";
	private static final ObjectMapper JSON = new ObjectMapper();

	/** La cara ocupa este cuadro dentro de la skin; el sombrero va superpuesto. */
	private static final int FACE_X = 8, FACE_Y = 8, FACE_SIZE = 8;
	private static final int HAT_X = 40, HAT_Y = 8;
	/** Tamaño final: la cara son 8x8 pixeles, ampliados sin difuminar. */
	private static final int OUTPUT_SIZE = 64;

	/** Identificadores ya intentados, para no llamar a Mojang en cada vuelta. */
	private static final Set<String> alreadyTried = ConcurrentHashMap.newKeySet();
	private static volatile ExecutorService downloads;

	private PlayerSkins()
	{
	}

	static void forgetForTests()
	{
		alreadyTried.clear();
	}

	/** Donde se guarda la cara ya recortada, fuera de los mapas. */
	static Path cacheFileFor( String uuid )
	{
		return AppPaths.data().resolve( "skins" ).resolve( uuid.replace( "-", "" ) + ".png" );
	}

	/** La skin entera, que es lo que necesita el muñeco 3D del visor. */
	static Path bodyCacheFileFor( String uuid )
	{
		return AppPaths.data().resolve( "skins" ).resolve( uuid.replace( "-", "" ) + "-body.png" );
	}

	static Path faceFileIn( Path mapDirectory, String mapName, String uuid )
	{
		return assetFileIn( mapDirectory, mapName, "playerheads", uuid );
	}

	/** El guion del muñeco 3D busca la skin entera aqui. */
	static Path bodyFileIn( Path mapDirectory, String mapName, String uuid )
	{
		return assetFileIn( mapDirectory, mapName, "playerskins", uuid );
	}

	private static Path assetFileIn( Path mapDirectory, String mapName, String folder, String uuid )
	{
		return mapDirectory.resolve( "web" ).resolve( "maps" ).resolve( mapName ).resolve( "assets" )
				.resolve( folder ).resolve( uuid + ".png" );
	}

	/**
	 * Deja la cara de un jugador donde el visor la busca.
	 *
	 * @return true si a partir de ahora hay una cara, sea la real o la generica
	 */
	public static boolean ensureFace( Path mapDirectory, String mapName, String uuid, Path genericFace )
	{
		Path target = faceFileIn( mapDirectory, mapName, uuid );
		if( Files.isRegularFile( target ) )
			return true;

		try
		{
			Files.createDirectories( target.getParent() );
		}
		catch( IOException noFolder )
		{
			Log.event( "SKINS", "No se pudo crear " + target.getParent(), noFolder );
			return false;
		}

		// Si ya se bajo alguna vez —en otro mapa, o antes de rehacer este— no se
		// vuelve a molestar a Mojang: basta con copiarla
		Path cached = cacheFileFor( uuid );
		if( Files.isRegularFile( cached ) )
		{
			// La skin entera puede faltar aunque la cara este: los mapas hechos antes
			// de que existiera el muñeco 3D solo tienen la cara
			if( !copyOnto( bodyCacheFileFor( uuid ), bodyFileIn( mapDirectory, mapName, uuid ) ) )
				queueDownload( mapDirectory, uuid );
			return copyOnto( cached, target );
		}

		queueDownload( mapDirectory, uuid );
		return copyOnto( genericFace, target );
	}

	/**
	 * Baja de antemano las caras de quienes ya han entrado alguna vez, para que
	 * cuando aparezcan en el mapa sea ya con su cara y no con la generica.
	 */
	public static void prefetch( Path mapDirectory, Collection<String> uuids )
	{
		for( String uuid : uuids )
		{
			if( !Files.isRegularFile( cacheFileFor( uuid ) ) || !Files.isRegularFile( bodyCacheFileFor( uuid ) ) )
				queueDownload( mapDirectory, uuid );
		}
	}

	/** Identificadores del fichero que el servidor lleva de quien ha entrado. */
	public static java.util.List<String> knownPlayersIn( Path serverDirectory )
	{
		java.util.List<String> uuids = new java.util.ArrayList<>();
		Path cache = serverDirectory.resolve( "usercache.json" );
		if( !Files.isRegularFile( cache ) )
			return uuids;
		try
		{
			for( JsonNode entry : JSON.readTree( Files.readString( cache, StandardCharsets.UTF_8 ) ) )
			{
				String uuid = entry.path( "uuid" ).asText( "" );
				if( !uuid.isBlank() )
					uuids.add( uuid );
			}
		}
		catch( IOException | RuntimeException unreadable )
		{
			// Sin esa lista simplemente no se adelanta trabajo
		}
		return uuids;
	}

	private static void queueDownload( Path mapDirectory, String uuid )
	{
		if( !downloadsAllowed() || !alreadyTried.add( uuid ) )
			return;
		executor().execute( () -> downloadAndPublish( mapDirectory, uuid ) );
	}

	/**
	 * Baja la skin una vez y deja las dos cosas que hacen falta: la cara para la
	 * chincheta del visor y la skin entera para el muñeco 3D.
	 */
	private static void downloadAndPublish( Path mapDirectory, String uuid )
	{
		Optional<BufferedImage> skin = downloadSkin( uuid );
		if( skin.isEmpty() )
			return;
		Path cachedFace = cacheFileFor( uuid );
		Path cachedBody = bodyCacheFileFor( uuid );
		if( !save( cropFace( skin.get() ), cachedFace ) || !save( skin.get(), cachedBody ) )
			return;

		Path maps = mapDirectory.resolve( "web" ).resolve( "maps" );
		if( !Files.isDirectory( maps ) )
			return;
		try (java.util.stream.Stream<Path> children = Files.list( maps ))
		{
			for( Path map : children.toList() )
			{
				if( !Files.isDirectory( map ) )
					continue;
				String mapName = map.getFileName().toString();
				copyOnto( cachedFace, faceFileIn( mapDirectory, mapName, uuid ) );
				copyOnto( cachedBody, bodyFileIn( mapDirectory, mapName, uuid ) );
			}
		}
		catch( IOException unreadable )
		{
			Log.event( "SKINS", "No se pudieron repartir las caras en " + maps, unreadable );
		}
	}

	private static boolean save( BufferedImage image, Path target )
	{
		try
		{
			Files.createDirectories( target.getParent() );
			Path temporary = target.resolveSibling( target.getFileName() + ".tmp" );
			ImageIO.write( image, "png", temporary.toFile() );
			Files.move( temporary, target, StandardCopyOption.REPLACE_EXISTING );
			return true;
		}
		catch( IOException notWritten )
		{
			Log.event( "SKINS", "No se pudo guardar " + target, notWritten );
			return false;
		}
	}

	private static boolean copyOnto( Path source, Path target )
	{
		try
		{
			if( source == null || !Files.isRegularFile( source ) )
				return false;
			Files.createDirectories( target.getParent() );
			Files.copy( source, target, StandardCopyOption.REPLACE_EXISTING );
			return true;
		}
		catch( IOException notCopied )
		{
			Log.event( "SKINS", "No se pudo poner la cara en " + target, notCopied );
			return false;
		}
	}

	private static boolean downloadsAllowed()
	{
		return !"off".equals( System.getProperty( OFFLINE_PROPERTY ) );
	}

	private static ExecutorService executor()
	{
		ExecutorService running = downloads;
		if( running != null )
			return running;
		synchronized( PlayerSkins.class )
		{
			if( downloads == null )
			{
				downloads = Executors.newSingleThreadExecutor( runnable ->
				{
					Thread thread = new Thread( runnable, "endershare-skins" );
					thread.setDaemon( true );
					thread.setPriority( Thread.MIN_PRIORITY );
					return thread;
				} );
			}
			return downloads;
		}
	}

	/** Baja la skin de Mojang, entera. Vacio si no se puede. */
	static Optional<BufferedImage> downloadSkin( String uuid )
	{
		try
		{
			Optional<String> skinUrl = skinUrlFor( uuid );
			if( skinUrl.isEmpty() )
				return Optional.empty();
			try (HttpClient client = HttpClient.newBuilder().connectTimeout( TIMEOUT ).build())
			{
				HttpResponse<byte[]> response = client.send(
						HttpRequest.newBuilder( URI.create( skinUrl.get() ) ).timeout( TIMEOUT ).GET().build(),
						HttpResponse.BodyHandlers.ofByteArray() );
				if( response.statusCode() != 200 )
					return Optional.empty();
				return Optional.ofNullable( ImageIO.read( new ByteArrayInputStream( response.body() ) ) );
			}
		}
		catch( IOException | RuntimeException unavailable )
		{
			// Sin internet o Mojang caido: se queda la cara generica
			return Optional.empty();
		}
		catch( InterruptedException interrupted )
		{
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	/** Saca la direccion de la skin del perfil publico de Mojang. */
	static Optional<String> skinUrlFor( String uuid ) throws IOException, InterruptedException
	{
		String plain = uuid.replace( "-", "" );
		try (HttpClient client = HttpClient.newBuilder().connectTimeout( TIMEOUT ).build())
		{
			HttpResponse<String> response = client.send(
					HttpRequest.newBuilder( URI.create( PROFILE_URL + plain ) ).timeout( TIMEOUT ).GET().build(),
					HttpResponse.BodyHandlers.ofString() );
			if( response.statusCode() != 200 )
				return Optional.empty();
			return skinUrlFromProfile( response.body() );
		}
	}

	/** El perfil trae las texturas en base64 dentro de una lista de propiedades. */
	static Optional<String> skinUrlFromProfile( String profileJson )
	{
		try
		{
			JsonNode profile = JSON.readTree( profileJson );
			for( JsonNode property : profile.path( "properties" ) )
			{
				if( !"textures".equals( property.path( "name" ).asText( "" ) ) )
					continue;
				String decoded = new String( Base64.getDecoder().decode( property.path( "value" ).asText( "" ) ),
						StandardCharsets.UTF_8 );
				String url = JSON.readTree( decoded ).path( "textures" ).path( "SKIN" ).path( "url" ).asText( "" );
				return url.isBlank() ? Optional.empty() : Optional.of( url );
			}
		}
		catch( IOException | RuntimeException unreadable )
		{
			// Perfil con otra forma o cuenta sin skin: cara generica
		}
		return Optional.empty();
	}

	/**
	 * Recorta la cara y le superpone el sombrero, ampliando sin difuminar para
	 * que se vea como en el juego y no como una mancha.
	 */
	static BufferedImage cropFace( BufferedImage skin )
	{
		BufferedImage face = new BufferedImage( OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB );
		int scale = OUTPUT_SIZE / FACE_SIZE;
		boolean hasHatLayer = skin.getWidth() >= HAT_X + FACE_SIZE && skin.getHeight() >= HAT_Y + FACE_SIZE;
		for( int x = 0; x < FACE_SIZE; x++ )
		{
			for( int y = 0; y < FACE_SIZE; y++ )
			{
				int colour = skin.getRGB( FACE_X + x, FACE_Y + y );
				if( hasHatLayer )
				{
					int hat = skin.getRGB( HAT_X + x, HAT_Y + y );
					// El sombrero solo cuenta donde no es transparente
					if( (hat >>> 24) > 0 )
						colour = hat;
				}
				for( int px = 0; px < scale; px++ )
					for( int py = 0; py < scale; py++ )
						face.setRGB( x * scale + px, y * scale + py, colour );
			}
		}
		return face;
	}
}
