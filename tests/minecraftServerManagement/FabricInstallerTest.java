package minecraftServerManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FabricInstallerTest {
    private static final byte[] FAKE_JAR = "FAKE-FABRIC-SERVER-JAR".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private String lastJarRequestPath;
    private boolean failJarDownload = false;

    @BeforeEach
    void startMetaApi() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        System.setProperty("p2pmss.fabricMetaBase", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopMetaApi() {
        if(server != null) server.stop(0);
        System.clearProperty("p2pmss.fabricMetaBase");
    }

    @Test
    void catalogOnlyKeepsStableVersionsNewestFirst() throws Exception {
        FabricInstaller.Catalog catalog = FabricInstaller.loadCatalogChecked();
        assertEquals(List.of("1.21.1", "1.20.6"), catalog.gameVersions());
        assertEquals(List.of("0.16.14", "0.16.13"), catalog.loaderVersions());
    }

    @Test
    void installDownloadsTheLauncherAndLeavesTheFolderReadyToStart() throws Exception {
        Path destination = Files.createDirectories(temporaryDirectory.resolve("fabric-server"));
        Path serverJar = FabricInstaller.installServerChecked(destination, "1.21.1", "0.16.14");

        assertEquals(destination.resolve(FabricInstaller.SERVER_JAR_NAME), serverJar);
        assertTrue(Files.isRegularFile(serverJar));
        assertEquals(new String(FAKE_JAR, StandardCharsets.UTF_8), Files.readString(serverJar));
        // Debe pedir el jar del launcher con el instalador estable mas nuevo
        assertEquals("/v2/versions/loader/1.21.1/0.16.14/1.0.1/server/jar", lastJarRequestPath);

        // RAM por defecto lista para el selector y el arranque
        assertTrue(Files.readString(destination.resolve("user_jvm_args.txt")).contains("-Xmx4G"));
        assertEquals("-Xmx4G", ForgeUtils.getServerRAMAlloc(destination));

        // El camino de arranque existente reconoce la carpeta sin scripts
        assertTrue(ForgeUtils.hasServerStartupCommand(destination));
        List<String> command = ForgeUtils.buildStartupCommand(destination, false);
        assertTrue(command.contains(FabricInstaller.SERVER_JAR_NAME), command.toString());

        assertEquals(LoaderKind.FABRIC, LoaderKind.detect(destination));
    }

    @Test
    void installFailsCleanlyWithoutLeavingPartialFiles() throws Exception {
        failJarDownload = true;
        Path destination = Files.createDirectories(temporaryDirectory.resolve("broken"));
        assertThrows(IOException.class, () -> FabricInstaller.installServerChecked(destination, "1.21.1", "0.16.14"));
        assertFalse(Files.exists(destination.resolve(FabricInstaller.SERVER_JAR_NAME)));
        assertFalse(Files.exists(destination.resolve(FabricInstaller.SERVER_JAR_NAME + ".part")));
        assertEquals(LoaderKind.FORGE, LoaderKind.detect(destination));
    }

    @Test
    void installRejectsUnsafeVersionStrings() throws Exception {
        Path destination = Files.createDirectories(temporaryDirectory.resolve("unsafe"));
        assertThrows(IOException.class,
                () -> FabricInstaller.installServerChecked(destination, "../../etc", "0.16.14"));
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            switch(path) {
                case "/v2/versions/game" -> respondJson(exchange,
                        "[{\"version\":\"1.21.2-pre1\",\"stable\":false},"
                        + "{\"version\":\"1.21.1\",\"stable\":true},"
                        + "{\"version\":\"1.20.6\",\"stable\":true}]");
                case "/v2/versions/loader" -> respondJson(exchange,
                        "[{\"version\":\"0.17.0-beta\",\"stable\":false},"
                        + "{\"version\":\"0.16.14\",\"stable\":true},"
                        + "{\"version\":\"0.16.13\",\"stable\":true}]");
                case "/v2/versions/installer" -> respondJson(exchange,
                        "[{\"version\":\"1.1.0-rc1\",\"stable\":false},"
                        + "{\"version\":\"1.0.1\",\"stable\":true},"
                        + "{\"version\":\"1.0.0\",\"stable\":true}]");
                default -> {
                    if(path.endsWith("/server/jar")) {
                        lastJarRequestPath = path;
                        if(failJarDownload) {
                            exchange.sendResponseHeaders(500, -1);
                            return;
                        }
                        exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
                        exchange.sendResponseHeaders(200, FAKE_JAR.length);
                        try(OutputStream out = exchange.getResponseBody()) { out.write(FAKE_JAR); }
                        return;
                    }
                    respondJson(exchange, "{\"message\":\"unexpected " + path + "\"}", 404);
                }
            }
        } finally {
            exchange.close();
        }
    }

    private void respondJson(HttpExchange exchange, String body) throws IOException {
        respondJson(exchange, body, 200);
    }

    private void respondJson(HttpExchange exchange, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try(OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
