package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WorldEventFileNameTest
{
	@Test
	void parsesNickWithHyphensBecauseGitHubAllowsThem()
	{
		WorldEvents.WorldEvent event = WorldEvents.parseFileName( "1755771600000-my-friend-nick-want_to_play.json", "sha1" );
		assertNotNull( event );
		assertEquals( 1755771600000L, event.atMillis() );
		assertEquals( "my-friend-nick", event.nick() );
		assertEquals( "want_to_play", event.type() );
		assertEquals( "sha1", event.sha() );
	}

	@Test
	void rejectsFilesThatAreNotEvents()
	{
		assertNull( WorldEvents.parseFileName( "README.md", "x" ) );
		assertNull( WorldEvents.parseFileName( "abc-nick-type.json", "x" ) );
		assertNull( WorldEvents.parseFileName( "123.json", "x" ) );
		assertNull( WorldEvents.parseFileName( null, "x" ) );
		assertNull( WorldEvents.parseFileName( "1700000000000-.json", "x" ) );
	}
}
