package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RunningMinecraftClientTest
{
	@Test
	void recognisesAClientByItsAccessTokenAndExtractsTheVersion()
	{
		RunningMinecraftClient.Client client = RunningMinecraftClient.fromCommandLine( 42,
				"java -Xmx4G net.minecraft.client.main.Main --username Player --version 1.21.7"
						+ " --accessToken secret-token --gameDir /home/player/.minecraft" );
		assertNotNull( client );
		assertEquals( 42, client.pid() );
		assertEquals( "1.21.7", client.versionId() );
	}

	@Test
	void toleratesAClientWithoutVersionArgument()
	{
		RunningMinecraftClient.Client client = RunningMinecraftClient.fromCommandLine( 7,
				"java net.minecraft.client.main.Main --accessToken secret" );
		assertNotNull( client );
		assertNull( client.versionId() );
	}

	@Test
	void ignoresServersAndUnrelatedProcesses()
	{
		// El server NUNCA lleva --accessToken: es el discriminador exacto
		assertNull( RunningMinecraftClient.fromCommandLine( 1,
				"java -Xmx4G -jar fabric-server-launch.jar nogui" ) );
		assertNull( RunningMinecraftClient.fromCommandLine( 2, "java -jar Endershare-1.8.0.jar" ) );
		assertNull( RunningMinecraftClient.fromCommandLine( 3, null ) );
	}
}
