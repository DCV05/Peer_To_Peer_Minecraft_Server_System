package view.dashboard;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.Scrollable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static view.dashboard.DashboardTheme.ACTIVE_BACKGROUND;
import static view.dashboard.DashboardTheme.AMBER;
import static view.dashboard.DashboardTheme.APP_BACKGROUND;
import static view.dashboard.DashboardTheme.BORDER;
import static view.dashboard.DashboardTheme.CYAN;
import static view.dashboard.DashboardTheme.GREEN;
import static view.dashboard.DashboardTheme.HAIRLINE;
import static view.dashboard.DashboardTheme.HOVER_BACKGROUND;
import static view.dashboard.DashboardTheme.PANEL_BACKGROUND;
import static view.dashboard.DashboardTheme.RED;
import static view.dashboard.DashboardTheme.SIDEBAR_BACKGROUND;
import static view.dashboard.DashboardTheme.TEXT;
import static view.dashboard.DashboardTheme.TEXT_DIM;
import static view.dashboard.DashboardTheme.TEXT_MUTED;

/**
 * Operational dashboard for P2PMSS. It intentionally contains no Forge, Git or
 * network code: MainFrame supplies real state and actions, while this class owns
 * the visual hierarchy and honest loading/error/empty states.
 */
public final class MinecraftDashboard extends JPanel {
    public enum Page {
        OVERVIEW("Overview"),
        SERVERS("Servers"),
        BACKUPS("Backups"),
        NETWORK("Network"),
        CONSOLE("Console"),
        SETTINGS("Settings");

        private final String title;

