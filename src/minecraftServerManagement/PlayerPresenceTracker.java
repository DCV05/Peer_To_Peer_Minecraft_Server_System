package minecraftServerManagement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe projection of Forge console output into a connected-player
 * snapshot. Periodic {@code list} responses are authoritative; join and leave
 * messages keep the dashboard current between polls.
 */
public final class PlayerPresenceTracker {
    private static final Pattern LIST_RESPONSE = Pattern.compile(
            "There are\\s+(\\d+)\\s+of a max of\\s+(\\d+)\\s+players online:?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN = Pattern.compile("\\b([A-Za-z0-9_]{1,16}) joined the game\\b");
    private static final Pattern LEAVE = Pattern.compile("\\b([A-Za-z0-9_]{1,16}) left the game\\b");
    private static final Pattern VALID_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Set<String> players = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private int onlineCount;
    private int maxPlayers = 20;

    /** Returns true only when the visible player snapshot changed. */
    public synchronized boolean acceptLine(String line) {
        if (line == null || line.isBlank()) return false;
        Snapshot before = snapshot();

        Matcher list = LIST_RESPONSE.matcher(line);
        if (list.find()) {
            onlineCount = parsePositiveOrZero(list.group(1), players.size());
            maxPlayers = Math.max(onlineCount, parsePositiveOrZero(list.group(2), maxPlayers));
            players.clear();
            for (String candidate : list.group(3).split(",")) {
                String username = candidate.trim();
                if (VALID_USERNAME.matcher(username).matches()) players.add(username);
            }
            onlineCount = Math.max(onlineCount, players.size());
            return !before.equals(snapshot());
        }

        Matcher join = JOIN.matcher(line);
        if (join.find()) {
            players.add(join.group(1));
            onlineCount = players.size();
        }

        Matcher leave = LEAVE.matcher(line);
        if (leave.find()) {
            players.remove(leave.group(1));
            onlineCount = players.size();
        }
        return !before.equals(snapshot());
    }

    public synchronized void reset(int configuredMaxPlayers) {
        players.clear();
        onlineCount = 0;
        maxPlayers = configuredMaxPlayers > 0 ? configuredMaxPlayers : 20;
    }

    /** Replaces local state with a roster published by a remote P2P host. */
    public synchronized void replaceSnapshot(List<String> remotePlayers, int remoteOnlineCount, int remoteMaxPlayers) {
        players.clear();
        if (remotePlayers != null) {
            remotePlayers.stream()
                    .filter(player -> player != null && VALID_USERNAME.matcher(player).matches())
                    .forEach(players::add);
        }
        onlineCount = Math.max(remoteOnlineCount, players.size());
        maxPlayers = Math.max(Math.max(1, remoteMaxPlayers), onlineCount);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(players), onlineCount, maxPlayers);
    }

    private static int parsePositiveOrZero(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record Snapshot(List<String> players, int onlineCount, int maxPlayers) {
        public Snapshot {
            players = players == null ? List.of() : List.copyOf(players);
            onlineCount = Math.max(onlineCount, players.size());
            maxPlayers = Math.max(Math.max(1, maxPlayers), onlineCount);
        }
    }
}
