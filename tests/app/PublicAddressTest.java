package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublicAddressTest
{
	@Test
	void chooseAddressPrefersTheTunnelAndFallsBackToPublicIpWithPort()
	{
		assertEquals( "abc.ply.gg:12345", PublicAddress.chooseAddress( "abc.ply.gg:12345", "1.2.3.4", 25565 ) );
		assertEquals( "1.2.3.4:25565", PublicAddress.chooseAddress( "  ", "1.2.3.4", 25565 ) );
		assertEquals( "1.2.3.4:25570", PublicAddress.chooseAddress( null, " 1.2.3.4 ", 25570 ) );
		assertNull( PublicAddress.chooseAddress( null, null, 25565 ) );
		assertNull( PublicAddress.chooseAddress( "", "  ", 25565 ) );
	}

	@Test
	void looksLikeIpv4RejectsProviderGarbage()
	{
		assertTrue( PublicAddress.looksLikeIpv4( "83.45.120.9" ) );
		assertFalse( PublicAddress.looksLikeIpv4( null ) );
		assertFalse( PublicAddress.looksLikeIpv4( "" ) );
		assertFalse( PublicAddress.looksLikeIpv4( "<html>rate limited</html>" ) );
		assertFalse( PublicAddress.looksLikeIpv4( "2001:db8::1" ) );
		assertFalse( PublicAddress.looksLikeIpv4( "not.an.ip" ) );
	}
}