        Page(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public enum Phase {
        NO_SERVER("NO SERVER", TEXT_MUTED),
        DISCOVERING("DISCOVERING", AMBER),
        OFFLINE("OFFLINE", TEXT_MUTED),
        REMOTE_HOST("REMOTE HOST", CYAN),
        SYNCING("SYNCING", AMBER),
        IMPORTING("IMPORTING", AMBER),
        STARTING("STARTING", AMBER),
        ONLINE("ONLINE", GREEN),
        STOPPING("STOPPING", AMBER),
        SAVING("SAVING", AMBER),
        ERROR("ERROR", RED);

        private final String label;
        private final Color color;

        Phase(String label, Color color) {
            this.label = label;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public Color color() {
            return color;
        }

        public boolean isBusy() {
            return this == DISCOVERING || this == SYNCING || this == IMPORTING
                    || this == STARTING || this == STOPPING || this == SAVING;
        }
    }

    public record ServerEntry(String name, String path, String detail, boolean selected) {
        public ServerEntry {
            name = valueOr(name, "Unnamed server");
            path = valueOr(path, "—");
            detail = valueOr(detail, "Forge server");
        }
    }

    /** Validated, machine-local values submitted by the Settings page. */
    public record SettingsDraft(String networkName, int port, String ram, int maxPlayers, boolean publicUrl) {}

    public record State(
            boolean serverLoaded,
            Phase phase,
            String phaseDetail,
            String serverName,
            String serverPath,
            String hostAddress,
            String port,
            String ram,
            String networkName,
            String loader,
            List<String> connectedPlayers,
            int onlinePlayers,
            int maxPlayers,
            boolean githubSelected,
            boolean githubAuthenticated,
            boolean repositoryLinked,
            String githubAccount,
            String repository,
            String syncState,
            String lastSync,
            String errorMessage,
            List<ServerEntry> recentServers
    ) {
        public State {
            phase = phase == null ? Phase.NO_SERVER : phase;
            phaseDetail = valueOr(phaseDetail, "Waiting for a server folder");
            serverName = valueOr(serverName, "NO SERVER SELECTED");
            serverPath = valueOr(serverPath, "Open or create a Minecraft server to begin");
            hostAddress = valueOr(hostAddress, "—");
            port = valueOr(port, "—");
            ram = valueOr(ram, "—");
            networkName = valueOr(networkName, "—");
            loader = valueOr(loader, "FORGE");
            connectedPlayers = connectedPlayers == null ? List.of() : List.copyOf(connectedPlayers);
            onlinePlayers = Math.max(onlinePlayers, connectedPlayers.size());
            maxPlayers = Math.max(Math.max(1, maxPlayers), onlinePlayers);
            githubAccount = valueOr(githubAccount, "NOT CONNECTED");
            repository = valueOr(repository, "NOT LINKED");
            syncState = valueOr(syncState, "NOT CONFIGURED");
            lastSync = valueOr(lastSync, "—");
            errorMessage = errorMessage == null ? "" : errorMessage;
            recentServers = recentServers == null ? List.of() : List.copyOf(recentServers);
        }

        /** Compatibility constructor for callers that do not publish a live roster. */
        public State(boolean serverLoaded, Phase phase, String phaseDetail, String serverName,
                String serverPath, String hostAddress, String port, String ram, String networkName,
                String loader, boolean githubSelected, boolean githubAuthenticated,
                boolean repositoryLinked, String githubAccount, String repository, String syncState,
                String lastSync, String errorMessage, List<ServerEntry> recentServers) {
            this(serverLoaded, phase, phaseDetail, serverName, serverPath, hostAddress, port, ram,
                    networkName, loader, List.of(), 0, 20, githubSelected, githubAuthenticated,
                    repositoryLinked, githubAccount, repository, syncState, lastSync, errorMessage,
                    recentServers);
        }

        public static State empty(List<ServerEntry> recentServers) {
            return new State(false, Phase.NO_SERVER, "Open or create a Minecraft server", null, null,
                    null, null, null, null, null, false, false, false,
                    null, null, null, null, null, recentServers);
        }
    }

    public interface Actions {
        default void createServer() {}
        default void openServer() {}
        default void selectServer(String path) {}
        default void cloneInvitedServer() {}
        default void toggleServer() {}
        default void refreshNetwork() {}
        default void syncNow() {}
        default void importWorld() {}
        default void openModsFolder() {}
        default void openServerFolder() {}
        default void openServerSettings() {}
        default void saveServerSettings(SettingsDraft settings) {}
        default void openGeneralSettings() {}
        default void createRepository() {}
        default void signIntoGitHub() {}
        default void signOutOfGitHub() {}
        default void showGitHubProfile() {}
        default void showInvitations() {}
        default void inviteHost() {}
        default void sendCommand(String command) {}
    }

    private final Actions actions;
    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pages = new JPanel(pageLayout);
    private final CardLayout overviewLayout = new CardLayout();
    private final JPanel overviewContainer = new JPanel(overviewLayout);
    private final Map<Page, JButton> navigationButtons = new EnumMap<>(Page.class);
    private final Map<String, MetricCard> metrics = new java.util.HashMap<>();
    private final Map<Phase, JLabel> lifecycleSteps = new EnumMap<>(Phase.class);

    private final JLabel phaseSquare = new JLabel("■");
    private final JLabel phaseLabel = DashboardTheme.eyebrow("NO SERVER");
    private final JLabel contextLabel = DashboardTheme.eyebrow("P2P SERVER CONTROL");
    private final JLabel pageTitle = DashboardTheme.label("Overview", TEXT, 30, Font.PLAIN);
    private final JLabel serverPathLabel = DashboardTheme.label("Open or create a Minecraft server to begin", TEXT_MUTED, 11, Font.PLAIN);
    private final JLabel accountStatus = DashboardTheme.label("■  GITHUB OFFLINE", TEXT_MUTED, 11, Font.PLAIN);
    private final JLabel accountName = DashboardTheme.label("NOT CONNECTED", TEXT_DIM, 11, Font.PLAIN);
    private final JLabel errorBanner = DashboardTheme.label("", RED, 11, Font.PLAIN);
    private final JLabel connectionInstruction = DashboardTheme.label("Server address unavailable", TEXT_MUTED, 20, Font.PLAIN);
    private final JLabel networkDetail = DashboardTheme.label("Configure a P2P VPN and scan the network.", TEXT_MUTED, 12, Font.PLAIN);
    private final JLabel repositoryValue = DashboardTheme.label("NOT LINKED", TEXT, 13, Font.PLAIN);
    private final JLabel syncDetail = DashboardTheme.label("Connect GitHub to protect this world.", TEXT_MUTED, 11, Font.PLAIN);
    private final JLabel settingsServerValue = DashboardTheme.label("NO SERVER SELECTED", TEXT, 13, Font.PLAIN);
    private final JLabel settingsPathValue = DashboardTheme.label("—", TEXT_MUTED, 11, Font.PLAIN);
    private final JLabel uptimeValue = DashboardTheme.label("00:00:00", TEXT, 12, Font.PLAIN);
    private final JLabel onboardingJavaValue = DashboardTheme.label("JAVA " + Runtime.version().feature(), GREEN, 13, Font.PLAIN);
    private final JLabel onboardingGitHubValue = DashboardTheme.label("NOT CONNECTED", TEXT_MUTED, 13, Font.PLAIN);
    private final JLabel onboardingNetworkValue = DashboardTheme.label("—", TEXT, 13, Font.PLAIN);
    private final JLabel onboardingRecentValue = DashboardTheme.label("0 KNOWN SERVERS", TEXT_MUTED, 13, Font.PLAIN);
    private final JLabel settingsStatus = DashboardTheme.label("Changes apply on the next server start.", TEXT_MUTED, 11, Font.PLAIN);

    private final JButton primaryAction = new JButton("START SERVER");
    private final JButton topRefreshButton = new JButton("SCAN");
	private final JButton createRepositoryButton = new JButton("RETRY PRIVATE BACKUP");
    private final JButton signInButton = new JButton("SIGN IN");
    private final JButton signOutButton = new JButton("SIGN OUT");
    private final JButton inviteButton = new JButton("INVITE HOST");
    private final JButton invitationsButton = new JButton("INVITATIONS");
    private final JButton profileButton = new JButton("PROFILE");
    private final JButton syncButton = new JButton("PULL WORLD");
    private final JButton onboardingSignInButton = new JButton("GITHUB SIGN IN");
    private final JButton onboardingInvitationsButton = new JButton("VIEW INVITATIONS");
    private final JButton onboardingCloneButton = new JButton("CLONE SERVER");
    private final JButton saveSettingsButton = new JButton("SAVE SETTINGS");
    private final JCheckBox publicUrlCheck = new JCheckBox("Enable public URL via playit.gg");
    private final JLabel publicUrlValue = DashboardTheme.label("—", TEXT_MUTED, 11, Font.PLAIN);

    private final JTextArea consoleArea = new JTextArea();
    private final JTextArea consolePreview = new JTextArea();
    private final JTextField commandInput = new JTextField();
    private final JTextField settingsNetworkInput = new JTextField();
    private final JTextField settingsPortInput = new JTextField();
    private final JTextField settingsRamInput = new JTextField();
    private final JTextField settingsMaxPlayersInput = new JTextField();
    private final JTextArea activityArea = new JTextArea();
    private final JTextArea connectedPlayersArea = new JTextArea();
    private final JLabel connectedPlayersCount = DashboardTheme.label("0 / 20", TEXT, 12, Font.PLAIN);
    private final JPanel serversList = new JPanel();
    private final List<JButton> importWorldButtons = new ArrayList<>();
    private final List<JButton> pullWorldButtons = new ArrayList<>();

    private Page activePage = Page.OVERVIEW;
    private State state = State.empty(List.of());
    private Instant serverStartedAt;
    private boolean settingsDirty;
    private boolean updatingSettingsFields;

    public MinecraftDashboard(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
        setLayout(new BorderLayout());
        setBackground(APP_BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder());

        add(buildSidebar(), BorderLayout.WEST);
        add(buildWorkspace(), BorderLayout.CENTER);

        configureActions();
        configureSettingsEditor();
        configureUptimeClock();
        showPage(Page.OVERVIEW);
        setState(state);
    }

    public JButton primaryActionButton() {
        return primaryAction;
    }

    public JTextArea consoleArea() {
        return consoleArea;
    }

    public JTextField commandInput() {
        return commandInput;
    }

    public JTextField settingsNetworkInput() { return settingsNetworkInput; }
    public JTextField settingsPortInput() { return settingsPortInput; }
    public JTextField settingsRamInput() { return settingsRamInput; }
    public JTextField settingsMaxPlayersInput() { return settingsMaxPlayersInput; }
    public JButton saveSettingsButton() { return saveSettingsButton; }

    public State state() {
        return state;
    }

    public Page activePage() {
        return activePage;
    }

    public void setState(State state) {
        this.state = Objects.requireNonNull(state, "state");
        updateTopBar();
        updateSidebar();
        updateMetrics();
        updatePlayers();
        updateLifecycle();
        updateServers();
        updateBackups();
        updateNetwork();
        updateSettings();
        updateActionAvailability();
        revalidate();
        repaint();
    }

    public void showPage(Page page) {
        activePage = Objects.requireNonNull(page, "page");
        pageLayout.show(pages, page.name());
        pageTitle.setText(page.title());
        for (Map.Entry<Page, JButton> entry : navigationButtons.entrySet()) {
            styleNavigationButton(entry.getValue(), entry.getKey() == page);
        }
        if (page == Page.CONSOLE) commandInput.requestFocusInWindow();
    }

    public void setPhase(Phase phase, String detail) {
        setState(new State(state.serverLoaded(), phase, detail, state.serverName(), state.serverPath(),
                state.hostAddress(), state.port(), state.ram(), state.networkName(), state.loader(),
                state.connectedPlayers(), state.onlinePlayers(), state.maxPlayers(),
                state.githubSelected(), state.githubAuthenticated(), state.repositoryLinked(),
                state.githubAccount(), state.repository(), state.syncState(), state.lastSync(),
                phase == Phase.ERROR ? detail : "", state.recentServers()));
    }

    public void markServerStarted() {
        serverStartedAt = Instant.now();
    }

    public void markServerStopped() {
        serverStartedAt = null;
        uptimeValue.setText("00:00:00");
    }

    public void appendActivity(String message) {
        if (message == null || message.isBlank()) return;
        String current = activityArea.getText();
        String next = current.isBlank() ? message : current + "\n" + message;
        String[] lines = next.split("\\R");
        if (lines.length > 12) {
            next = String.join("\n", java.util.Arrays.copyOfRange(lines, lines.length - 12, lines.length));
        }
        activityArea.setText(next);
        activityArea.setCaretPosition(activityArea.getDocument().getLength());
    }

    /** Reflects the shared playit state; the checkbox is only refreshed while there are no unsaved edits. */
    public void showPublicUrl(boolean enabled, String address) {
        if(!settingsDirty) {
            updatingSettingsFields = true;
            try {
                publicUrlCheck.setSelected(enabled);
            } finally {
                updatingSettingsFields = false;
            }
        }
        boolean hasAddress = address != null && !address.isBlank();
        publicUrlValue.setText(!enabled
                ? "Off — players join through the P2P network"
                : hasAddress ? "Address: " + address
                             : "Enabled — authorize playit in the browser or start the server to get the address");
        publicUrlValue.setForeground(enabled && hasAddress ? GREEN : TEXT_MUTED);
    }

    public void showSettingsResult(boolean success, String message) {
        settingsStatus.setText(message);
        settingsStatus.setForeground(success ? GREEN : RED);
        if(success) {
            settingsDirty = false;
            saveSettingsButton.setEnabled(false);
        }
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(SIDEBAR_BACKGROUND);
        sidebar.setPreferredSize(new Dimension(230, 0));
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, HAIRLINE));

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBorder(BorderFactory.createEmptyBorder(19, 20, 18, 16));
        JLabel name = DashboardTheme.label("P2P MINECRAFT", TEXT, 14, Font.PLAIN);
        JLabel descriptor = DashboardTheme.label("/ SERVER CONTROL", TEXT_DIM, 10, Font.PLAIN);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        descriptor.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.add(name);
        brand.add(Box.createVerticalStrut(3));
        brand.add(descriptor);
        sidebar.add(brand, BorderLayout.NORTH);

        JPanel navigation = new JPanel();
        navigation.setOpaque(false);
        navigation.setLayout(new BoxLayout(navigation, BoxLayout.Y_AXIS));
        navigation.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel section = DashboardTheme.eyebrow("Workspace");
        section.setBorder(BorderFactory.createEmptyBorder(0, 9, 8, 0));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        navigation.add(section);
        for (Page page : Page.values()) {
            JButton button = new JButton(page.title().toUpperCase(Locale.ROOT));
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.addActionListener(event -> showPage(page));
            navigationButtons.put(page, button);
            navigation.add(button);
            navigation.add(Box.createVerticalStrut(2));
        }
        sidebar.add(navigation, BorderLayout.CENTER);

        JPanel account = new JPanel();
        account.setOpaque(false);
        account.setLayout(new BoxLayout(account, BoxLayout.Y_AXIS));
        account.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, HAIRLINE),
                BorderFactory.createEmptyBorder(14, 20, 16, 12)
        ));
        accountStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        accountName.setAlignmentX(Component.LEFT_ALIGNMENT);
        account.add(accountStatus);
        account.add(Box.createVerticalStrut(5));
        account.add(accountName);
        sidebar.add(account, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel buildWorkspace() {
        JPanel workspace = new JPanel(new BorderLayout());
        workspace.setBackground(APP_BACKGROUND);
        workspace.add(buildTopBar(), BorderLayout.NORTH);

        pages.setBackground(APP_BACKGROUND);
        pages.add(wrapPage(buildOverviewPage()), Page.OVERVIEW.name());
        pages.add(wrapPage(buildServersPage()), Page.SERVERS.name());
        pages.add(wrapPage(buildBackupsPage()), Page.BACKUPS.name());
        pages.add(wrapPage(buildNetworkPage()), Page.NETWORK.name());
        pages.add(buildConsolePage(), Page.CONSOLE.name());
        pages.add(wrapPage(buildSettingsPage()), Page.SETTINGS.name());
        workspace.add(pages, BorderLayout.CENTER);
        return workspace;
    }

    private JPanel buildTopBar() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(APP_BACKGROUND);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, HAIRLINE),
                BorderFactory.createEmptyBorder(14, 20, 15, 20)
        ));

        JPanel context = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        context.setOpaque(false);
        context.setAlignmentX(Component.LEFT_ALIGNMENT);
        phaseSquare.setFont(DashboardTheme.font(Font.PLAIN, 9));
        context.add(phaseSquare);
        context.add(phaseLabel);
        context.add(separatorLabel());
        context.add(contextLabel);

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        pageTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        serverPathLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(context);
        heading.add(Box.createVerticalStrut(8));
        heading.add(pageTitle);
        heading.add(Box.createVerticalStrut(4));
        heading.add(serverPathLabel);
        heading.add(Box.createVerticalStrut(4));
        heading.add(errorBanner);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 9));
        actionsPanel.setOpaque(false);
        DashboardTheme.styleButton(topRefreshButton, DashboardTheme.ButtonKind.QUIET);
        DashboardTheme.styleButton(primaryAction, DashboardTheme.ButtonKind.PRIMARY);
        actionsPanel.add(topRefreshButton);
        actionsPanel.add(primaryAction);

        top.add(heading, BorderLayout.CENTER);
        top.add(actionsPanel, BorderLayout.EAST);
        return top;
    }

    private JPanel buildOverviewPage() {
        overviewContainer.setBackground(APP_BACKGROUND);
        overviewContainer.add(buildOnboardingPage(), "onboarding");
        overviewContainer.add(buildOperationalOverviewPage(), "operational");
        return overviewContainer;
    }

    private JPanel buildOperationalOverviewPage() {
        JPanel page = pagePanel();
        page.add(buildMetricGrid(List.of(
                metric("status", "STATUS", "NO SERVER", "waiting"),
                metric("host", "HOST", "—", "not discovered"),
                metric("players", "PLAYERS", "0 / 20", "live roster"),
                metric("port", "PORT", "—", "local setting"),
                metric("ram", "RAM", "—", "local allocation"),
                metric("network", "NETWORK", "—", "P2P identity"),
                metric("backup", "BACKUP", "—", "GitHub")
        )));
        page.add(Box.createVerticalStrut(12));

        JPanel middle = new JPanel(new GridLayout(1, 3, 12, 0));
        middle.setOpaque(false);
        middle.setAlignmentX(Component.LEFT_ALIGNMENT);
        middle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
        middle.add(buildLifecyclePanel());
        middle.add(buildPlayersPanel());
        middle.add(buildActivityPanel());
        page.add(middle);
        page.add(Box.createVerticalStrut(12));
        page.add(buildConsolePreview());
        return page;
    }

    private JPanel buildOnboardingPage() {
        JPanel page = pagePanel();
        page.add(sectionHeading("WELCOME TO P2P MINECRAFT", "Choose how this computer will join the shared hosting workflow."));

        JPanel primary = sectionPanel();
        primary.setLayout(new BorderLayout(24, 0));
        JPanel primaryCopy = new JPanel();
        primaryCopy.setOpaque(false);
        primaryCopy.setLayout(new BoxLayout(primaryCopy, BoxLayout.Y_AXIS));
        primaryCopy.add(DashboardTheme.eyebrow("01 / SELECT A SERVER"));
        primaryCopy.add(Box.createVerticalStrut(12));
        primaryCopy.add(DashboardTheme.label("Create a new Forge world or open one already installed on this machine.", TEXT, 14, Font.PLAIN));
        primaryCopy.add(Box.createVerticalStrut(7));
        primaryCopy.add(DashboardTheme.label("The dashboard only displays host, port, RAM and backup data after a valid startup folder is selected.", TEXT_MUTED, 11, Font.PLAIN));
        primary.add(primaryCopy, BorderLayout.CENTER);

        JPanel primaryActions = new JPanel();
        primaryActions.setOpaque(false);
        primaryActions.setLayout(new BoxLayout(primaryActions, BoxLayout.Y_AXIS));
        JButton create = actionButton("CREATE FORGE SERVER", DashboardTheme.ButtonKind.PRIMARY, actions::createServer);
        JButton open = actionButton("OPEN EXISTING SERVER", DashboardTheme.ButtonKind.SECONDARY, actions::openServer);
        create.setAlignmentX(Component.RIGHT_ALIGNMENT);
        open.setAlignmentX(Component.RIGHT_ALIGNMENT);
        primaryActions.add(create);
        primaryActions.add(Box.createVerticalStrut(8));
        primaryActions.add(open);
        primary.add(primaryActions, BorderLayout.EAST);
        primary.setAlignmentX(Component.LEFT_ALIGNMENT);
        primary.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        page.add(primary);
        page.add(Box.createVerticalStrut(12));

        JPanel readiness = new JPanel(new GridLayout(1, 4, 0, 0));
        readiness.setOpaque(false);
        readiness.setAlignmentX(Component.LEFT_ALIGNMENT);
        readiness.setMaximumSize(new Dimension(Integer.MAX_VALUE, 105));
        readiness.add(onboardingStat("RUNTIME", onboardingJavaValue, "Required: Java 21+"));
		readiness.add(onboardingStat("GITHUB ACCOUNT", onboardingGitHubValue, "Automatic private backups"));
        readiness.add(onboardingStat("P2P NETWORK", onboardingNetworkValue, "Shared host identity"));
        readiness.add(onboardingStat("LIBRARY", onboardingRecentValue, "Local server folders"));
        page.add(readiness);
        page.add(Box.createVerticalStrut(12));

        JPanel invited = sectionPanel();
        invited.setLayout(new BorderLayout(20, 0));
        JPanel invitedCopy = new JPanel();
        invitedCopy.setOpaque(false);
        invitedCopy.setLayout(new BoxLayout(invitedCopy, BoxLayout.Y_AXIS));
        invitedCopy.add(DashboardTheme.eyebrow("02 / JOIN A SHARED WORLD"));
        invitedCopy.add(Box.createVerticalStrut(10));
        invitedCopy.add(DashboardTheme.label("Already invited by another host? Accept the invitation and clone the private server repository.", TEXT, 13, Font.PLAIN));
        invitedCopy.add(Box.createVerticalStrut(6));
        invitedCopy.add(DashboardTheme.label("The clone receives world data and mods; port, RAM and machine settings remain local.", TEXT_MUTED, 11, Font.PLAIN));
        invited.add(invitedCopy, BorderLayout.CENTER);

        JPanel invitedActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        invitedActions.setOpaque(false);
        DashboardTheme.styleButton(onboardingSignInButton, DashboardTheme.ButtonKind.QUIET);
        DashboardTheme.styleButton(onboardingInvitationsButton, DashboardTheme.ButtonKind.SECONDARY);
        DashboardTheme.styleButton(onboardingCloneButton, DashboardTheme.ButtonKind.PRIMARY);
        onboardingSignInButton.addActionListener(event -> actions.signIntoGitHub());
        onboardingInvitationsButton.addActionListener(event -> actions.showInvitations());
        onboardingCloneButton.addActionListener(event -> actions.cloneInvitedServer());
        invitedActions.add(onboardingSignInButton);
        invitedActions.add(onboardingInvitationsButton);
        invitedActions.add(onboardingCloneButton);
        invited.add(invitedActions, BorderLayout.EAST);
        invited.setAlignmentX(Component.LEFT_ALIGNMENT);
        invited.setMaximumSize(new Dimension(Integer.MAX_VALUE, 145));
        page.add(invited);
        page.add(Box.createVerticalStrut(12));

        JPanel safety = sectionPanel();
        safety.setLayout(new BoxLayout(safety, BoxLayout.Y_AXIS));
        safety.add(DashboardTheme.eyebrow("HOW SHARED HOSTING STAYS SAFE"));
        safety.add(Box.createVerticalStrut(10));
        safety.add(checklistRow("01", "Scan the P2P network and block start when another host is online."));
        safety.add(checklistRow("02", "Pull the latest confirmed GitHub world before Forge starts."));
        safety.add(checklistRow("03", "Wait for Forge to save completely before committing the world."));
		safety.add(checklistRow("04", "Split large backups into verified commits and resume from the last accepted batch."));
        safety.setAlignmentX(Component.LEFT_ALIGNMENT);
        safety.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        page.add(safety);
        page.add(Box.createVerticalGlue());
        return page;
    }

    private JPanel buildServersPage() {
        JPanel page = pagePanel();
        page.add(sectionHeading("SERVER LIBRARY", "Open a known world or provision a clean Forge server."));

        JPanel actionsPanel = horizontalActions();
        actionsPanel.add(actionButton("NEW SERVER", DashboardTheme.ButtonKind.PRIMARY, actions::createServer));
        actionsPanel.add(actionButton("OPEN FOLDER", DashboardTheme.ButtonKind.SECONDARY, actions::openServer));
        actionsPanel.add(importWorldButton(DashboardTheme.ButtonKind.SECONDARY));
        actionsPanel.add(actionButton("CLONE INVITATION", DashboardTheme.ButtonKind.QUIET, actions::cloneInvitedServer));
        page.add(actionsPanel);
        page.add(Box.createVerticalStrut(14));

        serversList.setOpaque(false);
        serversList.setLayout(new BoxLayout(serversList, BoxLayout.Y_AXIS));
        serversList.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(serversList);
        page.add(Box.createVerticalGlue());
        return page;
    }

    private JPanel buildBackupsPage() {
        JPanel page = pagePanel();
        page.add(buildMetricGrid(List.of(
                metric("github", "GITHUB", "OFFLINE", "account"),
                metric("repository", "REPOSITORY", "NOT LINKED", "private"),
                metric("sync", "SYNC STATE", "—", "world protection"),
                metric("lastSync", "LAST SYNC", "—", "confirmed push")
        )));
        page.add(Box.createVerticalStrut(12));

        JPanel repositoryPanel = sectionPanel();
        repositoryPanel.setLayout(new BorderLayout(16, 0));
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(DashboardTheme.eyebrow("Repository connection"));
        copy.add(Box.createVerticalStrut(9));
        copy.add(repositoryValue);
        copy.add(Box.createVerticalStrut(5));
        copy.add(syncDetail);
        repositoryPanel.add(copy, BorderLayout.CENTER);

        JPanel repositoryActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        repositoryActions.setOpaque(false);
        for (JButton button : List.of(signInButton, profileButton, createRepositoryButton, syncButton)) {
            DashboardTheme.styleButton(button, button == createRepositoryButton ? DashboardTheme.ButtonKind.PRIMARY : DashboardTheme.ButtonKind.SECONDARY);
            repositoryActions.add(button);
        }
        repositoryPanel.add(repositoryActions, BorderLayout.EAST);
        repositoryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        repositoryPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 105));
        page.add(repositoryPanel);
        page.add(Box.createVerticalStrut(12));

        JPanel collaboration = sectionPanel();
        collaboration.setLayout(new BorderLayout(16, 0));
        JPanel collaborationCopy = new JPanel();
        collaborationCopy.setOpaque(false);
        collaborationCopy.setLayout(new BoxLayout(collaborationCopy, BoxLayout.Y_AXIS));
        collaborationCopy.add(DashboardTheme.eyebrow("Shared hosting"));
        collaborationCopy.add(Box.createVerticalStrut(8));
        collaborationCopy.add(DashboardTheme.label("Let another GitHub account host the same world safely.", TEXT, 13, Font.PLAIN));
        collaborationCopy.add(Box.createVerticalStrut(4));
        collaborationCopy.add(DashboardTheme.label("Invited hosts pull before start and push only after a clean stop.", TEXT_MUTED, 11, Font.PLAIN));
        collaboration.add(collaborationCopy, BorderLayout.CENTER);

        JPanel collaborationActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        collaborationActions.setOpaque(false);
        DashboardTheme.styleButton(invitationsButton, DashboardTheme.ButtonKind.QUIET);
        DashboardTheme.styleButton(inviteButton, DashboardTheme.ButtonKind.SECONDARY);
        DashboardTheme.styleButton(signOutButton, DashboardTheme.ButtonKind.DANGER);
        collaborationActions.add(invitationsButton);
        collaborationActions.add(inviteButton);
        collaborationActions.add(signOutButton);
        collaboration.add(collaborationActions, BorderLayout.EAST);
        collaboration.setAlignmentX(Component.LEFT_ALIGNMENT);
        collaboration.setMaximumSize(new Dimension(Integer.MAX_VALUE, 105));
        page.add(collaboration);
        page.add(Box.createVerticalGlue());
        return page;
    }

    private JPanel buildNetworkPage() {
        JPanel page = pagePanel();
        page.add(buildMetricGrid(List.of(
                metric("networkState", "DISCOVERY", "STANDBY", "UDP probe"),
                metric("networkHost", "ACTIVE HOST", "—", "P2P address"),
                metric("networkPort", "PORT", "—", "Minecraft Java"),
                metric("networkRole", "LOCAL ROLE", "CLIENT", "host election")
        )));
        page.add(Box.createVerticalStrut(12));

        JPanel address = sectionPanel();
        address.setLayout(new BorderLayout(20, 0));
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(DashboardTheme.eyebrow("Minecraft connection"));
        copy.add(Box.createVerticalStrut(12));
        copy.add(connectionInstruction);
        copy.add(Box.createVerticalStrut(7));
        copy.add(networkDetail);
        address.add(copy, BorderLayout.CENTER);
        address.add(actionButton("SCAN NETWORK", DashboardTheme.ButtonKind.SECONDARY, actions::refreshNetwork), BorderLayout.EAST);
        address.setAlignmentX(Component.LEFT_ALIGNMENT);
        address.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        page.add(address);
        page.add(Box.createVerticalStrut(12));

        JPanel guidance = sectionPanel();
        guidance.setLayout(new BoxLayout(guidance, BoxLayout.Y_AXIS));
        guidance.add(DashboardTheme.eyebrow("Connection checklist"));
        guidance.add(Box.createVerticalStrut(12));
        guidance.add(checklistRow("01", "Connect every player to the same Tailscale, Hamachi or Radmin network."));
        guidance.add(checklistRow("02", "Use the exact network name configured on every host."));
        guidance.add(checklistRow("03", "Allow Java and the selected TCP/UDP port through the firewall."));
        guidance.add(checklistRow("04", "Only start locally when ACTIVE HOST reports no remote server."));
        guidance.setAlignmentX(Component.LEFT_ALIGNMENT);
        guidance.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));
        page.add(guidance);
        page.add(Box.createVerticalGlue());
        return page;
    }

    private JPanel buildConsolePage() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(APP_BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(14, 20, 20, 20));
        panel.add(buildTerminal(consoleArea, true), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildSettingsPage() {
        JPanel page = pagePanel();
        page.add(sectionHeading("LOCAL SERVER SETTINGS", "Edit the selected server here. Changes are locked while any host is using the world."));

        JPanel summary = sectionPanel();
        summary.setLayout(new GridLayout(2, 1, 0, 0));
        summary.add(settingsRow("SERVER", settingsServerValue));
        summary.add(settingsRow("FOLDER", settingsPathValue));
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        page.add(summary);
        page.add(Box.createVerticalStrut(12));

        JPanel editor = sectionPanel();
        editor.setLayout(new GridLayout(2, 2, 14, 12));
        editor.add(settingsEditorField("P2P NETWORK NAME", settingsNetworkInput, "Must match every peer"));
        editor.add(settingsEditorField("SERVER PORT", settingsPortInput, "1–65535"));
        editor.add(settingsEditorField("ALLOCATED RAM", settingsRamInput, "Use 4G or 2048M"));
        editor.add(settingsEditorField("MAX PLAYERS", settingsMaxPlayersInput, "1–1000"));
        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
        editor.setMaximumSize(new Dimension(Integer.MAX_VALUE, 185));
        page.add(editor);
        page.add(Box.createVerticalStrut(12));

        JPanel publicUrl = sectionPanel();
        publicUrl.setLayout(new BoxLayout(publicUrl, BoxLayout.Y_AXIS));
        publicUrl.add(DashboardTheme.eyebrow("PUBLIC URL"));
        publicUrl.add(Box.createVerticalStrut(8));
        publicUrlCheck.setOpaque(false);
        publicUrlCheck.setForeground(TEXT);
        publicUrlCheck.setFocusPainted(false);
        publicUrlCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        publicUrl.add(publicUrlCheck);
        publicUrl.add(Box.createVerticalStrut(4));
        JLabel publicUrlHint = DashboardTheme.label(
                "Friends join with vanilla Minecraft — no VPN, no app, no port forwarding. One-time browser authorization.",
                TEXT_DIM, 10, Font.PLAIN);
        publicUrlHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        publicUrl.add(publicUrlHint);
        publicUrl.add(Box.createVerticalStrut(8));
        publicUrlValue.setAlignmentX(Component.LEFT_ALIGNMENT);
        publicUrl.add(publicUrlValue);
        publicUrl.setAlignmentX(Component.LEFT_ALIGNMENT);
        publicUrl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 125));
        page.add(publicUrl);
        page.add(Box.createVerticalStrut(10));

        JPanel saveRow = new JPanel(new BorderLayout(12, 0));
        saveRow.setOpaque(false);
        settingsStatus.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        DashboardTheme.styleButton(saveSettingsButton, DashboardTheme.ButtonKind.PRIMARY);
        saveRow.add(settingsStatus, BorderLayout.CENTER);
        saveRow.add(saveSettingsButton, BorderLayout.EAST);
        saveRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        page.add(saveRow);
        page.add(Box.createVerticalStrut(14));

        JPanel actionsPanel = horizontalActions();
        actionsPanel.add(importWorldButton(DashboardTheme.ButtonKind.SECONDARY));
        actionsPanel.add(actionButton("OPEN MODS", DashboardTheme.ButtonKind.SECONDARY, actions::openModsFolder));
        actionsPanel.add(actionButton("OPEN FOLDER", DashboardTheme.ButtonKind.SECONDARY, actions::openServerFolder));
        actionsPanel.add(actionButton("GENERAL CONFIG", DashboardTheme.ButtonKind.QUIET, actions::openGeneralSettings));
        page.add(actionsPanel);
        page.add(Box.createVerticalStrut(18));

        JPanel note = sectionPanel();
        note.setLayout(new BoxLayout(note, BoxLayout.Y_AXIS));
        note.add(DashboardTheme.eyebrow("Safety model"));
        note.add(Box.createVerticalStrut(10));
        note.add(DashboardTheme.label("server.properties and user_jvm_args.txt are protected as local-only files.", TEXT, 12, Font.PLAIN));
        note.add(Box.createVerticalStrut(5));
        note.add(DashboardTheme.label("World data, mods and shared server assets remain synchronized through the selected provider.", TEXT_MUTED, 11, Font.PLAIN));
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.setMaximumSize(new Dimension(Integer.MAX_VALUE, 105));
        page.add(note);
        page.add(Box.createVerticalGlue());
        return page;
    }

    private JPanel settingsEditorField(String title, JTextField input, String helper) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel label = DashboardTheme.eyebrow(title);
        JLabel hint = DashboardTheme.label(helper, TEXT_DIM, 10, Font.PLAIN);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        DashboardTheme.styleInput(input);
        panel.add(label);
        panel.add(Box.createVerticalStrut(7));
        panel.add(input);
        panel.add(Box.createVerticalStrut(5));
        panel.add(hint);
        return panel;
    }

    private JPanel buildLifecyclePanel() {
        JPanel panel = sectionPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(DashboardTheme.eyebrow("Server lifecycle"));
        panel.add(Box.createVerticalStrut(12));

        addLifecycleStep(panel, Phase.DISCOVERING, "CHECK HOST", "Ensure no peer is already hosting");
        addLifecycleStep(panel, Phase.SYNCING, "PULL WORLD", "Fetch the last confirmed GitHub state");
        addLifecycleStep(panel, Phase.STARTING, "START FORGE", "Launch the platform startup script");
        addLifecycleStep(panel, Phase.ONLINE, "READY", "Accept players and stream the console");
        panel.add(Box.createVerticalGlue());

        JPanel quickActions = new JPanel(new GridLayout(2, 2, 6, 6));
        quickActions.setOpaque(false);
        quickActions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        quickActions.add(importWorldButton(DashboardTheme.ButtonKind.QUIET));
        quickActions.add(actionButton("MODS", DashboardTheme.ButtonKind.QUIET, actions::openModsFolder));
        quickActions.add(actionButton("SETTINGS", DashboardTheme.ButtonKind.QUIET, actions::openServerSettings));
        quickActions.add(pullWorldButton(DashboardTheme.ButtonKind.QUIET));
        panel.add(quickActions);
        return panel;
    }

    private JPanel buildPlayersPanel() {
        JPanel panel = sectionPanel();
        panel.setLayout(new BorderLayout());

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(DashboardTheme.eyebrow("Connected players"), BorderLayout.WEST);
        heading.add(connectedPlayersCount, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);

        connectedPlayersArea.setEditable(false);
        connectedPlayersArea.setLineWrap(true);
        connectedPlayersArea.setWrapStyleWord(true);
        connectedPlayersArea.setFont(DashboardTheme.font(Font.PLAIN, 11));
        connectedPlayersArea.setForeground(TEXT_MUTED);
        connectedPlayersArea.setBackground(PANEL_BACKGROUND);
        connectedPlayersArea.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        connectedPlayersArea.setText("No players connected.");
        JScrollPane rosterScroll = new JScrollPane(connectedPlayersArea);
        rosterScroll.setBackground(PANEL_BACKGROUND);
        rosterScroll.getViewport().setBackground(PANEL_BACKGROUND);
        rosterScroll.setBorder(BorderFactory.createEmptyBorder());
        rosterScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(rosterScroll, BorderLayout.CENTER);

        JLabel refresh = DashboardTheme.label("LIVE · REFRESHES EVERY 10S", TEXT_DIM, 9, Font.PLAIN);
        panel.add(refresh, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildActivityPanel() {
        JPanel panel = sectionPanel();
        panel.setLayout(new BorderLayout());
        panel.add(DashboardTheme.eyebrow("Recent activity"), BorderLayout.NORTH);
        activityArea.setEditable(false);
        activityArea.setLineWrap(true);
        activityArea.setWrapStyleWord(true);
        activityArea.setFont(DashboardTheme.font(Font.PLAIN, 11));
        activityArea.setForeground(TEXT_MUTED);
        activityArea.setBackground(PANEL_BACKGROUND);
        activityArea.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        activityArea.setText("Waiting for server activity…");
        panel.add(activityArea, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(DashboardTheme.eyebrow("UPTIME"), BorderLayout.WEST);
        footer.add(uptimeValue, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildConsolePreview() {
        JPanel panel = sectionPanel();
        panel.setLayout(new BorderLayout());
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(DashboardTheme.eyebrow("Minecraft console"), BorderLayout.WEST);
        heading.add(actionButton("OPEN CONSOLE", DashboardTheme.ButtonKind.QUIET, () -> showPage(Page.CONSOLE)), BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);

        configureConsoleArea(consolePreview);
        consolePreview.setDocument(consoleArea.getDocument());
        JScrollPane scroll = new JScrollPane(consolePreview);
        scroll.setBackground(PANEL_BACKGROUND);
        scroll.getViewport().setBackground(PANEL_BACKGROUND);
        scroll.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        scroll.setPreferredSize(new Dimension(0, 155));
        panel.add(scroll, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 205));
        return panel;
    }

    private JPanel buildTerminal(JTextArea area, boolean includeInput) {
        JPanel terminal = new JPanel(new BorderLayout());
        terminal.setBackground(PANEL_BACKGROUND);
        terminal.setBorder(DashboardTheme.sectionBorder());

        JPanel heading = new JPanel(new BorderLayout());
        heading.setBackground(PANEL_BACKGROUND);
        heading.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, HAIRLINE),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)
        ));
        heading.add(DashboardTheme.eyebrow("LIVE OUTPUT"), BorderLayout.WEST);
        heading.add(DashboardTheme.label("AUTOSCROLL", TEXT_DIM, 10, Font.PLAIN), BorderLayout.EAST);
        terminal.add(heading, BorderLayout.NORTH);

        configureConsoleArea(area);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBackground(PANEL_BACKGROUND);
        scroll.getViewport().setBackground(PANEL_BACKGROUND);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        terminal.add(scroll, BorderLayout.CENTER);

        if (includeInput) {
            JPanel command = new JPanel(new BorderLayout(8, 0));
            command.setBackground(PANEL_BACKGROUND);
            command.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, HAIRLINE),
                    BorderFactory.createEmptyBorder(9, 12, 9, 12)
            ));
            JLabel prompt = DashboardTheme.label(">", GREEN, 13, Font.PLAIN);
            DashboardTheme.styleInput(commandInput);
            commandInput.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            commandInput.setToolTipText("Send a Minecraft server command");
            command.add(prompt, BorderLayout.WEST);
            command.add(commandInput, BorderLayout.CENTER);
            terminal.add(command, BorderLayout.SOUTH);
        }
        return terminal;
    }

    private void configureActions() {
        primaryAction.addActionListener(event -> actions.toggleServer());
        topRefreshButton.addActionListener(event -> actions.refreshNetwork());
        createRepositoryButton.addActionListener(event -> actions.createRepository());
        signInButton.addActionListener(event -> actions.signIntoGitHub());
        signOutButton.addActionListener(event -> actions.signOutOfGitHub());
        profileButton.addActionListener(event -> actions.showGitHubProfile());
        invitationsButton.addActionListener(event -> actions.showInvitations());
        inviteButton.addActionListener(event -> actions.inviteHost());
        syncButton.addActionListener(event -> actions.syncNow());
        commandInput.addActionListener(event -> {
            String command = commandInput.getText().trim();
            if (!command.isBlank()) actions.sendCommand(command);
            commandInput.setText("");
        });
    }

    private void configureSettingsEditor() {
        DocumentListener dirtyListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { markSettingsEdited(); }
            @Override public void removeUpdate(DocumentEvent event) { markSettingsEdited(); }
            @Override public void changedUpdate(DocumentEvent event) { markSettingsEdited(); }
        };
        for(JTextField field : List.of(settingsNetworkInput, settingsPortInput, settingsRamInput, settingsMaxPlayersInput)) {
            field.getDocument().addDocumentListener(dirtyListener);
        }
        publicUrlCheck.addItemListener(event -> markSettingsEdited());
        saveSettingsButton.addActionListener(event -> submitSettings());
    }

    private void markSettingsEdited() {
        if(updatingSettingsFields) return;
        settingsDirty = true;
        settingsStatus.setText("Unsaved local changes");
        settingsStatus.setForeground(AMBER);
        boolean editable = state.serverLoaded() && !state.phase().isBusy()
                && state.phase() != Phase.ONLINE && state.phase() != Phase.REMOTE_HOST;
        saveSettingsButton.setEnabled(editable);
    }

    private void submitSettings() {
        try {
            String network = settingsNetworkInput.getText().trim();
            if(network.isBlank()) throw new IllegalArgumentException("Network name cannot be empty.");
            int port = parseRange(settingsPortInput.getText(), "Port", 1, 65_535);
            String ram = settingsRamInput.getText().trim().toUpperCase(Locale.ROOT);
            if(!ram.matches("[1-9][0-9]*[GM]")) throw new IllegalArgumentException("RAM must use a value such as 4G or 2048M.");
            int maxPlayers = parseRange(settingsMaxPlayersInput.getText(), "Max players", 1, 1_000);
            settingsStatus.setText("Saving local settings…");
            settingsStatus.setForeground(AMBER);
            actions.saveServerSettings(new SettingsDraft(network, port, ram, maxPlayers, publicUrlCheck.isSelected()));
        } catch(IllegalArgumentException invalid) {
            showSettingsResult(false, invalid.getMessage());
        }
    }

    private static int parseRange(String value, String label, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if(parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch(NumberFormatException invalid) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    private void configureUptimeClock() {
        Timer timer = new Timer(1000, event -> {
            if (serverStartedAt == null) return;
            Duration uptime = Duration.between(serverStartedAt, Instant.now());
            long hours = uptime.toHours();
            long minutes = uptime.toMinutesPart();
            long seconds = uptime.toSecondsPart();
            uptimeValue.setText("%02d:%02d:%02d".formatted(hours, minutes, seconds));
        });
        timer.setRepeats(true);
        timer.start();
    }

    private void updateTopBar() {
        phaseSquare.setForeground(state.phase().color());
        phaseLabel.setText(state.phase().label());
        phaseLabel.setForeground(state.phase().color());
        contextLabel.setText(state.serverLoaded() ? state.loader().toUpperCase(Locale.ROOT) + " SERVER CONTROL" : "P2P SERVER CONTROL");
        serverPathLabel.setText(state.serverPath());
        errorBanner.setText(state.errorMessage());
        errorBanner.setVisible(!state.errorMessage().isBlank());

        primaryAction.setText(state.phase() == Phase.ONLINE ? "STOP SERVER" : state.phase().isBusy() ? state.phase().label() : "START SERVER");
        DashboardTheme.styleButton(primaryAction, state.phase() == Phase.ONLINE ? DashboardTheme.ButtonKind.DANGER : DashboardTheme.ButtonKind.PRIMARY);
    }

    private void updateSidebar() {
        accountStatus.setText(state.githubAuthenticated() ? "■  GITHUB ONLINE" : "■  GITHUB OFFLINE");
        accountStatus.setForeground(state.githubAuthenticated() ? GREEN : TEXT_MUTED);
        accountName.setText(state.githubAccount().toUpperCase(Locale.ROOT));
    }

    private void updateMetrics() {
        updateMetric("status", state.phase().label(), state.phaseDetail(), state.phase().color());
        updateMetric("host", state.hostAddress(), state.phase() == Phase.REMOTE_HOST ? "remote peer" : "discovered address", state.phase() == Phase.REMOTE_HOST ? CYAN : TEXT);
        updateMetric("players", state.onlinePlayers() + " / " + state.maxPlayers(), "live roster",
                state.onlinePlayers() > 0 ? GREEN : TEXT);
        updateMetric("port", state.port(), "local setting", TEXT);
        updateMetric("ram", state.ram(), "local allocation", TEXT);
        updateMetric("network", state.networkName(), "P2P identity", TEXT);
        String backupValue = state.repositoryLinked() && ("UP TO DATE".equalsIgnoreCase(state.syncState()) || "READY".equalsIgnoreCase(state.syncState()))
                ? "SYNCED" : state.syncState();
        updateMetric("backup", backupValue, state.lastSync(), state.repositoryLinked() ? syncColor() : TEXT_MUTED);

        updateMetric("github", state.githubAuthenticated() ? "ONLINE" : "OFFLINE", state.githubAccount(), state.githubAuthenticated() ? GREEN : TEXT_MUTED);
        updateMetric("repository", state.repositoryLinked() ? "LINKED" : "NOT LINKED", state.repository(), state.repositoryLinked() ? GREEN : TEXT_MUTED);
        updateMetric("sync", state.syncState(), "world protection", syncColor());
        updateMetric("lastSync", state.lastSync(), "confirmed push", TEXT);

        updateMetric("networkState", state.phase() == Phase.DISCOVERING ? "SCANNING" : "READY", state.networkName(), state.phase() == Phase.DISCOVERING ? AMBER : GREEN);
        updateMetric("networkHost", state.hostAddress(), state.phase() == Phase.REMOTE_HOST ? "remote peer" : "local / none", state.phase() == Phase.REMOTE_HOST ? CYAN : TEXT);
        updateMetric("networkPort", state.port(), "Minecraft Java", TEXT);
        updateMetric("networkRole", state.phase() == Phase.ONLINE ? "HOST" : "CLIENT", "host election", state.phase() == Phase.ONLINE ? GREEN : TEXT);
    }

    private void updatePlayers() {
        connectedPlayersCount.setText(state.onlinePlayers() + " / " + state.maxPlayers());
        connectedPlayersCount.setForeground(state.onlinePlayers() > 0 ? GREEN : TEXT);
        if (state.phase() == Phase.REMOTE_HOST && state.connectedPlayers().isEmpty()) {
            connectedPlayersArea.setText(state.onlinePlayers() > 0
                    ? "The active host reports players but did not publish their names."
                    : "No players connected to the active host.");
        } else if (state.connectedPlayers().isEmpty()) {
            connectedPlayersArea.setText(state.phase() == Phase.ONLINE
                    ? "No players connected."
                    : "Start this server to query its roster.");
        } else {
            connectedPlayersArea.setText(state.connectedPlayers().stream()
                    .map(player -> "■  " + player)
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
    }

    private void updateLifecycle() {
        for (Map.Entry<Phase, JLabel> entry : lifecycleSteps.entrySet()) {
            boolean active = entry.getKey() == state.phase();
            entry.getValue().setForeground(active ? state.phase().color() : TEXT_DIM);
        }
    }

    private void updateServers() {
        serversList.removeAll();
        List<ServerEntry> entries = new ArrayList<>(state.recentServers());
        if (entries.isEmpty()) {
            JPanel empty = sectionPanel();
            empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
            empty.add(DashboardTheme.eyebrow("NO SERVERS YET"));
            empty.add(Box.createVerticalStrut(10));
            empty.add(DashboardTheme.label("Create a Forge server or open an existing server folder.", TEXT, 13, Font.PLAIN));
            empty.add(Box.createVerticalStrut(5));
            empty.add(DashboardTheme.label("Known servers will appear here with their local and sync state.", TEXT_MUTED, 11, Font.PLAIN));
            empty.setMaximumSize(new Dimension(Integer.MAX_VALUE, 115));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            serversList.add(empty);
        } else {
            for (ServerEntry entry : entries) {
                serversList.add(serverRow(entry));
                serversList.add(Box.createVerticalStrut(7));
            }
        }
        serversList.revalidate();
        serversList.repaint();
    }

	private void updateBackups() {
		repositoryValue.setText(state.repository());
		if (!state.githubAuthenticated()) {
			syncDetail.setText("Sign in with a classic PAT using the repo scope.");
		} else if (!state.repositoryLinked()) {
			syncDetail.setText("Private setup starts automatically while the server is offline; retry here only if it failed.");
		} else if ("FAILED".equals(state.syncState())) {
			syncDetail.setText("The local world is safe, but GitHub did not confirm every batch. Retry to resume the upload.");
		} else {
			syncDetail.setText("Pull-before-start and batched, verified push-after-stop are active.");
		}
    }

    private void updateNetwork() {
        if (state.phase() == Phase.ONLINE || state.phase() == Phase.REMOTE_HOST) {
            connectionInstruction.setText(state.hostAddress() + ("25565".equals(state.port()) ? "" : ":" + state.port()));
            connectionInstruction.setForeground(state.phase() == Phase.ONLINE ? GREEN : CYAN);
            networkDetail.setText(state.phase() == Phase.ONLINE
                    ? "Share this address with players connected to the same P2P VPN."
                    : "A peer is already hosting this world; local start remains disabled.");
        } else {
            connectionInstruction.setText("Server address unavailable");
            connectionInstruction.setForeground(TEXT_MUTED);
            networkDetail.setText("Start the server or scan for another host on network " + state.networkName() + ".");
        }
    }

    private void updateSettings() {
        settingsServerValue.setText(state.serverName());
        settingsPathValue.setText(state.serverPath());
        if(settingsDirty) return;
        updatingSettingsFields = true;
        try {
            settingsNetworkInput.setText("—".equals(state.networkName()) ? "" : state.networkName());
            settingsPortInput.setText("—".equals(state.port()) ? "" : state.port());
            settingsRamInput.setText("—".equals(state.ram()) ? "" : state.ram());
            settingsMaxPlayersInput.setText(state.serverLoaded() ? Integer.toString(state.maxPlayers()) : "");
        } finally {
            updatingSettingsFields = false;
        }
    }

    private void updateActionAvailability() {
        boolean loaded = state.serverLoaded();
        boolean busy = state.phase().isBusy();
        boolean canStartOrStop = loaded && !busy && state.phase() != Phase.REMOTE_HOST;
        boolean canImport = loaded && !busy && state.phase() != Phase.ONLINE && state.phase() != Phase.REMOTE_HOST;
		boolean backupFailed = "FAILED".equals(state.syncState());
		boolean canPull = canImport && state.githubAuthenticated() && state.repositoryLinked() && !backupFailed;
        boolean canEditSettings = canImport;
        primaryAction.setVisible(loaded);
        primaryAction.setEnabled(canStartOrStop);
        topRefreshButton.setVisible(loaded);
        topRefreshButton.setEnabled(!busy);
        commandInput.setEnabled(state.phase() == Phase.ONLINE);

        signInButton.setVisible(!state.githubAuthenticated());
        signOutButton.setVisible(state.githubAuthenticated());
        profileButton.setVisible(state.githubAuthenticated());
        invitationsButton.setVisible(state.githubAuthenticated());
		inviteButton.setVisible(state.githubAuthenticated() && state.repositoryLinked() && !backupFailed);
		createRepositoryButton.setVisible(loaded && state.githubAuthenticated()
				&& (!state.repositoryLinked() || backupFailed));
		createRepositoryButton.setEnabled(canImport);
		syncButton.setVisible(loaded && state.githubAuthenticated() && state.repositoryLinked() && !backupFailed);
        syncButton.setEnabled(canPull);
        for (JButton importButton : importWorldButtons) importButton.setEnabled(canImport);
        for (JButton pullButton : pullWorldButtons) pullButton.setEnabled(canPull);
        for(JTextField field : List.of(settingsNetworkInput, settingsPortInput, settingsRamInput, settingsMaxPlayersInput)) {
            field.setEnabled(canEditSettings);
        }
        publicUrlCheck.setEnabled(canEditSettings);
        saveSettingsButton.setEnabled(canEditSettings && settingsDirty);
        if(!loaded) {
            settingsStatus.setText("Open a server to edit its local settings.");
            settingsStatus.setForeground(TEXT_MUTED);
        } else if(!canEditSettings) {
            settingsStatus.setText("Settings are locked while the world is active.");
            settingsStatus.setForeground(AMBER);
        } else if(!settingsDirty) {
            settingsStatus.setText("Changes apply on the next server start.");
            settingsStatus.setForeground(TEXT_MUTED);
        }

        overviewLayout.show(overviewContainer, loaded ? "operational" : "onboarding");
        onboardingJavaValue.setText("JAVA " + Runtime.version().feature());
        onboardingJavaValue.setForeground(Runtime.version().feature() >= 21 ? GREEN : RED);
        onboardingGitHubValue.setText(state.githubAuthenticated() ? state.githubAccount().toUpperCase(Locale.ROOT) : "NOT CONNECTED");
        onboardingGitHubValue.setForeground(state.githubAuthenticated() ? GREEN : TEXT_MUTED);
        onboardingSignInButton.setVisible(!state.githubAuthenticated());
        onboardingInvitationsButton.setVisible(state.githubAuthenticated());
        onboardingCloneButton.setVisible(state.githubAuthenticated());
        onboardingNetworkValue.setText(state.networkName());
        onboardingRecentValue.setText(state.recentServers().size() + (state.recentServers().size() == 1 ? " KNOWN SERVER" : " KNOWN SERVERS"));
    }

    private Color syncColor() {
        String sync = state.syncState().toUpperCase(Locale.ROOT);
        if (sync.contains("ERROR") || sync.contains("FAILED") || sync.contains("CONFLICT")) return RED;
        if (sync.contains("SYNC") || sync.contains("PULL") || sync.contains("PUSH")) return AMBER;
        if (state.repositoryLinked()) return GREEN;
        return TEXT_MUTED;
    }

    private JPanel pagePanel() {
        JPanel panel = new DashboardPage();
        panel.setBackground(APP_BACKGROUND);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 20, 20, 20));
        return panel;
    }

    private JComponent wrapPage(JPanel page) {
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBackground(APP_BACKGROUND);
        scroll.getViewport().setBackground(APP_BACKGROUND);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JPanel onboardingStat(String label, JLabel value, String detail) {
        JPanel panel = sectionPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel labelComponent = DashboardTheme.eyebrow(label);
        JLabel detailComponent = DashboardTheme.label(detail, TEXT_DIM, 10, Font.PLAIN);
        labelComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(labelComponent);
        panel.add(Box.createVerticalStrut(9));
        panel.add(value);
        panel.add(Box.createVerticalGlue());
        panel.add(detailComponent);
        return panel;
    }

    private JPanel sectionPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(PANEL_BACKGROUND);
        panel.setBorder(DashboardTheme.paddedSectionBorder(14, 15, 14, 15));
        return panel;
    }

    private JPanel sectionHeading(String title, String description) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel titleLabel = DashboardTheme.eyebrow(title);
        JLabel descriptionLabel = DashboardTheme.label(description, TEXT_MUTED, 11, Font.PLAIN);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(descriptionLabel);
        panel.add(Box.createVerticalStrut(12));
        return panel;
    }

    private JPanel buildMetricGrid(List<MetricCard> cards) {
        JPanel grid = new JPanel(new GridLayout(1, cards.size(), 0, 0));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));
        for (MetricCard card : cards) grid.add(card);
        return grid;
    }

    private MetricCard metric(String key, String label, String value, String detail) {
        MetricCard card = new MetricCard(label, value, detail);
        metrics.put(key, card);
        return card;
    }

    private void updateMetric(String key, String value, String detail, Color valueColor) {
        MetricCard card = metrics.get(key);
        if (card != null) card.setValue(value, detail, valueColor);
    }

    private void addLifecycleStep(JPanel parent, Phase phase, String title, String detail) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 33));
        JLabel square = DashboardTheme.label("■", TEXT_DIM, 8, Font.PLAIN);
        lifecycleSteps.put(phase, square);
        row.add(square, BorderLayout.WEST);

        JPanel copy = new JPanel(new BorderLayout());
        copy.setOpaque(false);
        copy.add(DashboardTheme.label(title, TEXT, 11, Font.PLAIN), BorderLayout.WEST);
        copy.add(DashboardTheme.label(detail, TEXT_DIM, 10, Font.PLAIN), BorderLayout.EAST);
        row.add(copy, BorderLayout.CENTER);
        parent.add(row);
        parent.add(Box.createVerticalStrut(4));
    }

    private JPanel horizontalActions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return panel;
    }

    private JButton actionButton(String text, DashboardTheme.ButtonKind kind, Runnable action) {
        JButton button = new JButton(text);
        DashboardTheme.styleButton(button, kind);
        button.addActionListener(event -> action.run());
        return button;
    }

    private JButton importWorldButton(DashboardTheme.ButtonKind kind) {
        JButton button = actionButton("IMPORT WORLD", kind, actions::importWorld);
        importWorldButtons.add(button);
        return button;
    }

    private JButton pullWorldButton(DashboardTheme.ButtonKind kind) {
        JButton button = actionButton("PULL WORLD", kind, actions::syncNow);
        pullWorldButtons.add(button);
        return button;
    }

    private JPanel checklistRow(String index, String text) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        row.add(DashboardTheme.label(index, TEXT_DIM, 10, Font.PLAIN), BorderLayout.WEST);
        row.add(DashboardTheme.label(text, TEXT_MUTED, 11, Font.PLAIN), BorderLayout.CENTER);
        return row;
    }

    private JPanel settingsRow(String label, JComponent value) {
        JPanel row = new JPanel(new BorderLayout(20, 0));
        row.setBackground(PANEL_BACKGROUND);
        row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, HAIRLINE));
        row.add(DashboardTheme.eyebrow(label), BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private JLabel settingsValueLabel(String value, String metricKey) {
        JLabel label = DashboardTheme.label(value, TEXT, 12, Font.PLAIN);
        MetricCard adapter = new MetricCard("", value, "");
        adapter.valueLabel = label;
        metrics.put(metricKey, adapter);
        return label;
    }

    private JPanel serverRow(ServerEntry entry) {
        JPanel row = sectionPanel();
        row.setLayout(new BorderLayout(14, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (entry.selected()) row.setBackground(ACTIVE_BACKGROUND);

        JLabel status = DashboardTheme.label("■", entry.selected() ? GREEN : TEXT_DIM, 8, Font.PLAIN);
        row.add(status, BorderLayout.WEST);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(DashboardTheme.label(entry.name(), TEXT, 13, Font.PLAIN));
        copy.add(Box.createVerticalStrut(4));
        copy.add(DashboardTheme.label(entry.detail() + "  ·  " + entry.path(), TEXT_MUTED, 10, Font.PLAIN));
        row.add(copy, BorderLayout.CENTER);

        JButton open = actionButton(entry.selected() ? "CURRENT" : "OPEN", entry.selected() ? DashboardTheme.ButtonKind.QUIET : DashboardTheme.ButtonKind.SECONDARY,
                () -> actions.selectServer(entry.path()));
        open.setEnabled(!entry.selected());
        row.add(open, BorderLayout.EAST);
        return row;
    }

    private JLabel separatorLabel() {
        return DashboardTheme.label("/", TEXT_DIM, 10, Font.PLAIN);
    }

    private void styleNavigationButton(JButton button, boolean active) {
        button.setFont(DashboardTheme.font(Font.PLAIN, 11));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(false);
        button.setBackground(active ? ACTIVE_BACKGROUND : SIDEBAR_BACKGROUND);
        button.setForeground(active ? TEXT : TEXT_MUTED);
        button.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
    }

    private void configureConsoleArea(JTextArea area) {
        area.setEditable(false);
        area.setFont(DashboardTheme.font(Font.PLAIN, 11));
        area.setForeground(TEXT_MUTED);
        area.setBackground(PANEL_BACKGROUND);
        area.setCaretColor(GREEN);
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        area.setLineWrap(false);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class MetricCard extends JPanel {
        private JLabel valueLabel;
        private final JLabel detailLabel;
        private String fullValue;

        private MetricCard(String label, String value, String detail) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(PANEL_BACKGROUND);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 1, 1, 0, HAIRLINE),
                    BorderFactory.createEmptyBorder(12, 13, 11, 13)
            ));
            JLabel labelComponent = DashboardTheme.eyebrow(label);
            valueLabel = DashboardTheme.label(value, TEXT, 24, Font.PLAIN);
            fullValue = value;
            detailLabel = DashboardTheme.label(detail, TEXT_DIM, 10, Font.PLAIN);
            labelComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
            valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(labelComponent);
            add(Box.createVerticalStrut(9));
            add(valueLabel);
            add(Box.createVerticalGlue());
            add(detailLabel);
            setPreferredSize(new Dimension(150, 112));
            setMinimumSize(new Dimension(105, 100));
        }

        private void setValue(String value, String detail, Color color) {
            fullValue = valueOr(value, "—");
            valueLabel.setText(fullValue);
            valueLabel.setForeground(color == null ? TEXT : color);
            detailLabel.setText(valueOr(detail, "—"));
            fitValueFont();
        }

        @Override
        public void doLayout() {
            super.doLayout();
            fitValueFont();
        }

        private void fitValueFont() {
            if(valueLabel == null || fullValue == null || getWidth() <= 0) return;
            int availableWidth = Math.max(40, getWidth() - 28);
            int size = 24;
            while(size > 13 && valueLabel.getFontMetrics(DashboardTheme.font(Font.PLAIN, size)).stringWidth(fullValue) > availableWidth) {
                size--;
            }
            valueLabel.setFont(DashboardTheme.font(Font.PLAIN, size));
        }
    }

    /** A vertical Swing page whose content always follows the viewport width. */
    private static final class DashboardPage extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(48, visibleRect.height - 48);
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
