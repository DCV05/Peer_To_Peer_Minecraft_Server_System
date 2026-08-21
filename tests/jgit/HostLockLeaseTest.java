package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HostLockLeaseTest
{

	@AfterEach
	void clearPublishedDetails()
	{
		HostLock.clearPublishedDetails();
	}

	@Test
	void leaseCarriesTheHostDetailsAndReadsThemBack()
	{
		HostLock.HostDetails published = new HostLock.HostDetails( "farm.craft.ply.gg:12345", 2, 4, "1.19" );

		ObjectNode lease = HostLock.leaseJson( "DCV05", published );
		HostLock.HostDetails read = HostLock.detailsFrom( lease );

		assertEquals( "DCV05", lease.path( "host_nickname" ).asText() );
		assertEquals( "farm.craft.ply.gg:12345", read.tunnelAddress() );
		assertEquals( 2, read.onlinePlayers() );
		assertEquals( 4, read.maxPlayers() );
		assertEquals( "1.19", read.minecraftVersion() );
	}

	@Test
	void aLegacyLeaseWithoutDetailsStillReads()
	{
		// Contrato de compatibilidad: un peer con version vieja escribe el lease
		// sin campos nuevos y un peer moderno debe leerlo sin inventarse nada
		ObjectNode legacy = GitUtils.JSON_MAPPER.createObjectNode()
				.put( "host_nickname", "Vikkavv" )
				.put( "lease_seconds", 900 );

		HostLock.HostDetails read = HostLock.detailsFrom( legacy );

		assertNull( read.tunnelAddress() );
		assertEquals( -1, read.onlinePlayers() );
		assertEquals( -1, read.maxPlayers() );
		assertNull( read.minecraftVersion() );
	}

	@Test
	void emptyDetailsWriteNoOptionalFields()
	{
		ObjectNode lease = HostLock.leaseJson( "DCV05", HostLock.HostDetails.empty() );

		assertFalse( lease.has( "tunnel_address" ) );
		assertFalse( lease.has( "online_players" ) );
		assertFalse( lease.has( "max_players" ) );
		assertFalse( lease.has( "minecraft_version" ) );
		// Los campos del contrato original siguen presentes
		assertTrue( lease.has( "host_nickname" ) && lease.has( "lease_seconds" ) );
	}

	@Test
	void zeroPlayersOnlineIsPublishedAsAValidCount()
	{
		// 0 dentro NO es "sin dato": la tarjeta debe poder decir "0/4"
		ObjectNode lease = HostLock.leaseJson( "DCV05", new HostLock.HostDetails( null, 0, 4, null ) );
		HostLock.HostDetails read = HostLock.detailsFrom( lease );

		assertEquals( 0, read.onlinePlayers() );
		assertEquals( 4, read.maxPlayers() );
		assertFalse( lease.has( "tunnel_address" ) );
	}
}
