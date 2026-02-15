package cloud;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.swing.JOptionPane;

import view.MainFrame;

public class ZipUtils {
	
	public static final Path BACKUPS_ZIPS_FOLDER = Path.of("data/backups_zips");
	public static final Path DOWNLOADS_BACKUPS_ZIPS_FOLDER = Path.of("data/download_backups_zips");
	
	public static void createZip(Path sourceDir, Path zipFilePath){
		deleteDirectory(BACKUPS_ZIPS_FOLDER);
		createFie(BACKUPS_ZIPS_FOLDER);
		try(ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFilePath)))){
			Files.walk(sourceDir).forEach(path -> {
				try {
					if (Files.isDirectory(path)) return;
					Path relativePath = sourceDir.relativize(path);
					if(shouldExclude(relativePath)) return;
					
					ZipEntry zipEntry = new ZipEntry(relativePath.toString().replace("\\", "/"));
					zos.putNextEntry(zipEntry);
					Files.copy(path, zos);
					zos.closeEntry();
				}
				catch(IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		}
		catch(IOException e) {
			JOptionPane.showMessageDialog(null, "File " + sourceDir + " or " + zipFilePath + " not found or inaccessible or another thing went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
			e.printStackTrace();
		}
	}
	
	public static void unzip(Path zipFilePath, Path targetDir) {
		if(!(Files.exists(zipFilePath)) || !(Files.exists(targetDir))) {
			JOptionPane.showMessageDialog(null, "File " + targetDir + " or " + zipFilePath + " not found or inaccessible, try again.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		
		try(ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFilePath)))){
			
			ZipEntry zipEntry;
			
			while((zipEntry = zis.getNextEntry()) != null) {
				Path resolvePath = targetDir.resolve(zipEntry.getName()).normalize();
				
				if(!resolvePath.startsWith(targetDir)) throw new IOException("Zip enty out of destinatary folder: " + zipEntry.getName());
				
				if(zipEntry.isDirectory())
					Files.createDirectories(resolvePath);
				else {
					Files.createDirectories(resolvePath.getParent());
					
					try(OutputStream os = Files.newOutputStream(resolvePath)){
						zis.transferTo(os);
					}
				}
				
			zis.closeEntry();
			}
		}
		catch(IOException e) {
			JOptionPane.showMessageDialog(null, "File " + targetDir + " or " + zipFilePath + " not found or inaccessible or another thing went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
			e.printStackTrace();
		}
		
	}
	
	public static void createDirectory(Path dir) {
		try {
			Files.createDirectory(dir);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(null, "Path " + dir + " not found or inaccessible or another thing went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
		}
	}
	
	public static void createFie(Path file) {
		try {
			Files.createFile(file);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(null, "Path " + file + " not found or inaccessible or another thing went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
		}
	}
	
	public static boolean deleteDirectory(Path dir){
	    if (!Files.exists(dir)) return false;

	    try {
		    Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
	    }
	    catch(IOException e) {
	    	e.printStackTrace();
	    	return false;
	    }
	    
	    return true;
	}
	
	public static boolean existsDirectory(Path directory) {
		return Files.exists(directory);
	}
	
	public static void createOrModiFyPropertiesFile(String property, String data, Path propertiesFilePath) {
		if(!Files.exists(propertiesFilePath))
			try {
				Files.createFile(propertiesFilePath);
			} catch (IOException e) {
				JOptionPane.showMessageDialog(null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}
		
		Properties props = new Properties();
		try(FileInputStream in = new FileInputStream(propertiesFilePath.toFile())){
			props.load(in);
			
			props.setProperty(property, data);
			try(FileOutputStream out = new FileOutputStream(propertiesFilePath.toFile())){
				props.store(out, "Modify property:" + property + " with data: '" + data + "'.");
			}
		} catch (IOException e) {
			JOptionPane.showMessageDialog(null, "File " + propertiesFilePath + " not found or inaccessible or another thing went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
		}
	}
	
	public static String getDataFromPropertiesFile(String property, Path propertiesFilePath) {
		if(!Files.exists(propertiesFilePath)) return null;
		
		Properties props = new Properties();
		
		try(FileInputStream in = new FileInputStream(propertiesFilePath.toFile())){
			props.load(in);
			if(!props.containsKey(property)) return null;
			String prString = props.getProperty(property);
			return props.getProperty(property);
		}
		catch (IOException e) {
			JOptionPane.showMessageDialog(null, "File " + propertiesFilePath + " not found or inaccessible or another thing went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
		}
		return null;
	}
	
    private static boolean shouldExclude(Path relativePath) {
        String p = relativePath.toString().replace("\\", "/").toLowerCase();

        return p.startsWith("logs/")
                || p.startsWith("crash-reports/")
                || p.startsWith("versions/")
                || p.endsWith(".log")
                || p.contains("/cache/")
                || p.contains("/.git/");
    }
}
