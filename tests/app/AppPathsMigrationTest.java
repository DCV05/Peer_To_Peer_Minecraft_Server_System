package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Al cambiar el nombre del proyecto cambio la carpeta de datos. Quien ya usaba
 * la aplicacion tiene ahi su sesion de GitHub y la lista de sus servidores: si
 * la mudanza falla, al actualizar se encuentra la aplicacion vacia y sin saber
 * por que.
 *
 * <p>El resolver real lee {@code user.home}, asi que aqui se prueba la copia en
 * si, que es la parte que puede perder datos.</p>
 */
class AppPathsMigrationTest
{
	@TempDir
	Path temporary;

	@Test
	void theOldFolderIsCopiedOverAndNeverDeleted() throws Exception
	{
		Path old = Files.createDirectories( temporary.resolve( "old" ) );
		Files.writeString( old.resolve( "recentServers.properties" ), "server=/mundos/farmland\n" );
		Files.createDirectories( old.resolve( "maps" ) );
		Files.writeString( old.resolve( "maps" ).resolve( "enabled" ), "on\n" );
		Path fresh = Files.createDirectories( temporary.resolve( "new" ) );

		copyMissingFiles( old, fresh );

		assertEquals( "server=/mundos/farmland\n", Files.readString( fresh.resolve( "recentServers.properties" ) ) );
		assertTrue( Files.exists( fresh.resolve( "maps" ).resolve( "enabled" ) ), "Se pierden los ajustes por carpeta" );
		assertTrue( Files.exists( old.resolve( "recentServers.properties" ) ),
				"El original tiene que quedar intacto: si la mudanza sale mal, ahi sigue todo" );
	}

	@Test
	void whatAlreadyExistsInTheNewFolderWins() throws Exception
	{
		Path old = Files.createDirectories( temporary.resolve( "old" ) );
		Files.writeString( old.resolve( "token" ), "viejo" );
		Path fresh = Files.createDirectories( temporary.resolve( "new" ) );
		Files.writeString( fresh.resolve( "token" ), "nuevo" );

		copyMissingFiles( old, fresh );

		assertEquals( "nuevo", Files.readString( fresh.resolve( "token" ) ),
				"La migracion ha pisado datos nuevos con los viejos" );
	}

	@Test
	void theLegacySystemPropertyStillWorks()
	{
		System.setProperty( "p2pmss.dataDirectory", temporary.resolve( "legacy" ).toString() );
		try
		{
			assertEquals( temporary.resolve( "legacy" ), AppPaths.data(),
					"Scripts y accesos directos antiguos dejarian de funcionar" );
		}
		finally
		{
			System.clearProperty( "p2pmss.dataDirectory" );
		}
	}

	@Test
	void theNewSystemPropertyTakesPrecedence()
	{
		System.setProperty( "p2pmss.dataDirectory", temporary.resolve( "legacy" ).toString() );
		System.setProperty( "endershare.dataDirectory", temporary.resolve( "current" ).toString() );
		try
		{
			assertEquals( temporary.resolve( "current" ), AppPaths.data() );
		}
		finally
		{
			System.clearProperty( "p2pmss.dataDirectory" );
			System.clearProperty( "endershare.dataDirectory" );
		}
	}

	private static void copyMissingFiles( Path from, Path to ) throws Exception
	{
		java.lang.reflect.Method method = AppPaths.class.getDeclaredMethod( "copyMissingFiles", Path.class, Path.class );
		method.setAccessible( true );
		method.invoke( null, from, to );
	}
}
