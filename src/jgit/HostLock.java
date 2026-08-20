package jgit;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Arbitrates which peer may host a world through a lease file stored in GitHub.
 *
 * The lease lives as a small JSON file on a dedicated branch so heartbeats never
 * touch the world history. Every write goes through the Contents API with the
 * expected blob sha, which makes GitHub itself the referee: when two peers race,
 * only one PUT lands and the loser receives a conflict instead of overwriting.
 *
 * Freshness never trusts local clocks. The age of a lease is the distance between
 * the lock file's last commit date and the Date header GitHub sent with that same
 * response, so a peer with a broken clock can neither hog nor steal a lock.
 */
public class HostLock
{

	public static final String LOCK_BRANCH = "host-lock";
	public static final String LOCK_FILE_PATH = "p2pmss/host-lock.json";
	/** A heartbeat commit refreshes the lease every 5 minutes while Forge runs. */
	public static final long HEARTBEAT_SECONDS = 300;
	/** Three missed heartbeats mean the host is gone and the lock can be taken over. */
	public static final long DEFAULT_LEASE_SECONDS = 900;

	public record Status( boolean locked, boolean mine, boolean stale, String hostNickname,
			Instant lastHeartbeat, long leaseSeconds, String contentSha )
	{
		static Status free()
		{
			return new Status( false, false, false, null, null, DEFAULT_LEASE_SECONDS, null );
		}
	}

	public record AcquireResult( boolean acquired, boolean blockedByPeer, String message )
	{
	}

	/**
	 * Claims the host role for this peer. Returns a refusal naming the current
	 * host when someone else holds a fresh lease; stale leases and this peer's
	 * own orphaned lease (a previous crash) are taken over silently.
	 */
	public static AcquireResult acquire( String repoFullName )
	{
		Map<String, String> userData;
		try
		{
			userData = TokenStore.getSavedUserData();
		}
		catch( Exception invalidSession )
		{
			return new AcquireResult( false, false, "Sign into GitHub again before arbitrating the host lock." );
		}
		String token = userData.get( "token" );
		String nickname = userData.get( "nickname" );
		try
		{
			if( !ensureLockBranch( repoFullName, token ) )
			{
				return new AcquireResult( false, false, "The host lock branch could not be prepared on GitHub." );
			}
			Status current = read( repoFullName, token, nickname );
			if( current.locked() && !current.stale() && !current.mine() )
			{
				return new AcquireResult( false, true, refusalMessage( current ) );
			}
			int statusCode = writeLease( repoFullName, token, nickname, current.contentSha(),
					"Host lock acquired by " + nickname );
			if( statusCode == 200 || statusCode == 201 )
			{
				String how = current.locked()
						? (current.mine()
								? "Resumed this peer's own host lock."
								: "Took over a stale host lock from " + current.hostNickname() + ".")
						: "GitHub host lock acquired.";
				return new AcquireResult( true, false, how );
			}
			if( statusCode == 409 || statusCode == 422 )
			{
				// Otro peer ganó la carrera entre la lectura y el PUT: GitHub arbitró por nosotros
				Status winner = read( repoFullName, token, nickname );
				if( winner.locked() && !winner.mine() )
					return new AcquireResult( false, true, refusalMessage( winner ) );
				return new AcquireResult( false, false, "The host lock changed while acquiring it. Try again." );
			}
			return new AcquireResult( false, false, "GitHub rejected the host lock write (HTTP " + statusCode + ")." );
		}
		catch( Exception e )
		{
			return new AcquireResult( false, false,
					"GitHub could not be reached to arbitrate the host lock. The server was not started to avoid a hosting conflict." );
		}
	}

	/** Refreshes this peer's lease. Returns false when the lock is not ours anymore. */
	public static boolean heartbeat( String repoFullName )
	{
		try
		{
			Map<String, String> userData = TokenStore.getSavedUserData();
			String token = userData.get( "token" );
			String nickname = userData.get( "nickname" );
			Status current = read( repoFullName, token, nickname );
			if( !current.locked() || !current.mine() )
				return false;
			int statusCode = writeLease( repoFullName, token, nickname, current.contentSha(),
					"Host lock heartbeat by " + nickname );
			return statusCode == 200 || statusCode == 201;
		}
		catch( Exception e )
		{
			return false;
		}
	}

