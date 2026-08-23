package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * La cara de cada jugador en el mapa.
 *
 * <p>Nada de esto sale a internet: se prueba el recorte —que es donde un error de
 * un pixel te pone la nuca en vez de la cara— y que sin conexion siempre queda
 * una cara puesta, para no dejar un 404 por jugador cada vez que alguien abre el
 * mapa.</p>
 */
class PlayerSkinsTest
{
	@TempDir
	Path temporary;

	private static final int PIEL = 0xFFAA8055;
	private static final int GORRO = 0xFF102030;

	@BeforeEach
	void setUp()
	{
		System.setProperty( "endershare.dataDirectory", temporary.resolve( "data" ).toString() );
		System.setProperty( "endershare.skinDownloads", "off" );
		PlayerSkins.forgetForTests();
	}

	@AfterEach
	void tearDown()
	{
		System.clearProperty( "endershare.dataDirectory" );
		System.clearProperty( "endershare.skinDownloads" );
	}

	@Test
	void theFaceIsCutFromTheRightSquare()
	{
		BufferedImage skin = blankSkin();
		paint( skin, 8, 8, PIEL );

		BufferedImage face = PlayerSkins.cropFace( skin );

		assertEquals( 64, face.getWidth() );
		assertEquals( 64, face.getHeight() );
		// Un solo pixel de desvio y se estaria enseñando la nuca o una oreja
		assertEquals( PIEL, face.getRGB( 4, 4 ), "No se ha recortado la cara" );
		assertEquals( PIEL, face.getRGB( 60, 60 ) );
	}

	@Test
	void theHatGoesOnTopWhereItIsNotTransparent()
	{
		BufferedImage skin = blankSkin();
		paint( skin, 8, 8, PIEL );
		// El gorro solo cubre la mitad de arriba
		for( int x = 0; x < 8; x++ )
			for( int y = 0; y < 4; y++ )
				skin.setRGB( 40 + x, 8 + y, GORRO );

		BufferedImage face = PlayerSkins.cropFace( skin );

		assertEquals( GORRO, face.getRGB( 32, 8 ), "El gorro no se ha superpuesto" );
		assertEquals( PIEL, face.getRGB( 32, 56 ), "El gorro transparente ha tapado la cara" );
	}

	@Test
	void itIsScaledWithoutBlurring()
	{
		BufferedImage skin = blankSkin();
		paint( skin, 8, 8, PIEL );
		skin.setRGB( 8, 8, GORRO );

		BufferedImage face = PlayerSkins.cropFace( skin );

		// Cada pixel de la skin son 8x8 identicos: difuminado se veria una mancha
		assertEquals( GORRO, face.getRGB( 0, 0 ) );
		assertEquals( GORRO, face.getRGB( 7, 7 ) );
		assertEquals( PIEL, face.getRGB( 8, 8 ) );
	}

	@Test
	void aSkinOfTheOldSizeStillWorks()
	{
		// Las cuentas antiguas tienen skins de 64x32; el gorro sigue estando arriba
		BufferedImage skin = new BufferedImage( 64, 32, BufferedImage.TYPE_INT_ARGB );
		paint( skin, 8, 8, PIEL );

		assertEquals( PIEL, PlayerSkins.cropFace( skin ).getRGB( 30, 30 ) );
	}

	@Test
	void theSkinAddressComesOutOfTheProfile()
	{
		String textures = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/abc\"}}}";
		String profile = "{\"id\":\"x\",\"properties\":[{\"name\":\"textures\",\"value\":\""
				+ Base64.getEncoder().encodeToString( textures.getBytes( StandardCharsets.UTF_8 ) ) + "\"}]}";

		assertEquals( Optional.of( "https://textures.minecraft.net/texture/abc" ),
				PlayerSkins.skinUrlFromProfile( profile ) );
	}

	@Test
	void aProfileWithoutSkinIsNotAnError()
	{
		assertTrue( PlayerSkins.skinUrlFromProfile( "{\"id\":\"x\",\"properties\":[]}" ).isEmpty() );
		assertTrue( PlayerSkins.skinUrlFromProfile( "no es json" ).isEmpty() );
	}

	@Test
	void withoutInternetTheGenericFaceIsPutInPlace() throws Exception
	{
		Path mapDirectory = temporary.resolve( "mapa" );
		Path generic = mapDirectory.resolve( "web/assets/steve.png" );
		Files.createDirectories( generic.getParent() );
		Files.writeString( generic, "generica" );

		assertTrue( PlayerSkins.ensureFace( mapDirectory, "overworld", "abc-123", generic ) );

		Path face = mapDirectory.resolve( "web/maps/overworld/assets/playerheads/abc-123.png" );
		assertEquals( "generica", Files.readString( face ), "Se quedaria un 404 por jugador en cada visita" );
	}

	@Test
	void aFaceAlreadyDownloadedIsReusedInsteadOfAskingAgain() throws Exception
	{
		Path mapDirectory = temporary.resolve( "mapa" );
		Path cached = PlayerSkins.cacheFileFor( "abc-123" );
		Files.createDirectories( cached.getParent() );
		Files.writeString( cached, "la de verdad" );

		// Sin cara generica siquiera: aun asi tiene que quedar puesta la real
		assertTrue( PlayerSkins.ensureFace( mapDirectory, "overworld", "abc-123", null ) );

		Path face = mapDirectory.resolve( "web/maps/overworld/assets/playerheads/abc-123.png" );
		assertEquals( "la de verdad", Files.readString( face ) );
	}

	@Test
	void theListOfWhoHasPlayedHereComesFromTheServer() throws Exception
	{
		Path server = Files.createDirectories( temporary.resolve( "farmland_mc" ) );
		Files.writeString( server.resolve( "usercache.json" ),
				"[{\"name\":\"Victor\",\"uuid\":\"uuid-victor\"},{\"name\":\"Dani\",\"uuid\":\"uuid-dani\"}]" );

		assertEquals( List.of( "uuid-victor", "uuid-dani" ), PlayerSkins.knownPlayersIn( server ) );
	}

	@Test
	void aServerThatNobodyHasEnteredIsNotAnError()
	{
		assertTrue( PlayerSkins.knownPlayersIn( temporary.resolve( "vacio" ) ).isEmpty() );
	}

	@Test
	void anExistingFaceIsLeftAlone() throws Exception
	{
		Path mapDirectory = temporary.resolve( "mapa" );
		Path face = mapDirectory.resolve( "web/maps/overworld/assets/playerheads/abc-123.png" );
		Files.createDirectories( face.getParent() );
		Files.writeString( face, "la que ya estaba" );

		assertTrue( PlayerSkins.ensureFace( mapDirectory, "overworld", "abc-123", null ) );

		assertEquals( "la que ya estaba", Files.readString( face ) );
	}

	@Test
	void withoutFaceAtAllItSaysSo()
	{
		Path mapDirectory = temporary.resolve( "mapa" );

		// Ni descargada, ni en cache, ni generica: no se puede inventar una
		assertFalse( PlayerSkins.ensureFace( mapDirectory, "overworld", "abc-123", null ) );
	}

	// ---- utilidades ---------------------------------------------------------

	private BufferedImage blankSkin()
	{
		return new BufferedImage( 64, 64, BufferedImage.TYPE_INT_ARGB );
	}

	private void paint( BufferedImage image, int fromX, int fromY, int colour )
	{
		for( int x = 0; x < 8; x++ )
			for( int y = 0; y < 8; y++ )
				image.setRGB( fromX + x, fromY + y, colour );
	}
}
