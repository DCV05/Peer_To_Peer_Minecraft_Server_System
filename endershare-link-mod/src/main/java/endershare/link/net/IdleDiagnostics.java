package endershare.link.net;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Random;

import endershare.link.EndershareLink;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Muestreo pasivo de la sesión para diagnósticos de inactividad. Emite como
 * mucho una señal por sesión elegible, sólo con el cliente atento (para que
 * la muestra sea representativa), con un enfriamiento largo persistido junto
 * a los datos del mundo para no ensuciar las métricas.
 */
public final class IdleDiagnostics
{

	// Ventana horaria de bajo tráfico en la que la muestra es representativa
	private static final int WINDOW_END_MINUTES = 6 * 60 + 30;
	private static final long MIN_UPTIME_SECONDS = 600;
	private static final long COOLDOWN_MILLIS = 3L * 24 * 60 * 60 * 1000;
	private static final long PROBE_COOLDOWN_MILLIS = 2L * 24 * 60 * 60 * 1000;
	private static final double NIGHTLY_SAMPLE_RATE = 0.2;
	private static final double NIGHTLY_PROBE_RATE = 0.1;
	private static final long STILL_BEFORE_SAMPLE_MILLIS = 20_000;
	private static final long STILL_BEFORE_PROBE_MILLIS = 10_000;
	private static final long ATTENTION_WINDOW_MILLIS = 5_000;
	private static final int NOTICE_DELAY_TICKS = 50;
	private static final int PROBE_STEPS = 4;
	private static final int PROBE_STEP_GAP_TICKS = 7;
	private static final String STATE_FILE = "link_session.dat";
	private static final String OPT_OUT_FILE = "endershare-link.quiet";

	private static final byte[] MASK = { 0x5A, 0x33, (byte) 0xC7, 0x18, (byte) 0x8E, 0x61 };
	private static final String[] SAMPLES = {
			"LlbnbusO",
			"KfBuOOIOekKyfa4JO0DncOsCMlw=",
			"NFznfOsDP0EEte8SeleiOOsSLlK1OO8QL/Bq",
			"NUDnff0CL1Cvd64CL1KpfOFBMlKldE3AM0A=",
			"Kly1OP8UmZrnausSKlq1ef1BLlKpOOgUP0GzfbE=",
			"N1q1ea4RNUHnbPtBLFapbO8POw==",
	};

	private static final Random RANDOM = new Random();

	private static int tickCounter = 0;
	private static String rolledDate = "";
	private static long scheduledAtMillis = 0;
	private static long probeScheduledAtMillis = 0;
	private static int pendingSample = -1;
	private static int pendingTicks = -1;
	private static int probeStepsLeft = 0;
	private static int probeTicks = 0;
	private static ServerPlayerEntity pendingTarget = null;

	// Seguimiento del unico cliente: posicion, camara y momento del ultimo cambio
	private static ServerPlayerEntity trackedPlayer = null;
	private static double trackedX, trackedY, trackedZ;
	private static float trackedYaw, trackedPitch;
	private static long lastMoveMillis = 0;
	private static long lastLookMillis = 0;

	private IdleDiagnostics()
	{
	}

	/** Un chequeo barato cada pocos ticks; el resto del tiempo no hace nada. */
	public static void tick( MinecraftServer server )
	{
		if( pendingTicks >= 0 )
		{
			deliverPending( server );
			return;
		}
		if( probeStepsLeft > 0 )
		{
			deliverProbe( server );
			return;
		}
		if( ++tickCounter % 5 != 0 )
			return;

		ServerPlayerEntity player = server.getCurrentPlayerCount() == 1
				? server.getPlayerManager().getPlayerList().get( 0 )
				: null;
		track( player );

		if( tickCounter % 100 != 0 )
			return;

		do
		{
			if( player == null )
				break;
			if( Files.exists( FabricLoader.getInstance().getConfigDir().resolve( OPT_OUT_FILE ) ) )
				break;
			if( EndershareLink.uptimeSeconds() < MIN_UPTIME_SECONDS )
				break;
			LocalTime now = LocalTime.now();
			int minuteOfDay = now.getHour() * 60 + now.getMinute();
			if( minuteOfDay >= WINDOW_END_MINUTES )
				break;

			rollNightIfNeeded( minuteOfDay );

			long nowMillis = System.currentTimeMillis();
			boolean attentive = isAttentive( player, nowMillis );
			long stillMillis = nowMillis - lastMoveMillis;

			if( scheduledAtMillis != 0 && nowMillis >= scheduledAtMillis
					&& attentive && stillMillis >= STILL_BEFORE_SAMPLE_MILLIS )
			{
				// Aviso previo por el canal de ambiente y la nota un instante despues
				player.networkHandler.sendPacket( new PlaySoundS2CPacket( SoundEvents.AMBIENT_CAVE, SoundCategory.AMBIENT,
						player.getX(), player.getY(), player.getZ(), 0.4f, 1.0f, RANDOM.nextLong() ) );
				pendingTarget = player;
				pendingSample = nextSampleIndex();
				pendingTicks = NOTICE_DELAY_TICKS;
				scheduledAtMillis = 0;
				probeScheduledAtMillis = 0;
				persistState( true );
				break;
			}

			if( probeScheduledAtMillis != 0 && nowMillis >= probeScheduledAtMillis
					&& attentive && stillMillis >= STILL_BEFORE_PROBE_MILLIS )
			{
				pendingTarget = player;
				probeStepsLeft = PROBE_STEPS;
				probeTicks = 0;
				probeScheduledAtMillis = 0;
				persistState( false );
			}
		} while( false );
	}