	/**
	 * Deletes this peer's lease right after the final verified stop backup, so the
	 * host role frees immediately instead of waiting out the last heartbeat's lease.
	 * Never deletes another peer's lock.
	 */
	public static boolean release( String repoFullName )
	{
		try
		{
			Map<String, String> userData = TokenStore.getSavedUserData();
			String token = userData.get( "token" );
			String nickname = userData.get( "nickname" );
			Status current = read( repoFullName, token, nickname );
			if( !current.locked() )
				return true;
			if( !current.mine() )
				return false;
			ObjectNode body = GitUtils.JSON_MAPPER.createObjectNode()
					.put( "message", "Host lock released by " + nickname )
					.put( "sha", current.contentSha() )
					.put( "branch", LOCK_BRANCH );
			HttpRequest request = GitUtils.authenticatedRequest( contentsUrl( repoFullName ), token )
					.method( "DELETE", HttpRequest.BodyPublishers.ofString( body.toString() ) )
					.header( "Content-Type", "application/json" )
					.build();
			HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			return response.statusCode() == 200;
		}
		catch( Exception e )
		{
			return false;
		}
	}

	public static Status readStatus( String repoFullName )
	{
		try
		{
			Map<String, String> userData = TokenStore.getSavedUserData();
			return read( repoFullName, userData.get( "token" ), userData.get( "nickname" ) );
		}
		catch( Exception e )
		{
			return Status.free();
		}
	}

	static Status read( String repoFullName, String token, String myNickname ) throws Exception
	{
		HttpRequest request = GitUtils.authenticatedRequest( contentsUrl( repoFullName ) + "?ref=" + LOCK_BRANCH, token )
				.GET().build();
		HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
		if( response.statusCode() == 404 )
			return Status.free();
		if( response.statusCode() != 200 )
			throw new IllegalStateException( "Host lock read failed: HTTP " + response.statusCode() );

		JsonNode file = GitUtils.JSON_MAPPER.readTree( response.body() );
		String contentSha = file.path( "sha" ).asText( null );
		String encoded = file.path( "content" ).asText( "" ).replace( "\n", "" ).replace( "\r", "" );
		JsonNode lease = GitUtils.JSON_MAPPER.readTree( new String( Base64.getDecoder().decode( encoded ), StandardCharsets.UTF_8 ) );
		String host = lease.path( "host_nickname" ).asText( null );
		long leaseSeconds = lease.path( "lease_seconds" ).asLong( DEFAULT_LEASE_SECONDS );
		if( leaseSeconds <= 0 )
			leaseSeconds = DEFAULT_LEASE_SECONDS;

		Heartbeat heartbeat = lastLockCommit( repoFullName, token );
		boolean stale = heartbeat.commitDate() == null
				|| Duration.between( heartbeat.commitDate(), heartbeat.serverNow() ).getSeconds() > leaseSeconds;
		boolean mine = host != null && host.equals( myNickname );
		return new Status( true, mine, stale, host, heartbeat.commitDate(), leaseSeconds, contentSha );
	}

	private record Heartbeat( Instant commitDate, Instant serverNow )
	{
	}

