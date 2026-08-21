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
		assertEquals( "P2PMSS · farmland", created.path( "name" ).asText() );
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
}
