package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jgit.HostLock;
import jgit.TokenStore;

/**
 * Ensayo del ciclo del candado contra la API REAL de GitHub, sobre un repo
 * desechable. Caza lo que el mock no caza: latencias, rate limits y 409 de
 * verdad. Solo corre cuando el entorno lo pide (workflow manual e2e-real):
 * sin las variables, el test se salta en silencio.
 *
 * <p>Variables: {@code Endershare_E2E_REPO} (owner/repo desechable),
 * {@code Endershare_E2E_NICK} y {@code Endershare_E2E_TOKEN} (PAT con scope repo).</p>
 */
@Tag("e2e-real")
class RealGitHubE2ETest
{
	@TempDir
	Path temporaryDirectory;

	/**
	 * Se aisla la carpeta de datos ANTES de nada.
	 *
	 * <p>Estaba dentro del ensayo, despues de la suposicion que lo salta cuando no
	 * hay credenciales. Al saltarse, la carpeta nunca se aislaba, pero el
	 * {@code tearDown} se ejecuta igual: y ahi
	 * {@link TokenStore#invalidateSession()} borraba la sesion de GitHub <b>de
	 * verdad</b> del usuario. Correr los tests te dejaba fuera de tu propia
	 * cuenta, sin un solo fallo en rojo que lo delatara.</p>
	 */
	@BeforeEach
	void setUp() throws Exception
	{
		System.setProperty( "endershare.dataDirectory",
				Files.createDirectories( temporaryDirectory.resolve( "data" ) ).toString() );
	}

	@AfterEach
	void tearDown()
	{
		TokenStore.invalidateSession();
		System.clearProperty( "endershare.dataDirectory" );
		HostLock.clearPublishedDetails();
	}

	@Test
	void lockLifecycleAgainstTheRealGitHubApi() throws Exception
	{
		String repo = System.getenv( "Endershare_E2E_REPO" );
		String nickname = System.getenv( "Endershare_E2E_NICK" );
		String token = System.getenv( "Endershare_E2E_TOKEN" );
		Assumptions.assumeTrue( repo != null && nickname != null && token != null,
				"Sin Endershare_E2E_REPO/NICK/TOKEN este ensayo no corre" );

		assertTrue( TokenStore.saveUserData( nickname, nickname + "@example.test", token ) );

		// Estado limpio de partida: si otro ensayo dejo el candado cogido y aun
		// fresco, este fallara aqui a proposito — es la señal de revisarlo
		HostLock.Status before = HostLock.readStatus( repo );
		Assumptions.assumeTrue( !before.locked() || before.stale() || before.mine(),
				"El repo de ensayo tiene un candado ajeno fresco: revisar " + repo );

		HostLock.AcquireResult acquired = HostLock.acquire( repo );
		assertTrue( acquired.acquired(), acquired.message() );

		HostLock.publishDetails( new HostLock.HostDetails( "e2e.test:25565", 1, 4, "1.21.7" ) );
		assertTrue( HostLock.heartbeat( repo ) );

		HostLock.Status published = HostLock.readStatus( repo );
		assertTrue( published.locked() && published.mine() );
		assertEquals( "e2e.test:25565", published.details().tunnelAddress() );
		assertEquals( 1, published.details().onlinePlayers() );

		assertTrue( HostLock.release( repo ) );
		assertFalse( HostLock.readStatus( repo ).locked() );
	}
}
