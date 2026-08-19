package view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MainFrameSelectionTest {
    @AfterEach
    void resetProvider() {
        MainFrame.cloudProviderInUse = "noCloudProvider";
    }

    @Test
    void comparesGitHubProviderByValue() {
        MainFrame.cloudProviderInUse = new String("GitHub");
        assertTrue(MainFrame.isGitHubSelected());

        MainFrame.cloudProviderInUse = "GoogleDrive";
        assertFalse(MainFrame.isGitHubSelected());

        MainFrame.cloudProviderInUse = null;
        assertFalse(MainFrame.isGitHubSelected());
    }
}
