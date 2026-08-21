package app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServersDatTest
{
	@TempDir
	Path temporaryDirectory;

	@Test
	void createsTheFileFromScratchWithTheServerEntry() throws Exception
	{
		Path file = temporaryDirectory.resolve( "servers.dat" );

		assertTrue( ServersDat.upsertServer( file, "Endershare · farmland", "farm.ply.gg:123" ) );

		Map<String, Object> root = readBack( file );
		ServersDat.NbtList servers = (ServersDat.NbtList) root.get( "servers" );
		assertEquals( 1, servers.items().size() );
		@SuppressWarnings("unchecked")
		Map<String, Object> entry = (Map<String, Object>) servers.items().get( 0 );
		assertEquals( "Endershare · farmland", entry.get( "name" ) );
		assertEquals( "farm.ply.gg:123", entry.get( "ip" ) );
	}

	@Test
	void updatesTheAddressPreservingEveryOtherServerAndField() throws Exception
	{
		Path file = temporaryDirectory.resolve( "servers.dat" );

		// Lista previa del jugador: otro server con icon y flags que NO se tocan
		Map<String, Object> friend = new LinkedHashMap<>();
		friend.put( "name", "Hypixel" );
		friend.put( "ip", "mc.hypixel.net" );
		friend.put( "icon", "aWNvbi1iYXNlNjQ=" );
		friend.put( "acceptTextures", (byte) 1 );
		Map<String, Object> ours = new LinkedHashMap<>();
		ours.put( "name", "Endershare · farmland" );
		ours.put( "ip", "old-address:1" );
		Map<String, Object> root = new LinkedHashMap<>();
		root.put( "servers", new ServersDat.NbtList( ServersDat.TAG_COMPOUND,
				new java.util.ArrayList<>( List.of( friend, ours ) ) ) );
		Files.write( file, ServersDat.writeRoot( root ) );

		assertTrue( ServersDat.upsertServer( file, "Endershare · farmland", "83.45.120.9:25565" ) );

		Map<String, Object> reread = readBack( file );
		ServersDat.NbtList servers = (ServersDat.NbtList) reread.get( "servers" );
		assertEquals( 2, servers.items().size() );
		@SuppressWarnings("unchecked")
		Map<String, Object> keptFriend = (Map<String, Object>) servers.items().get( 0 );
		assertEquals( "mc.hypixel.net", keptFriend.get( "ip" ) );
		assertEquals( "aWNvbi1iYXNlNjQ=", keptFriend.get( "icon" ) );
		assertEquals( (byte) 1, keptFriend.get( "acceptTextures" ) );
		@SuppressWarnings("unchecked")
		Map<String, Object> updated = (Map<String, Object>) servers.items().get( 1 );
		assertEquals( "83.45.120.9:25565", updated.get( "ip" ) );
		// Y el backup del estado anterior queda al lado
		assertTrue( Files.isRegularFile( temporaryDirectory.resolve( "servers.dat.bak" ) ) );
	}

	@Test
	void refusesToTouchAFileItCannotParse() throws Exception
	{
		Path file = temporaryDirectory.resolve( "servers.dat" );
		byte[] garbage = {1, 2, 3, 4, 5};
		Files.write( file, garbage );

		assertFalse( ServersDat.upsertServer( file, "Endershare · farmland", "farm.ply.gg:123" ) );
		assertArrayEquals( garbage, Files.readAllBytes( file ) );
	}

	private static Map<String, Object> readBack( Path file ) throws Exception
	{
		try (DataInputStream input = new DataInputStream( new ByteArrayInputStream( Files.readAllBytes( file ) ) ))
		{
			return ServersDat.readRoot( input );
		}
	}
}
