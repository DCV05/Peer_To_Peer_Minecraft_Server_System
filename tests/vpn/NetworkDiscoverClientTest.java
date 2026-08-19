package vpn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NetworkDiscoverClientTest {
    @Test
    void parsesRosterPublishedByANewDashboardHost() {
        NetworkDiscoverClient.DiscoveryResult result = NetworkDiscoverClient.parseResponse(
                "100.64.0.8", "HERE;ONLINE=2;MAX=20;PLAYERS=Alex,Steve");

        assertTrue(result.found());
        assertTrue(result.rosterAvailable());
        assertEquals("100.64.0.8", result.host());
        assertEquals(List.of("Alex", "Steve"), result.players());
        assertEquals(2, result.onlinePlayers());
        assertEquals(20, result.maxPlayers());
    }

    @Test
    void remainsCompatibleWithLegacyHereResponses() {
        NetworkDiscoverClient.DiscoveryResult result = NetworkDiscoverClient.parseResponse("100.64.0.9", "HERE");

        assertTrue(result.found());
        assertFalse(result.rosterAvailable());
        assertEquals(List.of(), result.players());
    }

    @Test
    void rejectsUnrelatedUdpResponses() {
        assertFalse(NetworkDiscoverClient.parseResponse("100.64.0.10", "UNKNOWN").found());
    }
}
