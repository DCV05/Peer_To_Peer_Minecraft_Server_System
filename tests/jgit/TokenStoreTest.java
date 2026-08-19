package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenStoreTest {
    @TempDir
    Path temporaryDirectory;

    private Path dataDirectory;

    @BeforeEach
    void configureIsolatedDataDirectory() {
        dataDirectory = temporaryDirectory.resolve("data");
        System.setProperty("p2pmss.dataDirectory", dataDirectory.toString());
    }

    @AfterEach
    void clearConfiguration() {
        TokenStore.invalidateSession();
        System.clearProperty("p2pmss.dataDirectory");
    }

    @Test
    void savesAndLoadsOnlyCompleteSessions() throws Exception {
        assertTrue(TokenStore.saveUserData("hoster", "hoster@example.test", "secret-token"));
        assertTrue(TokenStore.sessionIsOpened());

        Map<String, String> userData = TokenStore.getSavedUserData();
        assertEquals("hoster", userData.get("nickname"));
        assertEquals("hoster@example.test", userData.get("email"));
        assertEquals("secret-token", userData.get("token"));

        Files.delete(dataDirectory.resolve("userData.properties"));
        assertFalse(TokenStore.sessionIsOpened());
        assertThrows(Exception.class, TokenStore::getSavedUserData);
    }

    @Test
    void rejectsTamperedCredentials() throws Exception {
        assertTrue(TokenStore.saveUserData("hoster", "hoster@example.test", "secret-token"));
        Path credentials = dataDirectory.resolve("credentials.dat");
        byte[] bytes = Files.readAllBytes(credentials);
        bytes[bytes.length - 1] ^= 1;
        Files.write(credentials, bytes);

        assertFalse(TokenStore.sessionIsOpened());
        assertThrows(Exception.class, TokenStore::getSavedUserData);
    }

    @Test
    void invalidSaveDoesNotDestroyPreviousSession() throws Exception {
        assertTrue(TokenStore.saveUserData("hoster", "hoster@example.test", "secret-token"));
        assertFalse(TokenStore.saveUserData("", "replacement@example.test", "replacement-token"));

        Map<String, String> userData = TokenStore.getSavedUserData();
        assertEquals("hoster", userData.get("nickname"));
        assertEquals("secret-token", userData.get("token"));
    }
}
