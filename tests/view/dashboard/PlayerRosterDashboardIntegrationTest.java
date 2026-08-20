package view.dashboard;

import minecraftServerManagement.PlayerPresenceTracker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRosterDashboardIntegrationTest
{
	@BeforeAll
	static void installTheme() throws Exception
	{
		SwingUtilities.invokeAndWait( DashboardTheme::install );
	}

	@Test
	void realForgeJoinAndLeaveLinesFlowIntoTheVisibleRoster() throws Exception
	{
		PlayerPresenceTracker tracker = new PlayerPresenceTracker();
		tracker.reset( 20 );
		tracker.acceptLine( "[Server thread/INFO] [minecraft/MinecraftServer]: TestPlayer joined the game" );

		AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
		SwingUtilities.invokeAndWait( () ->
		{
			MinecraftDashboard dashboard = new MinecraftDashboard( new MinecraftDashboard.Actions()
			{
			} );
			dashboard.setState( stateFrom( tracker.snapshot() ) );
			reference.set( dashboard );
		} );
		assertTrue( rosterText( reference.get() ).contains( "TestPlayer" ) );

		tracker.acceptLine( "[Server thread/INFO] [minecraft/MinecraftServer]: TestPlayer left the game" );
		SwingUtilities.invokeAndWait( () -> reference.get().setState( stateFrom( tracker.snapshot() ) ) );
		assertFalse( rosterText( reference.get() ).contains( "TestPlayer" ) );
	}

	private static MinecraftDashboard.State stateFrom( PlayerPresenceTracker.Snapshot snapshot )
	{
		return new MinecraftDashboard.State( true, MinecraftDashboard.Phase.ONLINE, "Forge is accepting players",
				"test", "/tmp/test", "LOCAL PROCESS", "25565", "1G", "test-network",
				"FORGE / JAVA 21", snapshot.players(), snapshot.onlineCount(), snapshot.maxPlayers(),
				true, true, true, "player", "player/test", "UP TO DATE", "JUST NOW", "", List.of() );
	}

	private static String rosterText( Component root )
	{
		List<JTextArea> areas = new ArrayList<>();
		collect( root, areas );
		return areas.stream().map( JTextArea::getText ).filter( text -> text.contains( "TestPlayer" ) || text.contains( "No players" ) )
				.findFirst().orElse( "" );
	}

	private static void collect( Component component, List<JTextArea> areas )
	{
		if( component instanceof JTextArea area )
			areas.add( area );
		if( component instanceof Container container )
		{
			for( Component child : container.getComponents() )
				collect( child, areas );
		}
	}
}
