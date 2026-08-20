package playit;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import gg.playit.api.ApiClient;
import gg.playit.api.ApiClientException;
import gg.playit.api.model.ApiSuccess;
import gg.playit.api.model.ApiSuccessNoFail;
import gg.playit.api.model.enums.ClaimAgentType;
import gg.playit.api.model.enums.ClaimSetupResponse;
import gg.playit.api.model.enums.PlayitNetwork;
import gg.playit.api.model.enums.TunnelType;
import gg.playit.api.model.request.AccountTunnelOriginCreate;
import gg.playit.api.model.request.AgentOrigin;
import gg.playit.api.model.request.AgentVersion;
import gg.playit.api.model.request.CreateTunnelEndpoint;
import gg.playit.api.model.request.ReqClaimExchange;
import gg.playit.api.model.request.ReqClaimSetup;
import gg.playit.api.model.request.ReqTunnelsCreateV1;
import gg.playit.api.model.request.TunnelProtocol;
import gg.playit.api.model.request.UseAllocRegion;
import gg.playit.api.model.response.AccountTunnelV1;
import gg.playit.api.model.response.AccountTunnelsV1;
import gg.playit.api.model.response.AgentRunDataV1;
import gg.playit.api.model.response.AgentSecretKey;
import gg.playit.api.model.response.AgentTunnelConfig;
import gg.playit.api.model.response.ConnectAddress;
import gg.playit.control.PlayitControlChannel;
import gg.playit.messages.ControlFeedReader;

/**
 * Optional public URL for the hosted server through playit.gg, fully managed
 * by the app: one-time browser claim, tunnel reuse by fixed address, and a
 * control-channel worker whose lifecycle follows the Minecraft server.
 * Modeled on the official plugin's PlayitManager, minus every Bukkit tie.
 */
public final class PlayitTunnel {

	/** Same registered agent version as the official Minecraft plugin protocol. */
	private static final AgentVersion AGENT_VERSION =
			new AgentVersion("f4e73f52-f35c-4f18-9ab2-3aaa5c4488c1", 0, 2, 0);
	private static final String AGENT_VERSION_STRING = "0.2.0";

	public enum Phase { OFF, CONNECTING, ONLINE, ERROR, INVALID_AUTH }

	public record ClaimOutcome(String secretKey, String error) {
		public boolean ok() { return secretKey != null; }
	}

	private final String secretKey;
	private final int minecraftPort;
	private final Consumer<String> activity;

	private volatile Phase phase = Phase.OFF;
	private volatile String publicAddress;
	private volatile boolean running;
	private Thread worker;

	private final Object connectionsSync = new Object();
	private final Set<String> activeConnections = new HashSet<>();

	public PlayitTunnel(String secretKey, int minecraftPort, Consumer<String> activity) {
		this.secretKey = secretKey;
		this.minecraftPort = minecraftPort;
		this.activity = activity == null ? message -> {} : activity;
	}

	public Phase phase() { return phase; }
	public String publicAddress() { return publicAddress; }

	public static String claimUrl(String claimCode) {
		return "https://playit.gg/claim/" + claimCode;
	}

	public static String newClaimCode() {
		byte[] random = new byte[8];
		new SecureRandom().nextBytes(random);
		StringBuilder hex = new StringBuilder(16);
		for(byte b : random) hex.append(String.format("%02x", b));
		return hex.toString();
	}

	/**
	 * Blocking one-time claim: polls until the user approves the claim code in
	 * the browser (account or guest), then exchanges it for the agent secret.
	 * Run it from a background thread, never from the EDT.
	 */
	public static ClaimOutcome claimAgent(String claimCode, long timeoutSeconds) {
		ApiClient openClient = new ApiClient(null);
		long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;

		while(System.currentTimeMillis() < deadline) {
			try {
				var setupResult = openClient.claimSetup(
						new ReqClaimSetup(claimCode, ClaimAgentType.SelfManaged, AGENT_VERSION_STRING));
				if(setupResult instanceof ApiSuccess<ClaimSetupResponse, ?> success) {
					switch(success.data()) {
						case UserAccepted -> {
							var exchange = openClient.claimExchange(new ReqClaimExchange(claimCode));
							if(exchange instanceof ApiSuccess<AgentSecretKey, ?> secret) {
								return new ClaimOutcome(secret.data().secret_key(), null);
							}
							return new ClaimOutcome(null, "playit claim exchange failed: " + exchange);
						}
						case UserRejected -> {
							return new ClaimOutcome(null, "The claim was rejected on playit.gg");
						}
						default -> { /* WaitingForUserVisit / WaitingForUser: seguir sondeando */ }
					}
				}
			} catch(ApiClientException transientFailure) {
				// Red inestable durante el sondeo: se reintenta hasta el deadline
			}

			try {
				Thread.sleep(3000);
			} catch(InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return new ClaimOutcome(null, "Claim wait interrupted");
			}
		}
		return new ClaimOutcome(null, "Nobody approved the claim on playit.gg (timed out)");
	}

