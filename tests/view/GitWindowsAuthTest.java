package view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitWindowsAuthTest {
    private HttpServer server;
    private final AtomicReference<String> scenario = new AtomicReference<>("valid");

    @BeforeEach
    void startApi() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user", this::handleUser);
        server.start();
        System.setProperty("p2pmss.githubApiBase", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopApi() {
        if(server != null) server.stop(0);
        System.clearProperty("p2pmss.githubApiBase");
    }

    @Test
    void derivesCanonicalLoginFromAuthenticatedUser() {
        scenario.set("valid");
        assertEquals("CanonicalUser", GitWindows.getAuthenticatedLogin("token"));
    }

    @Test
    void rejectsForbiddenAndMalformedUserResponses() {
        scenario.set("forbidden");
        assertNull(GitWindows.getAuthenticatedLogin("token"));

        scenario.set("malformed");
        assertNull(GitWindows.getAuthenticatedLogin("token"));
    }

    private void handleUser(HttpExchange exchange) throws IOException {
        switch(scenario.get()) {
            case "valid" -> send(exchange, 200, "{\"login\":\"CanonicalUser\"}");
            case "forbidden" -> send(exchange, 403, "{\"message\":\"Forbidden\"}");
            default -> send(exchange, 200, "{\"id\":42}");
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
