package vpn;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Discovers the active P2P host and, when supported, its live player roster. */
public class NetworkDiscoverClient {
    public static String discover(String networkName, int targetPort, int timeoutMs) throws IOException {
        return discoverStatus(networkName, targetPort, timeoutMs).host();
    }

    public static DiscoveryResult discoverStatus(String networkName, int targetPort, int timeoutMs) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(timeoutMs);

            byte[] data = ("DISCOVER: " + networkName).getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    InetAddress.getByName("255.255.255.255"), targetPort);
            socket.send(packet);

            try {
                byte[] buffer = new byte[2048];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(responsePacket);
                String response = new String(responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8);
                String ip = responsePacket.getAddress().getHostAddress();
                DiscoveryResult result = parseResponse(ip, response);
                System.out.println("Respuesta recibida de " + ip + ": " + response);
                return result;
            } catch (SocketTimeoutException ignored) {
                return DiscoveryResult.notFound();
            }
        }
    }

    static DiscoveryResult parseResponse(String host, String response) {
        if (response == null || !response.startsWith("HERE")) return DiscoveryResult.notFound();
        int online = 0;
        int max = 0;
        List<String> players = new ArrayList<>();
        for (String field : response.split(";")) {
            if (field.startsWith("ONLINE=")) online = parseNonNegative(field.substring("ONLINE=".length()));
            else if (field.startsWith("MAX=")) max = parseNonNegative(field.substring("MAX=".length()));
            else if (field.startsWith("PLAYERS=")) {
                for (String candidate : field.substring("PLAYERS=".length()).split(",")) {
                    String player = candidate.trim();
                    if (player.matches("[A-Za-z0-9_]{1,16}")) players.add(player);
                }
            }
        }
        online = Math.max(online, players.size());
        if (max > 0) max = Math.max(max, online);
        return new DiscoveryResult(host, players, online, max);
    }

    public static DiscoveryResult surroundDiscoverStatus(String networkName, int targetPort, int timeoutMs) {
        try {
            return discoverStatus(networkName, targetPort, timeoutMs);
        } catch (IOException ignored) {
            return DiscoveryResult.notFound();
        }
    }

    public static String surroundDiscoverIOException(String networkName, int targetPort, int timeoutMs) {
        return surroundDiscoverStatus(networkName, targetPort, timeoutMs).host();
    }

    private static int parseNonNegative(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record DiscoveryResult(String host, List<String> players, int onlinePlayers, int maxPlayers) {
        public DiscoveryResult {
            host = host == null || host.isBlank() ? "NotFound" : host;
            players = players == null ? List.of() : List.copyOf(players);
            onlinePlayers = Math.max(onlinePlayers, players.size());
            maxPlayers = maxPlayers <= 0 ? 0 : Math.max(maxPlayers, onlinePlayers);
        }

        public boolean found() {
            return !"NotFound".equals(host);
        }

        public boolean rosterAvailable() {
            return maxPlayers > 0;
        }

        private static DiscoveryResult notFound() {
            return new DiscoveryResult("NotFound", List.of(), 0, 0);
        }
    }
}