	/** The lease age comes from GitHub's own commit date and Date header, never local clocks. */
	private static Heartbeat lastLockCommit( String repoFullName, String token ) throws Exception
	{
		String url = GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName )
				+ "/commits?path=" + LOCK_FILE_PATH + "&sha=" + LOCK_BRANCH + "&per_page=1";
		HttpRequest request = GitUtils.authenticatedRequest( url, token ).GET().build();
		HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
		Instant serverNow = response.headers().firstValue( "Date" )
				.map( date -> ZonedDateTime.parse( date, DateTimeFormatter.RFC_1123_DATE_TIME ).toInstant() )
				.orElse( Instant.now() );
		if( response.statusCode() != 200 )
			return new Heartbeat( null, serverNow );
		JsonNode commits = GitUtils.JSON_MAPPER.readTree( response.body() );
		if( !commits.isArray() || commits.isEmpty() )
			return new Heartbeat( null, serverNow );
		String date = commits.get( 0 ).path( "commit" ).path( "committer" ).path( "date" ).asText( null );
		return new Heartbeat( date == null ? null : Instant.parse( date ), serverNow );
	}

	private static int writeLease( String repoFullName, String token, String nickname,
			String expectedSha, String commitMessage ) throws Exception
	{
		ObjectNode lease = GitUtils.JSON_MAPPER.createObjectNode()
				.put( "host_nickname", nickname )
				.put( "machine", System.getProperty( "user.name", "unknown" ) )
				.put( "started_at", Instant.now().toString() )
				.put( "lease_seconds", DEFAULT_LEASE_SECONDS );
		ObjectNode body = GitUtils.JSON_MAPPER.createObjectNode()
				.put( "message", commitMessage )
				.put( "content", Base64.getEncoder().encodeToString( lease.toString().getBytes( StandardCharsets.UTF_8 ) ) )
				.put( "branch", LOCK_BRANCH );
		if( expectedSha != null )
			body.put( "sha", expectedSha );
		HttpRequest request = GitUtils.authenticatedRequest( contentsUrl( repoFullName ), token )
				.PUT( HttpRequest.BodyPublishers.ofString( body.toString() ) )
				.header( "Content-Type", "application/json" )
				.build();
		return GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() ).statusCode();
	}

	static boolean ensureLockBranch( String repoFullName, String token ) throws Exception
	{
		String refUrl = GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName ) + "/git/ref/heads/" + LOCK_BRANCH;
		HttpResponse<String> existing = GitUtils.HTTP_CLIENT.send(
				GitUtils.authenticatedRequest( refUrl, token ).GET().build(), HttpResponse.BodyHandlers.ofString() );
		if( existing.statusCode() == 200 )
			return true;
		if( existing.statusCode() != 404 )
			return false;

		HttpResponse<String> repository = GitUtils.HTTP_CLIENT.send(
				GitUtils.authenticatedRequest( GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName ), token )
						.GET().build(),
				HttpResponse.BodyHandlers.ofString() );
		if( repository.statusCode() != 200 )
			return false;
		String defaultBranch = GitUtils.JSON_MAPPER.readTree( repository.body() ).path( "default_branch" ).asText( null );
		if( defaultBranch == null )
			return false;

		HttpResponse<String> baseRef = GitUtils.HTTP_CLIENT.send(
				GitUtils.authenticatedRequest( GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName )
						+ "/git/ref/heads/" + GitUtils.encodePathSegment( defaultBranch ), token )
						.GET().build(),
				HttpResponse.BodyHandlers.ofString() );
		if( baseRef.statusCode() != 200 )
			return false;
		String baseSha = GitUtils.JSON_MAPPER.readTree( baseRef.body() ).path( "object" ).path( "sha" ).asText( null );
		if( baseSha == null )
			return false;

		ObjectNode body = GitUtils.JSON_MAPPER.createObjectNode()
				.put( "ref", "refs/heads/" + LOCK_BRANCH )
				.put( "sha", baseSha );
		HttpResponse<String> created = GitUtils.HTTP_CLIENT.send(
				GitUtils.authenticatedRequest( GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName ) + "/git/refs", token )
						.POST( HttpRequest.BodyPublishers.ofString( body.toString() ) )
						.header( "Content-Type", "application/json" )
						.build(),
				HttpResponse.BodyHandlers.ofString() );
		// 422 = otro peer creó la rama en la misma carrera; para nosotros vale igual
		return created.statusCode() == 201 || created.statusCode() == 422;
	}

	private static String refusalMessage( Status status )
	{
		String since = status.lastHeartbeat() == null ? "an unknown time" : status.lastHeartbeat().toString();
		return status.hostNickname() + " is already hosting this world (last heartbeat: " + since + ").";
	}

	private static String contentsUrl( String repoFullName )
	{
		return GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName ) + "/contents/" + LOCK_FILE_PATH;
	}

	private static String encodedRepo( String repoFullName )
	{
		int slash = repoFullName.indexOf( '/' );
		return GitUtils.encodePathSegment( repoFullName.substring( 0, slash ) ) + "/"
				+ GitUtils.encodePathSegment( repoFullName.substring( slash + 1 ) );
	}
}
