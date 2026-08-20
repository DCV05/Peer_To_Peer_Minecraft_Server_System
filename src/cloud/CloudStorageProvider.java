package cloud;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Contrato de cualquier nube donde se guarden las copias del servidor. La app
 * habla siempre contra esta interfaz para poder anadir proveedores nuevos sin
 * tocar la vista: hoy solo existe Google Drive.
 */
public interface CloudStorageProvider
{

	// Marca de la ultima copia por servidor y proveedor: es lo unico que permite
	// saber si la copia remota es mas nueva que la local sin descargarla entera
	Path lastServerBackUpDate = app.AppPaths.dataFile( "lastServersBackupDate.properties" );

	void authenticate();

	void uploadServerBackup( Path serverZip );

	boolean downloadServerBackup( Path serverDestinataryFolder );

	boolean hasBackUp();

	void closeSession();

	boolean isSessionOpened();

	boolean inviteUser( String email );

	void createSavingFolder();

	Map<String, Object> getUserInfo();

	String getProviderName();

	boolean hasRemoteServerFolder();

	List<String> getInvitedFolderList();
}
