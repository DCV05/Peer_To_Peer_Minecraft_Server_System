package minecraftServerManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlayerPresenceTrackerTest
{
	@Test
	void listResponseProvidesAuthoritativeNamesAndCapacity()
	{
		PlayerPresenceTracker tracker = new PlayerPresenceTracker();

		assertTrue( tracker.acceptLine( "[Server thread/INFO]: There are 2 of a max of 12 players online: Alex, Steve" ) );
		assertEquals( List.of( "Alex", "Steve" ), tracker.snapshot().players() );
		assertEquals( 2, tracker.snapshot().onlineCount() );
		assertEquals( 12, tracker.snapshot().maxPlayers() );
		assertFalse( tracker.acceptLine( "[Server thread/INFO]: There are 2 of a max of 12 players online: Alex, Steve" ) );
	}

	@Test
	void joinAndLeaveEventsRefreshRosterBetweenPolls()
	{
		PlayerPresenceTracker tracker = new PlayerPresenceTracker();
		tracker.reset( 8 );

		assertTrue( tracker.acceptLine( "[Server thread/INFO] [minecraft/MinecraftServer]: Alex joined the game" ) );
		assertTrue( tracker.acceptLine( "[Server thread/INFO] [minecraft/MinecraftServer]: Steve joined the game" ) );
		assertEquals( List.of( "Alex", "Steve" ), tracker.snapshot().players() );
		assertTrue( tracker.acceptLine( "[Server thread/INFO] [minecraft/MinecraftServer]: Alex left the game" ) );
		assertEquals( List.of( "Steve" ), tracker.snapshot().players() );
		assertEquals( 8, tracker.snapshot().maxPlayers() );
	}

	@Test
	void acceptsRosterPublishedByARemotePeer()
	{
		PlayerPresenceTracker tracker = new PlayerPresenceTracker();

		tracker.replaceSnapshot( List.of( "RemoteAlex", "RemoteSteve" ), 2, 30 );

		assertEquals( List.of( "RemoteAlex", "RemoteSteve" ), tracker.snapshot().players() );
		assertEquals( 2, tracker.snapshot().onlineCount() );
		assertEquals( 30, tracker.snapshot().maxPlayers() );
	}
}