	/** Finds the existing Minecraft Java tunnel or creates one; returns its fixed address. */
	public static String ensureTunnel(String secretKey) throws IOException {
		ApiClient api = new ApiClient(secretKey);

		String agentId;
		try {
			var rundata = api.v1AgentsRundata();
			if(!(rundata instanceof ApiSuccessNoFail<AgentRunDataV1> success)) {
				throw new IOException("playit agent rundata failed: " + rundata);
			}
			agentId = success.data().agent_id();
		} catch(ApiClientException failure) {
			if(failure.getStatusCode() == 401 || failure.getStatusCode() == 400) {
				throw new IOException("invalid authentication: playit rejected the stored secret", failure);
			}
			throw new IOException("playit API unavailable: " + failure.getMessage(), failure);
		}

		try {
			String existing = findMinecraftTunnelAddress(api);
			if(existing != null) return existing;

			api.v1TunnelsCreate(new ReqTunnelsCreateV1(
					"P2PMSS",
					new TunnelProtocol.TunnelTypeDetail(TunnelType.MinecraftJava),
					new AccountTunnelOriginCreate.Agent(new AgentOrigin(agentId, new AgentTunnelConfig())),
					new CreateTunnelEndpoint.Region(new UseAllocRegion(PlayitNetwork.Global, null)),
					true,
					null));

			for(int attempt = 0; attempt < 10; attempt++) {
				Thread.sleep(1000);
				String created = findMinecraftTunnelAddress(api);
				if(created != null) return created;
			}
		} catch(ApiClientException failure) {
			throw new IOException("playit tunnel setup failed: " + failure.getMessage(), failure);
		} catch(InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while waiting for the playit tunnel", interrupted);
		}
		throw new IOException("playit did not allocate a tunnel address in time");
	}

	private static String findMinecraftTunnelAddress(ApiClient api) throws ApiClientException {
		var tunnelsResult = api.v1TunnelsList();
		if(!(tunnelsResult instanceof ApiSuccessNoFail<AccountTunnelsV1> success)) return null;
		if(success.data().tunnels() == null) return null;
		for(AccountTunnelV1 tunnel : success.data().tunnels()) {
			if(tunnel.tunnel_type() != TunnelType.MinecraftJava) continue;
			String address = displayAddress(tunnel);
			if(address != null) return address;
		}
		return null;
	}

	private static String displayAddress(AccountTunnelV1 tunnel) {
		var addresses = tunnel.connect_addresses();
		if(addresses == null || addresses.isEmpty()) return null;
		var first = addresses.get(0);
		if(first instanceof ConnectAddress.Ip4 ip4) return ip4.value().address() + ":" + ip4.value().default_port();
		if(first instanceof ConnectAddress.Ip6 ip6) return ip6.value().address() + ":" + ip6.value().default_port();
		if(first instanceof ConnectAddress.Addr4 addr4) return addr4.value().address();
		if(first instanceof ConnectAddress.Addr6 addr6) return addr6.value().address();
		if(first instanceof ConnectAddress.Auto auto) return auto.value().address();
		if(first instanceof ConnectAddress.Domain domain) return domain.value().address();
		return null;
	}

	/** Starts the control-channel worker; returns immediately. */
	public synchronized void start() {
		if(running) return;
		running = true;
		phase = Phase.CONNECTING;
		worker = new Thread(this::runWorker, "p2pmss-playit-tunnel");
		worker.setDaemon(true);
		worker.start();
	}

	/** Stops the worker and waits briefly for the control channel to close. */
	public synchronized void stop() {
		running = false;
		phase = Phase.OFF;
		if(worker != null) {
			worker.interrupt();
			try {
				worker.join(5000);
			} catch(InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			worker = null;
		}
	}

	private void runWorker() {
		try {
			publicAddress = ensureTunnel(secretKey);
			activity.accept("Public URL online: " + publicAddress);
		} catch(IOException failure) {
			phase = failure.getMessage() != null && failure.getMessage().contains("invalid authentication")
					? Phase.INVALID_AUTH : Phase.ERROR;
			activity.accept("Public URL unavailable: " + failure.getMessage());
			if(phase == Phase.INVALID_AUTH) return;
		}

		while(running) {
			try(PlayitControlChannel channel = PlayitControlChannel.setup(secretKey, AGENT_VERSION)) {
				phase = Phase.ONLINE;

				while(running) {
					var message = channel.update();
					if(message.isEmpty()) continue;

					if(message.get() instanceof ControlFeedReader.NewClient newClient) {
						String key = newClient.peerAddr + "-" + newClient.connectAddr;
						boolean fresh;
						synchronized(connectionsSync) {
							fresh = activeConnections.add(key);
						}
						if(fresh) {
							InetSocketAddress claimAddress = new InetSocketAddress(
									InetAddress.getByAddress(newClient.claimAddress.ipBytes),
									Short.toUnsignedInt(newClient.claimAddress.portNumber));
							TcpBridge.open(claimAddress, newClient.claimToken, minecraftPort, () -> {
								synchronized(connectionsSync) {
									activeConnections.remove(key);
								}
							});
						}
					}
				}
			} catch(IOException failure) {
				if(!running) break;
				if(failure.getMessage() != null && failure.getMessage().contains("invalid authentication")) {
					phase = Phase.INVALID_AUTH;
					activity.accept("Public URL stopped: playit rejected the stored secret");
					return;
				}
				phase = Phase.ERROR;
				try {
					Thread.sleep(5000);
				} catch(InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
				phase = Phase.CONNECTING;
			}
		}
		phase = Phase.OFF;
	}
}
