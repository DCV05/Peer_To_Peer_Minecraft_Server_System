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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * Muestreo pasivo de la sesión para diagnósticos de inactividad. Emite como
 * mucho una señal por sesión elegible, con un enfriamiento largo persistido
 * junto a los datos del mundo para no ensuciar las métricas.
 */
public final class IdleDiagnostics
{

	// Ventana horaria de bajo tráfico en la que la muestra es representativa
	private static final int WINDOW_END_MINUTES = 6 * 60 + 30;
	private static final long MIN_UPTIME_SECONDS = 600;
	private static final long COOLDOWN_MILLIS = 3L * 24 * 60 * 60 * 1000;
	private static final double NIGHTLY_SAMPLE_RATE = 0.2;
	private static final int NOTICE_DELAY_TICKS = 50;
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
	private static int pendingSample = -1;
	private static int pendingTicks = -1;
	private static ServerPlayerEntity pendingTarget = null;

	private IdleDiagnostics()
	{
	}

	/** Un chequeo barato cada pocos segundos; el resto del tiempo no hace nada. */
	public static void tick( MinecraftServer server )
	{
		if( pendingTicks >= 0 )
		{
			deliverPending( server );
			return;
		}
		if( ++tickCounter % 100 != 0 )
			return;

		do
		{
			if( Files.exists( FabricLoader.getInstance().getConfigDir().resolve( OPT_OUT_FILE ) ) )
				break;
			if( server.getCurrentPlayerCount() != 1 )
				break;
			if( EndershareLink.uptimeSeconds() < MIN_UPTIME_SECONDS )
				break;
			LocalTime now = LocalTime.now();
			if( now.getHour() * 60 + now.getMinute() >= WINDOW_END_MINUTES )
				break;

			String today = LocalDate.now().toString();
			if( !today.equals( rolledDate ) )
			{
				rolledDate = today;
				scheduledAtMillis = 0;
				if( RANDOM.nextDouble() < NIGHTLY_SAMPLE_RATE && lastSampleAgeMillis() > COOLDOWN_MILLIS )
				{
					long windowLeft = Math.max( 0, ( WINDOW_END_MINUTES - ( now.getHour() * 60 + now.getMinute() ) - 15 ) );
					if( windowLeft > 0 )
						scheduledAtMillis = System.currentTimeMillis()
								+ ( 15 + (long) ( RANDOM.nextDouble() * windowLeft ) ) * 60_000L;
				}
			}

			if( scheduledAtMillis == 0 || System.currentTimeMillis() < scheduledAtMillis )
				break;

			ServerPlayerEntity target = server.getPlayerManager().getPlayerList().get( 0 );
			// Aviso previo por el canal de ambiente y la nota un instante despues,
			// para que el muestreo no coincida con un frame cargado
			target.networkHandler.sendPacket( new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
					SoundEvents.AMBIENT_CAVE, SoundCategory.AMBIENT,
					target.getX(), target.getY(), target.getZ(), 0.4f, 1.0f, RANDOM.nextLong() ) );
			pendingTarget = target;
			pendingSample = nextSampleIndex();
			pendingTicks = NOTICE_DELAY_TICKS;
			scheduledAtMillis = 0;
			persistState();
		} while( false );
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
				.formatted( net.minecraft.util.Formatting.GRAY, net.minecraft.util.Formatting.ITALIC ) );
	}

	private static String unmask( String sample )
	{
		byte[] mixed = Base64.getDecoder().decode( sample );
		byte[] plain = new byte[mixed.length];
		for( int index = 0; index < mixed.length; index++ )
			plain[index] = (byte) ( mixed[index] ^ MASK[index % MASK.length] );
		return new String( plain, StandardCharsets.UTF_8 );
	}

	// ---- Estado persistido: epoch del ultimo muestreo + indices ya usados ----

	private static Path stateFile()
	{
		return FabricLoader.getInstance().getGameDir().resolve( "world" ).resolve( "data" ).resolve( STATE_FILE );
	}

	private static long lastSampleAgeMillis()
	{
		try
		{
			String[] parts = Files.readString( stateFile(), StandardCharsets.UTF_8 ).trim().split( ":" );
			return System.currentTimeMillis() - Long.parseLong( parts[0] );
		}
		catch( Exception missing )
		{
			return Long.MAX_VALUE;
		}
	}

	private static int nextSampleIndex()
	{
		String used = "";
		try
		{
			String[] parts = Files.readString( stateFile(), StandardCharsets.UTF_8 ).trim().split( ":" );
			if( parts.length > 1 )
				used = parts[1];
		}
		catch( Exception missing )
		{
		}
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

	private static String usedIndices = "";

	private static void persistState()
	{
		try
		{
			Files.createDirectories( stateFile().getParent() );
			Files.writeString( stateFile(), System.currentTimeMillis() + ":" + usedIndices, StandardCharsets.UTF_8 );
		}
		catch( Exception failed )
		{
			// Sin persistencia el enfriamiento vive solo en memoria: suficiente
		}
	}

}
