package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotifierTest
{
	@TempDir
	Path temporaryDirectory;

	private final List<String> shown = new ArrayList<>();

	@BeforeEach
	void isolateDataDirectory()
	{
		System.setProperty( "p2pmss.dataDirectory", temporaryDirectory.resolve( "data" ).toString() );
		Notifier.setBackendForTests( ( title, message ) -> shown.add( title + ": " + message ) );
	}

	@AfterEach
	void restore()
	{
		Notifier.setBackendForTests( null );
		System.clearProperty( "p2pmss.dataDirectory" );
	}

	@Test
	void notifiesByDefaultAndRespectsThePersistedSwitch()
	{
		assertTrue( Notifier.isEnabled() );
		Notifier.notifyWorldEvent( "Endershare", "Vikkavv is hosting farmland" );
		assertEquals( List.of( "Endershare: Vikkavv is hosting farmland" ), shown );

		Notifier.setEnabled( false );
		assertFalse( Notifier.isEnabled() );
		Notifier.notifyWorldEvent( "Endershare", "silenced" );
		assertEquals( 1, shown.size() );

		Notifier.setEnabled( true );
		Notifier.notifyWorldEvent( "Endershare", "back" );
		assertEquals( 2, shown.size() );
	}
}
