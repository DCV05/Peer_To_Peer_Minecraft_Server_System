package minecraftServerManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgeUtilsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsNativeCommandsForWindowsAndUnix() throws Exception {
        Path server = Files.createDirectories(temporaryDirectory.resolve("scripts"));
        Files.writeString(server.resolve("run.bat"), "@echo off\r\n");
        Files.writeString(server.resolve("run.sh"), "#!/bin/sh\n");

        assertEquals(List.of("cmd.exe", "/c", "run.bat", "nogui"), ForgeUtils.buildStartupCommand(server, true));
        assertEquals(List.of("/bin/sh", "run.sh", "nogui"), ForgeUtils.buildStartupCommand(server, false));
    }

    @Test
    void executesUnixScriptAndForwardsNogui() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path server = Files.createDirectories(temporaryDirectory.resolve("unix-server"));
        Files.writeString(server.resolve("run.sh"), "printf '%s' \"$1\" > launched.txt\n");

        Process process = ForgeUtils.executeMinecraftServer(server);
        assertNotNull(process);
        assertEquals(0, process.waitFor());
        assertEquals("nogui", Files.readString(server.resolve("launched.txt")));
    }

    @Test
    void fallsBackToLegacyJarCommand() throws Exception {
        Path server = Files.createDirectories(temporaryDirectory.resolve("legacy-server"));
        Files.writeString(server.resolve("forge-legacy.jar"), "placeholder");
        Files.writeString(server.resolve("user_jvm_args.txt"), "-Xmx2G\n");

        assertEquals(
                List.of("java", "-Xmx2G", "-jar", "forge-legacy.jar", "nogui"),
                ForgeUtils.buildStartupCommand(server, false));
    }

    @Test
    void readsAndUpdatesRamWithoutDependingOnCurrentlyFreeMemory() throws Exception {
        Path server = Files.createDirectories(temporaryDirectory.resolve("ram-server"));
        Path arguments = server.resolve("user_jvm_args.txt");
        Files.writeString(arguments, "# local arguments\n-Xmx2G\n");

        assertEquals("-Xmx2G", ForgeUtils.getServerRAMAlloc(server));
        ForgeUtils.setServerRAMAlloc(server, 1);
        assertEquals("-Xmx1G", ForgeUtils.getServerRAMAlloc(server));
    }

    @Test
    void ignoresCommentedForgeRamExamplesAndAddsAnActiveValue() throws Exception {
        Path server = Files.createDirectories(temporaryDirectory.resolve("official-ram-server"));
        Path arguments = server.resolve("user_jvm_args.txt");
        Files.writeString(arguments, "# Example only: -Xmx3G\n# -Xmx4G\n");

        assertEquals("-Xmx1G", ForgeUtils.getServerRAMAlloc(server));
        ForgeUtils.setServerRAMAlloc(server, 1);
        assertEquals("-Xmx1G", ForgeUtils.getServerRAMAlloc(server));
        assertTrue(Files.readAllLines(arguments).stream().anyMatch(line -> line.equals("-Xmx1G")));
    }

    @Test
    void createsModsDirectoryWithPlatformIndependentPath() throws Exception {
        Path server = Files.createDirectories(temporaryDirectory.resolve("mods-server"));
        ForgeUtils.openModsFolder(server);
        assertTrue(Files.isDirectory(server.resolve("mods")));
    }

    @Test
    void writesExactInlineSettingsWithoutTouchingWorldData() throws Exception {
        Path server = Files.createDirectories(temporaryDirectory.resolve("settings-server"));
        Files.writeString(server.resolve("server.properties"), "server-port=25565\nmax-players=20\nlevel-name=world\n");
        Files.writeString(server.resolve("user_jvm_args.txt"), "-Xmx1G\n");
        Path world = Files.createDirectories(server.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "world-sentinel");

        ForgeUtils.setServerPortChecked(server, 25570);
        ForgeUtils.setMaxPlayers(server, 12);
        ForgeUtils.setServerRAMAlloc(server, "2048M");

        assertEquals(25570, ForgeUtils.getServerPort(server));
        assertEquals(12, ForgeUtils.getMaxPlayers(server));
        assertEquals("-Xmx2048M", ForgeUtils.getServerRAMAlloc(server));
        assertEquals("world-sentinel", Files.readString(world.resolve("level.dat")));
    }
}
