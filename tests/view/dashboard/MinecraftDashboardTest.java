package view.dashboard;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftDashboardTest {
    @BeforeAll
    static void installTheme() throws Exception {
        SwingUtilities.invokeAndWait(DashboardTheme::install);
    }

    @Test
    void rendersAnOperationalServerStateWithoutInventingTelemetry() throws Exception {
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MinecraftDashboard dashboard = new MinecraftDashboard(new MinecraftDashboard.Actions() {});
            dashboard.setState(onlineState());
            reference.set(dashboard);
        });

        MinecraftDashboard dashboard = reference.get();
        assertNotNull(dashboard);
        assertEquals(MinecraftDashboard.Phase.ONLINE, dashboard.state().phase());
        assertEquals("STOP SERVER", dashboard.primaryActionButton().getText());
        assertTrue(dashboard.primaryActionButton().isEnabled());
        assertTrue(dashboard.commandInput().isEnabled());
        assertEquals("player-one/minecraft-friends", dashboard.state().repository());
        assertEquals("—", dashboard.state().hostAddress(), "Unknown values must remain explicit instead of using fake metrics");
    }

    @Test
    void disablesUnsafeActionsWhileSyncingOrWhenARemoteHostIsActive() throws Exception {
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new MinecraftDashboard(new MinecraftDashboard.Actions() {})));
        MinecraftDashboard dashboard = reference.get();

        SwingUtilities.invokeAndWait(() -> dashboard.setState(stateFor(MinecraftDashboard.Phase.SYNCING)));
        assertFalse(dashboard.primaryActionButton().isEnabled());
        assertEquals("SYNCING", dashboard.primaryActionButton().getText());

        SwingUtilities.invokeAndWait(() -> dashboard.setState(stateFor(MinecraftDashboard.Phase.REMOTE_HOST)));
        assertFalse(dashboard.primaryActionButton().isEnabled());
        assertEquals("REMOTE HOST", dashboard.state().phase().label());
    }

    @Test
    void delegatesPrimaryAndConsoleActionsToTheOperationalController() throws Exception {
        AtomicInteger toggles = new AtomicInteger();
        AtomicReference<String> command = new AtomicReference<>();
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            MinecraftDashboard dashboard = new MinecraftDashboard(new MinecraftDashboard.Actions() {
                @Override public void toggleServer() {
                    toggles.incrementAndGet();
                }

                @Override public void sendCommand(String value) {
                    command.set(value);
                }
            });
            dashboard.setState(stateFor(MinecraftDashboard.Phase.OFFLINE));
            reference.set(dashboard);
        });

        MinecraftDashboard dashboard = reference.get();
        SwingUtilities.invokeAndWait(() -> {
            dashboard.primaryActionButton().doClick();
            dashboard.setState(onlineState());
            dashboard.commandInput().setText("list");
            dashboard.commandInput().postActionEvent();
        });

        assertEquals(1, toggles.get());
        assertEquals("list", command.get());
        assertEquals("", dashboard.commandInput().getText());
    }

    @Test
    void copiesServerListsAndSupportsDeterministicNavigation() throws Exception {
        List<MinecraftDashboard.ServerEntry> mutable = new ArrayList<>();
        mutable.add(new MinecraftDashboard.ServerEntry("one", "/tmp/one", "FORGE READY", true));
        MinecraftDashboard.State state = new MinecraftDashboard.State(false, MinecraftDashboard.Phase.NO_SERVER,
                null, null, null, null, null, null, null, null,
                false, false, false, null, null, null, null, null, mutable);
        mutable.clear();
        assertEquals(1, state.recentServers().size());

        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MinecraftDashboard dashboard = new MinecraftDashboard(new MinecraftDashboard.Actions() {});
            dashboard.showPage(MinecraftDashboard.Page.BACKUPS);
            reference.set(dashboard);
        });
        assertEquals(MinecraftDashboard.Page.BACKUPS, reference.get().activePage());
    }

    @Test
    void emptyStateUsesOnboardingAndHidesMeaninglessServerControls() throws Exception {
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new MinecraftDashboard(new MinecraftDashboard.Actions() {})));

        MinecraftDashboard dashboard = reference.get();
        assertFalse(dashboard.state().serverLoaded());
        assertEquals(MinecraftDashboard.Phase.NO_SERVER, dashboard.state().phase());
        assertFalse(dashboard.primaryActionButton().isVisible());
        assertFalse(dashboard.commandInput().isEnabled());
    }

    @Test
    void onboardingViewportsRemainDarkWhenTheWindowIsTallerThanTheirContent() throws Exception {
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MinecraftDashboard dashboard = new MinecraftDashboard(new MinecraftDashboard.Actions() {});
            dashboard.setSize(1280, 1200);
            dashboard.doLayout();
            reference.set(dashboard);
        });

        List<JScrollPane> scrollPanes = new ArrayList<>();
        collectScrollPanes(reference.get(), scrollPanes);
        assertFalse(scrollPanes.isEmpty());
        for (JScrollPane scrollPane : scrollPanes) {
            int brightestChannel = Math.max(scrollPane.getViewport().getBackground().getRed(),
                    Math.max(scrollPane.getViewport().getBackground().getGreen(),
                            scrollPane.getViewport().getBackground().getBlue()));
            assertTrue(brightestChannel < 64, "A dashboard viewport must never expose a light default background");
        }
    }

    @Test
    void rendersConnectedPlayerNamesFromTheOperationalState() throws Exception {
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MinecraftDashboard dashboard = new MinecraftDashboard(new MinecraftDashboard.Actions() {});
            dashboard.setState(onlineStateWithPlayers());
            reference.set(dashboard);
        });

        List<JTextArea> textAreas = new ArrayList<>();
        collectTextAreas(reference.get(), textAreas);
        assertEquals(List.of("Alex", "Steve"), reference.get().state().connectedPlayers());
        assertTrue(textAreas.stream().anyMatch(area -> area.getText().contains("Alex") && area.getText().contains("Steve")));
    }

    @Test
    void delegatesImportAndPullAndDisablesBothWhileOnline() throws Exception {
        AtomicInteger imports = new AtomicInteger();
        AtomicInteger pulls = new AtomicInteger();
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MinecraftDashboard dashboard = new MinecraftDashboard(new MinecraftDashboard.Actions() {
                @Override public void importWorld() { imports.incrementAndGet(); }
                @Override public void syncNow() { pulls.incrementAndGet(); }
            });
            dashboard.setState(stateFor(MinecraftDashboard.Phase.OFFLINE));
            reference.set(dashboard);
        });

        List<JButton> buttons = new ArrayList<>();
        collectButtons(reference.get(), buttons);
        JButton importButton = buttons.stream().filter(button -> "IMPORT WORLD".equals(button.getText())).findFirst().orElseThrow();
        JButton pullButton = buttons.stream().filter(button -> "PULL WORLD".equals(button.getText())).findFirst().orElseThrow();
        assertTrue(importButton.isEnabled());
        assertTrue(pullButton.isEnabled());
        SwingUtilities.invokeAndWait(() -> {
            importButton.doClick();
            pullButton.doClick();
            reference.get().setState(onlineStateWithPlayers());
        });
        assertEquals(1, imports.get());
        assertEquals(1, pulls.get());
        assertFalse(importButton.isEnabled());
        assertFalse(pullButton.isEnabled());
    }

    @Test
    void validatesAndDelegatesInlineSettingsAndLocksThemOnline() throws Exception {
        AtomicReference<MinecraftDashboard.SettingsDraft> saved = new AtomicReference<>();
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MinecraftDashboard dashboard = new MinecraftDashboard(new MinecraftDashboard.Actions() {
                @Override public void saveServerSettings(MinecraftDashboard.SettingsDraft settings) {
                    saved.set(settings);
                }
            });
            dashboard.setState(stateFor(MinecraftDashboard.Phase.OFFLINE));
            reference.set(dashboard);
        });

        MinecraftDashboard dashboard = reference.get();
        SwingUtilities.invokeAndWait(() -> {
            dashboard.settingsNetworkInput().setText("friends-vpn");
            dashboard.settingsPortInput().setText("25570");
            dashboard.settingsRamInput().setText("2G");
            dashboard.settingsMaxPlayersInput().setText("12");
            assertTrue(dashboard.saveSettingsButton().isEnabled());
            dashboard.saveSettingsButton().doClick();
        });
        assertEquals(new MinecraftDashboard.SettingsDraft("friends-vpn", 25570, "2G", 12, false), saved.get());

        SwingUtilities.invokeAndWait(() -> dashboard.setState(onlineState()));
        assertFalse(dashboard.settingsPortInput().isEnabled());
        assertFalse(dashboard.saveSettingsButton().isEnabled());
    }

    private static void collectScrollPanes(Component component, List<JScrollPane> result) {
        if (component instanceof JScrollPane scrollPane) result.add(scrollPane);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectScrollPanes(child, result);
        }
    }

    private static void collectTextAreas(Component component, List<JTextArea> result) {
        if (component instanceof JTextArea textArea) result.add(textArea);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectTextAreas(child, result);
        }
    }

    private static void collectButtons(Component component, List<JButton> result) {
        if (component instanceof JButton button) result.add(button);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectButtons(child, result);
        }
    }

    private static MinecraftDashboard.State onlineState() {
        return new MinecraftDashboard.State(true, MinecraftDashboard.Phase.ONLINE, "Forge is accepting players",
                "minecraft-friends", "/servers/minecraft-friends", "—", "25565", "4G",
                "friends-vpn", "FORGE / JAVA 21", true, true, true, "player-one",
                "player-one/minecraft-friends", "UP TO DATE", "JUST NOW", "", List.of());
    }

    private static MinecraftDashboard.State onlineStateWithPlayers() {
        return new MinecraftDashboard.State(true, MinecraftDashboard.Phase.ONLINE, "Forge is accepting players",
                "minecraft-friends", "/servers/minecraft-friends", "LOCAL PROCESS", "25565", "4G",
                "friends-vpn", "FORGE / JAVA 21", List.of("Alex", "Steve"), 2, 20,
                true, true, true, "player-one", "player-one/minecraft-friends",
                "UP TO DATE", "JUST NOW", "", List.of());
    }

    private static MinecraftDashboard.State stateFor(MinecraftDashboard.Phase phase) {
        return stateWithServers(phase, List.of());
    }

    private static MinecraftDashboard.State stateWithServers(MinecraftDashboard.Phase phase,
            List<MinecraftDashboard.ServerEntry> servers) {
        return new MinecraftDashboard.State(true, phase, phase.label(), "minecraft-friends",
                "/servers/minecraft-friends", phase == MinecraftDashboard.Phase.REMOTE_HOST ? "100.64.0.8" : "—",
                "25565", "4G", "friends-vpn", "FORGE / JAVA 21", true, true, true,
                "player-one", "player-one/minecraft-friends", "UP TO DATE", "JUST NOW", "", servers);
    }

    private static Component[] serversListChildren(MinecraftDashboard dashboard) throws Exception {
        java.lang.reflect.Field field = MinecraftDashboard.class.getDeclaredField("serversList");
        field.setAccessible(true);
        return ((Container) field.get(dashboard)).getComponents();
    }

    @Test
    void reusesServerRowsWhenTheListDidNotChange() throws Exception {
        AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new MinecraftDashboard(new MinecraftDashboard.Actions() {})));
        MinecraftDashboard dashboard = reference.get();

        List<MinecraftDashboard.ServerEntry> servers =
                List.of(new MinecraftDashboard.ServerEntry("one", "/tmp/one", "FABRIC READY", true));
        SwingUtilities.invokeAndWait(() -> dashboard.setState(stateWithServers(MinecraftDashboard.Phase.OFFLINE, servers)));
        Component[] first = serversListChildren(dashboard);
        assertTrue(first.length > 0);

        // Mismo listado: las filas NO se reconstruyen (misma instancia de componente)
        SwingUtilities.invokeAndWait(() -> dashboard.setState(stateWithServers(MinecraftDashboard.Phase.OFFLINE, servers)));
        Component[] second = serversListChildren(dashboard);
        assertEquals(first.length, second.length);
        assertTrue(first[0] == second[0]);

        // Listado distinto: ahora sí se rehace
        List<MinecraftDashboard.ServerEntry> changed = List.of(
                new MinecraftDashboard.ServerEntry("one", "/tmp/one", "FABRIC READY", true),
                new MinecraftDashboard.ServerEntry("two", "/tmp/two", "FORGE READY", false));
        SwingUtilities.invokeAndWait(() -> dashboard.setState(stateWithServers(MinecraftDashboard.Phase.OFFLINE, changed)));
        assertTrue(serversListChildren(dashboard).length > second.length);
    }
}
