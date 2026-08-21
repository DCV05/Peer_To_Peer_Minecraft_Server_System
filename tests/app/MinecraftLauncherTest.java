package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftLauncherTest
{
	@TempDir
	Path temporaryDirectory;

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void createsTheProfileWithTheWorldVersionAndKeepsExistingOnes() throws Exception
	{
		Path profiles = temporaryDirectory.resolve( "launcher_profiles.json" );
		Files.writeString( profiles, """
				{ "profiles": { "vanilla": { "name": "Latest", "lastVersionId": "latest-release" } }, "settings": {} }
				""" );

		assertTrue( MinecraftLauncher.upsertProfile( profiles, "farmland", "1.19" ) );

		JsonNode root = JSON.readTree( Files.readString( profiles ) );
		JsonNode created = root.path( "profiles" ).path( MinecraftLauncher.PROFILE_ID );
		assertEquals( "Endershare · farmland", created.path( "name" ).asText() );
		assertEquals( "1.19", created.path( "lastVersionId" ).asText() );
		// Los perfiles del usuario no se tocan
		assertEquals( "latest-release", root.path( "profiles" ).path( "vanilla" ).path( "lastVersionId" ).asText() );
	}

	@Test
	void reusesTheSameProfileInsteadOfAccumulatingOnePerPlay() throws Exception
	{
		Path profiles = temporaryDirectory.resolve( "launcher_profiles.json" );
		Files.writeString( profiles, "{ \"profiles\": {} }" );

		assertTrue( MinecraftLauncher.upsertProfile( profiles, "farmland", "1.19" ) );
		assertTrue( MinecraftLauncher.upsertProfile( profiles, "otro_mundo", "1.20.1" ) );

		JsonNode root = JSON.readTree( Files.readString( profiles ) );
		assertEquals( 1, root.path( "profiles" ).size() );
		assertEquals( "1.20.1",
				root.path( "profiles" ).path( MinecraftLauncher.PROFILE_ID ).path( "lastVersionId" ).asText() );
	}

	@Test
	void refusesWhenTheLauncherIsNotInstalledOrTheVersionIsUnknown() throws Exception
	{
		Path missing = temporaryDirectory.resolve( "no-launcher/launcher_profiles.json" );
		assertFalse( MinecraftLauncher.upsertProfile( missing, "farmland", "1.19" ) );

		Path profiles = temporaryDirectory.resolve( "launcher_profiles.json" );
		Files.writeString( profiles, "{ \"profiles\": {} }" );
		// Sin version conocida no se escribe un perfil que apunte a la nada
		assertFalse( MinecraftLauncher.upsertProfile( profiles, "farmland", null ) );
		assertEquals( 0, JSON.readTree( Files.readString( profiles ) ).path( "profiles" ).size() );
	}

	@Test
	void offersVanillaFirstAndTheInstalledVariantsOfTheSameVersion() throws Exception
	{
		Path minecraft = temporaryDirectory.resolve( "minecraft" );
		Files.createDirectories( minecraft.resolve( "versions/1.21.7" ) );
		Files.createDirectories( minecraft.resolve( "versions/fabric-loader-0.16.9-1.21.7" ) );
		Files.createDirectories( minecraft.resolve( "versions/1.20.1" ) );
		Files.createDirectories( minecraft.resolve( "versions/" + MinecraftLauncher.QUICK_PLAY_VERSION_ID ) );

		var candidates = MinecraftLauncher.installedVersionCandidates( minecraft, "1.21.7" );

		// La vanilla exacta va primero (el launcher la descarga si falta); la de
		// otra version y nuestra version quick-play no aparecen jamas
		assertEquals( java.util.List.of( "1.21.7", "fabric-loader-0.16.9-1.21.7" ), candidates );

		// Sin carpeta versions/, la vanilla sola sigue siendo una oferta valida
		assertEquals( java.util.List.of( "1.19" ),
				MinecraftLauncher.installedVersionCandidates( temporaryDirectory.resolve( "missing" ), "1.19" ) );
	}

	@Test
	void writesTheQuickPlayVersionInheritingTheChosenBase() throws Exception
	{
		Path minecraft = temporaryDirectory.resolve( "minecraft" );

		assertTrue( MinecraftLauncher.writeQuickPlayVersion( minecraft, "1.21.7", "farm.ply.gg:123" ) );

		Path json = minecraft.resolve( "versions/" + MinecraftLauncher.QUICK_PLAY_VERSION_ID
				+ "/" + MinecraftLauncher.QUICK_PLAY_VERSION_ID + ".json" );
		JsonNode version = JSON.readTree( Files.readString( json ) );
		assertEquals( MinecraftLauncher.QUICK_PLAY_VERSION_ID, version.path( "id" ).asText() );
		assertEquals( "1.21.7", version.path( "inheritsFrom" ).asText() );
		assertEquals( "--quickPlayMultiplayer", version.path( "arguments" ).path( "game" ).get( 0 ).asText() );
		assertEquals( "farm.ply.gg:123", version.path( "arguments" ).path( "game" ).get( 1 ).asText() );

		// Cada JOIN reescribe la direccion: el host pudo cambiar de tunel o IP
		assertTrue( MinecraftLauncher.writeQuickPlayVersion( minecraft, "1.21.7", "9.9.9.9:25565" ) );
		JsonNode rewritten = JSON.readTree( Files.readString( json ) );
		assertEquals( "9.9.9.9:25565", rewritten.path( "arguments" ).path( "game" ).get( 1 ).asText() );
	}

	@Test
	void remembersTheChosenJoinVersionPerWorld() throws Exception
	{
		System.setProperty( "p2pmss.dataDirectory", temporaryDirectory.resolve( "data" ).toString() );
		try
		{
			assertEquals( null, MinecraftLauncher.rememberedJoinVersion( "DCV05/farmland_mc" ) );
			MinecraftLauncher.rememberJoinVersion( "DCV05/farmland_mc", "fabric-loader-0.16.9-1.21.7" );
			assertEquals( "fabric-loader-0.16.9-1.21.7", MinecraftLauncher.rememberedJoinVersion( "DCV05/farmland_mc" ) );
			assertEquals( null, MinecraftLauncher.rememberedJoinVersion( "DCV05/otro_mundo" ) );
		}
		finally
		{
			System.clearProperty( "p2pmss.dataDirectory" );
		}
	}
}
