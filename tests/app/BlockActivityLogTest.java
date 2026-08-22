package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guardado de la actividad. Dos cosas no pueden fallar: que no se repita nada
 * (el mismo suceso pintado dos veces en el mapa), y que esto no crezca sin
 * limite en el disco de nadie.
 */
class BlockActivityLogTest
{
	@TempDir
	Path temporary;

	@BeforeEach
	void useATemporaryDataDirectory()
	{
		System.setProperty( "endershare.dataDirectory", temporary.resolve( "data" ).toString() );
	}

	@AfterEach
	void restoreDataDirectory()
	{
		System.clearProperty( "endershare.dataDirectory" );
	}

	@Test
	void whatIsAddedIsKeptAndSurvivesARestart()
	{
		Path repository = temporary.resolve( "farmland_mc" );
		BlockActivityLog log = new BlockActivityLog( repository );

		log.add( List.of( activity( 1, "Victor" ), activity( 2, "Daniel" ) ) );

		BlockActivityLog reopened = new BlockActivityLog( repository );
		assertEquals( 2, reopened.size() );
		assertEquals( 2, reopened.lastSeenId() );
		assertEquals( "Daniel", reopened.recent( 10 ).get( 0 ).player(), "Lo mas reciente va primero" );
	}

	@Test
	void thesameEventIsNeverStoredTwice()
	{
		BlockActivityLog log = new BlockActivityLog( temporary.resolve( "farmland_mc" ) );
		log.add( List.of( activity( 1, "Victor" ), activity( 2, "Victor" ) ) );

		List<BlockActivity> accepted = log.add( List.of( activity( 2, "Victor" ), activity( 3, "Victor" ) ) );

		assertEquals( 1, accepted.size(), "Se repintaria en el mapa un suceso ya visto" );
		assertEquals( 3, accepted.get( 0 ).id() );
		assertEquals( 3, log.size() );
	}

	@Test
	void oldActivityIsDroppedSoThisNeverGrowsForever()
	{
		BlockActivityLog log = new BlockActivityLog( temporary.resolve( "farmland_mc" ) );
		Instant old = Instant.now().minus( BlockActivityLog.RETENTION ).minusSeconds( 60 );

		log.add( List.of( new BlockActivity( 1, old, "Victor", BlockActivity.BROKEN, "minecraft:stone",
				"minecraft:overworld", 0, 0, 0 ), activity( 2, "Daniel" ) ) );

		assertEquals( 1, log.size(), "Lo de hace medio dia ya no es 'lo que esta pasando'" );
		assertEquals( "Daniel", log.recent( 10 ).get( 0 ).player() );
	}

	@Test
	void thereIsAHardCeilingOnStoredEvents()
	{
		BlockActivityLog log = new BlockActivityLog( temporary.resolve( "farmland_mc" ) );

		List<BlockActivity> lots = new java.util.ArrayList<>();
		for( int index = 1; index <= BlockActivityLog.MAX_ENTRIES + 200; index++ )
			lots.add( activity( index, "Victor" ) );
		log.add( lots );

		assertEquals( BlockActivityLog.MAX_ENTRIES, log.size() );
		assertEquals( BlockActivityLog.MAX_ENTRIES + 200, log.lastSeenId(), "El cursor no puede retroceder al podar" );
	}

	@Test
	void theLogNeverLivesInsideTheWorldRepository()
	{
		Path repository = temporary.resolve( "farmland_mc" );
		new BlockActivityLog( repository ).add( List.of( activity( 1, "Victor" ) ) );

		assertFalse( WorldMap.directoryFor( repository ).toAbsolutePath().startsWith( repository.toAbsolutePath() ) );
		assertTrue( java.nio.file.Files.exists( WorldMap.directoryFor( repository ).resolve( "activity.json" ) ) );
	}

	private static BlockActivity activity( long id, String player )
	{
		return new BlockActivity( id, Instant.now(), player, BlockActivity.PLACED, "minecraft:oak_planks",
				"minecraft:overworld", 10, 64, -20 );
	}
}
