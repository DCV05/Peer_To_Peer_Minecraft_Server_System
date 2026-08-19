package view.dashboard;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeVersionWizardTest {
    @BeforeAll
    static void installTheme() throws Exception {
        SwingUtilities.invokeAndWait(DashboardTheme::install);
    }

    @Test
    void rendersCompactCompatibleSelectionAndDelegatesInstall() throws Exception {
        AtomicReference<ForgeVersionWizard.Selection> installed = new AtomicReference<>();
        AtomicReference<ForgeVersionWizard> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ForgeVersionWizard wizard = new ForgeVersionWizard(Path.of("/tmp/server"), installed::set, () -> {});
            wizard.applyCatalog(new ForgeVersionWizard.VersionCatalog(
                    List.of("1.19", "1.20.1"),
                    List.of("1.19-41.1.0", "1.20.1-47.2.0", "1.20.1-47.3.0")));
            reference.set(wizard);
        });

        ForgeVersionWizard wizard = reference.get();
        assertTrue(wizard.getPreferredSize().height <= 430);
        assertFalse(wizard.primaryButton().isEnabled());
        SwingUtilities.invokeAndWait(() -> {
            wizard.minecraftSelect().setSelectedItem("1.20.1");
            assertEquals(3, wizard.forgeSelect().getItemCount());
            wizard.forgeSelect().setSelectedItem("1.20.1-47.3.0");
            wizard.primaryButton().doClick();
        });
        assertNotNull(installed.get());
        assertEquals("1.20.1", installed.get().minecraftVersion());
        assertEquals("1.20.1-47.3.0", installed.get().forgeVersion());
    }
}
