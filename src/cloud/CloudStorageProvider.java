package cloud;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.api.services.drive.Drive;

public interface CloudStorageProvider {
	
	public static Path lastServerBackUpDate = app.AppPaths.dataFile("lastServersBackupDate.properties");
	
	void authenticate();
	
	void uploadServerBackup(Path serverZip);
	
	boolean downloadServerBackup(Path serverDestinataryFolder);
	
	boolean hasBackUp();
	
	void closeSession();
	
	boolean isSessionOpened();
	
	boolean inviteUser(String email);
	
	void createSavingFolder();
	
	Map<String, Object> getUserInfo();
	
	String getProviderName();

	boolean hasRemoteServerFolder();
	
	List<String> getInvitedFolderList();
}