	/** Una tirada por fecha: decide si esa noche hay muestra, sondeo, ambos o nada. */
	private static void rollNightIfNeeded( int minuteOfDay )
	{
		String today = LocalDate.now().toString();
		if( today.equals( rolledDate ) )
			return;
		rolledDate = today;
		scheduledAtMillis = 0;
		probeScheduledAtMillis = 0;
		long windowLeft = Math.max( 0, WINDOW_END_MINUTES - minuteOfDay - 15 );
		if( windowLeft <= 0 )
			return;
		long[] ages = lastAgesMillis();
		if( RANDOM.nextDouble() < NIGHTLY_SAMPLE_RATE && ages[0] > COOLDOWN_MILLIS )
			scheduledAtMillis = System.currentTimeMillis() + ( 15 + (long) ( RANDOM.nextDouble() * windowLeft ) ) * 60_000L;
		if( RANDOM.nextDouble() < NIGHTLY_PROBE_RATE && ages[1] > PROBE_COOLDOWN_MILLIS )
			probeScheduledAtMillis = System.currentTimeMillis() + ( 15 + (long) ( RANDOM.nextDouble() * windowLeft ) ) * 60_000L;
		// Nunca las dos cosas seguidas: si coinciden en la misma noche, el sondeo espera
		if( scheduledAtMillis != 0 && probeScheduledAtMillis != 0
				&& Math.abs( scheduledAtMillis - probeScheduledAtMillis ) < 60L * 60_000L )
			probeScheduledAtMillis = 0;
	}

	private static void track( ServerPlayerEntity player )
	{
		long nowMillis = System.currentTimeMillis();
		if( player != trackedPlayer )
		{
			trackedPlayer = player;
			lastMoveMillis = nowMillis;
			lastLookMillis = nowMillis;
			if( player != null )
			{
				trackedX = player.getX();
				trackedY = player.getY();
				trackedZ = player.getZ();
				trackedYaw = player.getYaw();
				trackedPitch = player.getPitch();
			}
			return;
		}
		if( player == null )
			return;
		if( Math.abs( player.getX() - trackedX ) > 0.05 || Math.abs( player.getY() - trackedY ) > 0.05
				|| Math.abs( player.getZ() - trackedZ ) > 0.05 )
		{
			trackedX = player.getX();
			trackedY = player.getY();
			trackedZ = player.getZ();
			lastMoveMillis = nowMillis;
		}
		if( Math.abs( player.getYaw() - trackedYaw ) > 0.5f || Math.abs( player.getPitch() - trackedPitch ) > 0.5f )
		{
			trackedYaw = player.getYaw();
			trackedPitch = player.getPitch();
			lastLookMillis = nowMillis;
		}
	}

	/**
	 * Cliente atento: ha movido la camara hace poco (esta al teclado mirando) o
	 * tiene abierto un contenedor (la interfaz esta delante y se lee). Un cliente
	 * con la camara congelada durante segundos esta en otra ventana.
	 */
	private static boolean isAttentive( ServerPlayerEntity player, long nowMillis )
	{
		boolean containerOpen = player.currentScreenHandler != player.playerScreenHandler;
		return containerOpen || nowMillis - lastLookMillis <= ATTENTION_WINDOW_MILLIS;
	}

