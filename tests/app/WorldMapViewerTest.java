package app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * El guion que le pone al visor el muñeco 3D de los jugadores.
 *
 * <p>Va pegado detras de la libreria 3D en un solo fichero a proposito: en dos,
 * el navegador no garantiza el orden y la mitad de las veces arrancaria sin
 * libreria. Eso es lo que fija el primer test.</p>
 */
class WorldMapViewerTest
{
	@TempDir
	Path temporary;

	@Test
	void theLibraryTravelsAheadOfTheScriptInASingleFile() throws Exception
	{
		assertTrue( WorldMapViewer.install( temporary ), "No se ha instalado" );

		String installed = Files.readString( WorldMapViewer.scriptFileIn( temporary ), StandardCharsets.UTF_8 );
		int library = installed.indexOf( "Three.js Authors" );
		int ours = installed.indexOf( "playerMarkerManager" );
		assertTrue( library > 0, "No viaja la libreria 3D" );
		assertTrue( ours > 0, "No viaja nuestro guion" );
		assertTrue( library < ours, "La libreria tiene que ir delante o el guion arranca sin ella" );
	}

	@Test
	void theVersionOfTheViewerMatchesTheOneOfTheLibrary() throws Exception
	{
		WorldMapViewer.install( temporary );

		String installed = Files.readString( WorldMapViewer.scriptFileIn( temporary ), StandardCharsets.UTF_8 );
		// Los objetos 3D de dos versiones distintas no se mezclan bien; el visor
		// que descargamos usa exactamente esta
		assertTrue( installed.contains( "\"147\"" ), "La libreria no es la misma version que usa el visor" );
	}

	@Test
	void itIsNotRewrittenOnEveryBuild() throws Exception
	{
		WorldMapViewer.install( temporary );

		assertFalse( WorldMapViewer.install( temporary ), "Se reescriben 600 KB cada vez que se toca el mapa" );
	}

	@Test
	void anOlderVersionIsReplaced() throws Exception
	{
		Path script = WorldMapViewer.scriptFileIn( temporary );
		Files.createDirectories( script.getParent() );
		Files.writeString( script, "// endershare-players3d v0\nGUION-VIEJO-DE-LA-VERSION-ANTERIOR" );

		assertTrue( WorldMapViewer.install( temporary ), "Se quedaria el guion viejo para siempre" );
		assertFalse( Files.readString( script ).contains( "GUION-VIEJO-DE-LA-VERSION-ANTERIOR" ) );
	}

	@Test
	void theConfigurationPointsAtWhereTheScriptIsLeft() throws Exception
	{
		Path world = Files.createDirectories( temporary.resolve( "world" ) );
		Files.createDirectories( world.resolve( "region" ) );
		Path map = temporary.resolve( "mapa" );

		WorldMapConfig.write( map, world, new WorldMapConfig.Options( false, 2, 8100 ) );

		String webapp = Files.readString( map.resolve( "config" ).resolve( "webapp.conf" ) );
		assertTrue( webapp.contains( WorldMapViewer.SCRIPT_PATH ),
				"El visor no cargaria el guion y no habria muñecos: " + webapp );
		// Tal cual lo pide el renderizador: relativo a la carpeta web
		assertFalse( WorldMapViewer.SCRIPT_PATH.startsWith( "/" ) );
	}
}
