package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * La mudanza de la carpeta de datos del nombre viejo al nuevo.
 *
 * <p>Esto sale de un caso real: la mudanza se atraganto con un fichero, dejo el
 * resto copiado, y como a partir de entonces la carpeta nueva ya existia no se
 * volvio a intentar. Los dos ficheros de la sesion de GitHub se quedaron atras y
 * la aplicacion pedia autenticarse otra vez sin decir por que.</p>
 */
class AppPathsMigrationTest
{
	@TempDir
	Path temporary;

	private String realHome;

	@BeforeEach
	void setUp() throws IOException
	{
		realHome = System.getProperty( "user.home" );
		System.setProperty( "user.home", temporary.toString() );
		System.clearProperty( "endershare.dataDirectory" );
		System.clearProperty( "p2pmss.dataDirectory" );
		forgetWhatWasResolvedBefore();
	}

	@AfterEach
	void tearDown() throws IOException
	{
		System.setProperty( "user.home", realHome );
		forgetWhatWasResolvedBefore();
	}

	@Test
	void whatWasInTheOldFolderEndsUpInTheNewOne() throws Exception
	{
		givenOldData( Set.of( "credentials.dat", "userData.properties", "recentServers.properties" ) );

		Path data = AppPaths.data();

		assertTrue( Files.exists( data.resolve( "credentials.dat" ) ), "La sesion se ha quedado atras" );
		assertTrue( Files.exists( data.resolve( "userData.properties" ) ), "El perfil se ha quedado atras" );
		assertTrue( Files.exists( data.resolve( "recentServers.properties" ) ) );
		assertEquals( "contenido de credentials.dat",
			Files.readString( data.resolve( "credentials.dat" ), StandardCharsets.UTF_8 ) );
	}

	@Test
	void aHalfDoneMoveIsFinishedOnTheNextStart() throws Exception
	{
		// El caso real: alguien ya tiene la carpeta nueva con casi todo, pero los dos
		// ficheros de la sesion se quedaron en la vieja
		givenOldData( Set.of( "credentials.dat", "userData.properties", "recentServers.properties" ) );
		Path newFolder = Files.createDirectories( temporary.resolve( ".endershare" ).resolve( "data" ) );
		Files.writeString( newFolder.resolve( "recentServers.properties" ), "lo que ya estaba" );

		Path data = AppPaths.data();

		assertTrue( Files.exists( data.resolve( "credentials.dat" ) ),
			"Que la carpeta nueva exista no puede significar que la mudanza esta hecha" );
		assertTrue( Files.exists( data.resolve( "userData.properties" ) ) );
	}

	@Test
	void whatIsAlreadyInTheNewFolderIsNeverOverwritten() throws Exception
	{
		givenOldData( Set.of( "recentServers.properties" ) );
		Path newFolder = Files.createDirectories( temporary.resolve( ".endershare" ).resolve( "data" ) );
		Files.writeString( newFolder.resolve( "recentServers.properties" ), "lo nuevo, que manda" );

		Path data = AppPaths.data();

		assertEquals( "lo nuevo, que manda",
			Files.readString( data.resolve( "recentServers.properties" ), StandardCharsets.UTF_8 ) );
	}

	@Test
	void theOldFolderIsLeftUntouched() throws Exception
	{
		Path old = givenOldData( Set.of( "credentials.dat" ) );

		AppPaths.data();

		assertTrue( Files.exists( old.resolve( "credentials.dat" ) ),
			"Si la mudanza sale mal a medias, el original es lo unico que queda" );
	}

	@Test
	void aClosedSessionIsNotBroughtBackFromTheGrave() throws Exception
	{
		givenOldData( Set.of( "credentials.dat", "userData.properties" ) );
		Path data = AppPaths.data();
		assertTrue( Files.exists( data.resolve( "credentials.dat" ) ) );

		// Cerrar la sesion borra esos dos ficheros de la carpeta nueva
		Files.delete( data.resolve( "credentials.dat" ) );
		Files.delete( data.resolve( "userData.properties" ) );
		forgetWhatWasResolvedBefore();

		Path again = AppPaths.data();

		assertFalse( Files.exists( again.resolve( "credentials.dat" ) ),
			"Volver a copiarla de la carpeta vieja deja al usuario sin forma de salir" );
	}

	@Test
	void whenThereIsNothingToMoveTheMoveIsStillConsideredDone() throws Exception
	{
		Path data = AppPaths.data();

		assertTrue( AppPaths.migrationIsDone( data ), "Sin marca se recorreria la carpeta vieja en cada arranque" );
	}

	/** Deja escritos esos ficheros en la carpeta del nombre anterior. */
	private Path givenOldData( Set<String> names ) throws IOException
	{
		Path old = Files.createDirectories( temporary.resolve( ".p2pmss" ).resolve( "data" ) );
		for( String name : names )
			Files.writeString( old.resolve( name ), "contenido de " + name, StandardCharsets.UTF_8 );
		return old;
	}

	/**
	 * AppPaths se queda con la carpeta que resolvio la primera vez. Los tests
	 * necesitan varias resoluciones en el mismo proceso.
	 */
	private void forgetWhatWasResolvedBefore() throws IOException
	{
		try
		{
			java.lang.reflect.Field cached = AppPaths.class.getDeclaredField( "resolvedDataDirectory" );
			cached.setAccessible( true );
			cached.set( null, null );
		}
		catch( ReflectiveOperationException unexpected )
		{
			throw new IOException( "No se pudo reiniciar AppPaths", unexpected );
		}
	}
}