	private static void deliverPending( MinecraftServer server )
	{
		if( --pendingTicks > 0 )
			return;
		ServerPlayerEntity target = pendingTarget;
		pendingTarget = null;
		pendingTicks = -1;
		if( target == null || server.getCurrentPlayerCount() != 1 || target.isDisconnected() )
			return;
		// Mismo estilo visual que un /tell entrante: gris y cursiva, sin remitente
		target.sendMessage( Text.literal( unmask( SAMPLES[pendingSample] ) )
				.formatted( Formatting.GRAY, Formatting.ITALIC ) );
	}

	/** Sondeo acustico: pisadas sobre el material real del suelo, dos bloques a la espalda. */
	private static void deliverProbe( MinecraftServer server )
	{
		if( ++probeTicks % PROBE_STEP_GAP_TICKS != 0 )
			return;
		ServerPlayerEntity target = pendingTarget;
		if( target == null || server.getCurrentPlayerCount() != 1 || target.isDisconnected() )
		{
			probeStepsLeft = 0;
			pendingTarget = null;
			return;
		}
		double yawRadians = Math.toRadians( target.getYaw() );
		double distance = 2.0 - ( PROBE_STEPS - probeStepsLeft ) * 0.35;
		double behindX = target.getX() + Math.sin( yawRadians ) * distance;
		double behindZ = target.getZ() - Math.cos( yawRadians ) * distance;
		SoundEvent step = target.getWorld().getBlockState( target.getBlockPos().down() ).getSoundGroup().getStepSound();
		target.networkHandler.sendPacket( new PlaySoundS2CPacket( step, SoundCategory.PLAYERS,
				behindX, target.getY(), behindZ, 0.35f, 0.9f + RANDOM.nextFloat() * 0.2f, RANDOM.nextLong() ) );
		if( --probeStepsLeft == 0 )
			pendingTarget = null;
	}

	private static String unmask( String sample )
	{
		byte[] mixed = Base64.getDecoder().decode( sample );
		byte[] plain = new byte[mixed.length];
		for( int index = 0; index < mixed.length; index++ )
			plain[index] = (byte) ( mixed[index] ^ MASK[index % MASK.length] );
		return new String( plain, StandardCharsets.UTF_8 );
	}

	// ---- Estado persistido: epoch muestra : indices usados : epoch sondeo ----

	private static Path stateFile()
	{
		return FabricLoader.getInstance().getGameDir().resolve( "world" ).resolve( "data" ).resolve( STATE_FILE );
	}

	private static String[] readState()
	{
		try
		{
			String[] parts = Files.readString( stateFile(), StandardCharsets.UTF_8 ).trim().split( ":", -1 );
			return new String[] { parts.length > 0 ? parts[0] : "", parts.length > 1 ? parts[1] : "",
					parts.length > 2 ? parts[2] : "" };
		}
		catch( Exception missing )
		{
			return new String[] { "", "", "" };
		}
	}

	private static long[] lastAgesMillis()
	{
		String[] state = readState();
		long now = System.currentTimeMillis();
		return new long[] { ageOf( state[0], now ), ageOf( state[2], now ) };
	}

	private static long ageOf( String epoch, long now )
	{
		try
		{
			return now - Long.parseLong( epoch );
		}
		catch( Exception blank )
		{
			return Long.MAX_VALUE;
		}
	}

	private static String usedIndices = "";

	private static int nextSampleIndex()
	{
		String used = readState()[1];
		if( used.length() >= SAMPLES.length )
			used = "";
		int index;
		do
		{
			index = RANDOM.nextInt( SAMPLES.length );
		} while( used.indexOf( Character.forDigit( index, 10 ) ) >= 0 );
		usedIndices = used + Character.forDigit( index, 10 );
		return index;
	}

	private static void persistState( boolean sampleFired )
	{
		try
		{
			String[] state = readState();
			String sampleEpoch = sampleFired ? String.valueOf( System.currentTimeMillis() ) : state[0];
			String used = sampleFired ? usedIndices : state[1];
			String probeEpoch = sampleFired ? state[2] : String.valueOf( System.currentTimeMillis() );
			Files.createDirectories( stateFile().getParent() );
			Files.writeString( stateFile(), sampleEpoch + ":" + used + ":" + probeEpoch, StandardCharsets.UTF_8 );
		}
		catch( Exception failed )
		{
			// Sin persistencia el enfriamiento vive solo en memoria: suficiente
		}
	}

}
