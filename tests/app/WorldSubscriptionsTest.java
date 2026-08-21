package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldSubscriptionsTest
{
	@TempDir
	Path temporaryDirectory;

	@BeforeEach
	void pointStorageAtTemporaryDirectory()
	{
		System.setProperty( "p2pmss.dataDirectory", temporaryDirectory.toString() );
	}

	@AfterEach
	void clearStorageOverride()
	{
		System.clearProperty( "p2pmss.dataDirectory" );
	}

	@Test
	void subscribesWithoutDuplicatesAndPreservesOrder()
	{
		assertTrue( WorldSubscriptions.subscribe( "daniel", "DCV05/farmland_mc" ) );
		assertTrue( WorldSubscriptions.subscribe( "daniel", "DCV05/otro_mundo" ) );
		// Repetir una suscripcion no es un alta nueva
		assertFalse( WorldSubscriptions.subscribe( "daniel", "DCV05/farmland_mc" ) );

		assertEquals( List.of( "DCV05/farmland_mc", "DCV05/otro_mundo" ), WorldSubscriptions.all( "daniel" ) );
	}

	@Test
	void eachUserKeepsTheirOwnList()
	{
		WorldSubscriptions.subscribe( "daniel", "DCV05/farmland_mc" );
		WorldSubscriptions.subscribe( "victor", "Vikkavv/otro" );

		assertEquals( List.of( "DCV05/farmland_mc" ), WorldSubscriptions.all( "daniel" ) );
		assertEquals( List.of( "Vikkavv/otro" ), WorldSubscriptions.all( "victor" ) );
	}

	@Test
	void unsubscribeRemovesOnlyWhatExisted()
	{
		WorldSubscriptions.subscribe( "daniel", "DCV05/farmland_mc" );

		assertTrue( WorldSubscriptions.unsubscribe( "daniel", "DCV05/farmland_mc" ) );
		assertFalse( WorldSubscriptions.unsubscribe( "daniel", "DCV05/farmland_mc" ) );
		assertEquals( List.of(), WorldSubscriptions.all( "daniel" ) );
	}

	@Test
	void absorbsJoinedReposAndPersistsTheMigration() throws Exception
	{
		// Formato real de joined_repos.properties: lista por usuario separada por comas
		Files.writeString( temporaryDirectory.resolve( "joined_repos.properties" ),
				"joined_repos_by_daniel=DCV05/farmland_mc,Vikkavv/mundo_victor\n" );
		WorldSubscriptions.subscribe( "daniel", "DCV05/nuevo" );

		List<String> all = WorldSubscriptions.all( "daniel" );
		assertTrue( all.contains( "DCV05/farmland_mc" ) );
		assertTrue( all.contains( "Vikkavv/mundo_victor" ) );
		assertTrue( all.contains( "DCV05/nuevo" ) );

		// La union queda persistida: borrar el legacy no pierde mundos
		Files.delete( temporaryDirectory.resolve( "joined_repos.properties" ) );
		assertEquals( all, WorldSubscriptions.all( "daniel" ) );
	}

	@Test
	void blankUserOrRepoNeverTouchesStorage()
	{
		assertFalse( WorldSubscriptions.subscribe( null, "DCV05/farmland_mc" ) );
		assertFalse( WorldSubscriptions.subscribe( "daniel", " " ) );
		assertEquals( List.of(), WorldSubscriptions.all( null ) );
	}
}
