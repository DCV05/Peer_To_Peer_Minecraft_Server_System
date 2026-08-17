package minecraftServerManagement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cloud.ZipUtils;
import jgit.TokenStore;
import view.GeneralConfigurationsWindows;
import view.MainFrame;

public class CustomCommands {
	private static Map<String, Runnable> commandsActions;
	private static Map<String, String> commandsInfo;
	private static List<String> directions;
	private static String userNickname = null;
	private static String command = null;
	
	//Global variables for playerFillCommand
	private static double[] coords1;
	private static double[] coords2;
	private static String direction;
	private static String lastCoordPosition = "";
	private static boolean fillInProcess = false;
	private static double currentChangingCoord = 0;
	
	private static String directCommands = "\\player\\op\\playerFill";
	
	private static Runnable customHelpCommand = () -> {
		for(Map.Entry<String, String> entry : commandsInfo.entrySet()) {
			ForgeUtils.sendCommand("/tell " + userNickname + " " + entry.getKey() + ": " + entry.getValue(), MainFrame.serverProcess, MainFrame.serverWriter);
		}
	};
	
	private static Runnable customStopCommand = () -> {
		if(MainFrame.cloudProviderInUse != null && ((MainFrame.cloudProviderInUse.equals("GitHub") && TokenStore.sessionIsOpened()) || (MainFrame.cloudProvider != null && MainFrame.cloudProvider.getProviderName().equals(MainFrame.cloudProviderInUse) && MainFrame.cloudProvider.isSessionOpened())))
			ForgeUtils.sendCommand("/title @a subtitle {\"text\":\"Saving backup in " + MainFrame.cloudProviderInUse + ".\",\"bold\":true,\"color\":\"#ccff11\"}", MainFrame.serverProcess, MainFrame.serverWriter);
		ForgeUtils.sendCommand("/title @a title {\"text\":\"Server is shutting down.\",\"bold\":true,\"color\":\"#ff8000\"}", MainFrame.serverProcess, MainFrame.serverWriter);
		try {Thread.sleep(2000);} catch(InterruptedException e) {}
		MainFrame.window.turnOffServer();
	};
	
	private static Runnable customOpCommand = () -> {
		String[] dividedCommandWords = command.trim().split(" ");
		if(dividedCommandWords.length > 2) {
			ForgeUtils.sendCommand("/msg " + userNickname + " Unknown command '" + command + "', use \\help to get more information.", MainFrame.serverProcess, MainFrame.serverWriter);
			return;
		}
		String userToAddToOps = dividedCommandWords[1];
		String currentOpsListValue = "";
		if(ZipUtils.existsDirectory(GeneralConfigurationsWindows.USER_OPS_PATH))
			currentOpsListValue = ZipUtils.getDataFromPropertiesFile("usersOpsForCustomCommands", GeneralConfigurationsWindows.USER_OPS_PATH);
		if(currentOpsListValue.contains(userToAddToOps)) {
			ForgeUtils.sendCommand("/msg " + userNickname + " The user '" + userToAddToOps + "' is already an operator.", MainFrame.serverProcess, MainFrame.serverWriter);
			return;
		}
		if(currentOpsListValue.trim().isEmpty())
			ZipUtils.createOrModiFyPropertiesFile("usersOpsForCustomCommands", userToAddToOps, GeneralConfigurationsWindows.USER_OPS_PATH);
		else
			ZipUtils.createOrModiFyPropertiesFile("usersOpsForCustomCommands", currentOpsListValue + ", " + userToAddToOps, GeneralConfigurationsWindows.USER_OPS_PATH);
	};
	private static Runnable addTabHearts = () -> {
		ForgeUtils.sendCommand("/scoreboard objectives add Hearts health", MainFrame.serverProcess, MainFrame.serverWriter);
		ForgeUtils.sendCommand("/scoreboard objectives setdisplay list Hearts", MainFrame.serverProcess, MainFrame.serverWriter);
	};
	
	
	private static Runnable removeTabHearts = () -> {
		ForgeUtils.sendCommand("/scoreboard objectives remove Hearts", MainFrame.serverProcess, MainFrame.serverWriter);
	};
	
	private static Runnable customCarpetPlayerCommand = () -> {
		String userToSpawn = null;
		String spawnInSurvival = "";
		if(command.contains("spawn")) {
			userToSpawn = command.split(" ")[1];
			spawnInSurvival = " in survival";
		}
		ForgeUtils.sendCommand("/" + command.substring(1) + spawnInSurvival, MainFrame.serverProcess, MainFrame.serverWriter);
		ForgeUtils.sendCommand("/gamemode survival " + userToSpawn, MainFrame.serverProcess, MainFrame.serverWriter);
		if(userToSpawn != null)
			try {Thread.sleep(200);} catch(InterruptedException e) {}
			ForgeUtils.sendCommand("/tp " + userToSpawn + " " + userNickname, MainFrame.serverProcess, MainFrame.serverWriter);
	};
	
	private static Runnable restartServerCommand = () -> {
		if(MainFrame.cloudProviderInUse != null && ((MainFrame.cloudProviderInUse.equals("GitHub") && TokenStore.sessionIsOpened()) || (MainFrame.cloudProvider != null && MainFrame.cloudProvider.getProviderName().equals(MainFrame.cloudProviderInUse) && MainFrame.cloudProvider.isSessionOpened())))
			ForgeUtils.sendCommand("/title @a subtitle {\"text\":\"Saving backup in " + MainFrame.cloudProviderInUse + ".\",\"bold\":true,\"color\":\"#ccff11\"}", MainFrame.serverProcess, MainFrame.serverWriter);
		ForgeUtils.sendCommand("/title @a title {\"text\":\"Server is restarting.\",\"bold\":true,\"color\":\"#ff8000\"}", MainFrame.serverProcess, MainFrame.serverWriter);
		try {Thread.sleep(2000);} catch(InterruptedException e) {}
		MainFrame.window.turnOffServer();
		new Thread(() -> {
			while(true) {
				try {Thread.sleep(100);} catch(InterruptedException e) {}
				if(MainFrame.serverProcess == null) {
					MainFrame.turnOnOffBtn.doClick();
					break;
				}
			}
		}).start();
	};
	
	private static Runnable playerFillCommand = () -> {
		if(command.equals("\\stopFill")){
			if(currentChangingCoord != 0) {
				ForgeUtils.sendCommand("/msg " + userNickname + " Stopping...", MainFrame.serverProcess, MainFrame.serverWriter);
				currentChangingCoord = 0;
				return;
			}
			else {
				ForgeUtils.sendCommand("/msg " + userNickname + " Nothing to stop.", MainFrame.serverProcess, MainFrame.serverWriter);
				return;
			}
		}
		
		if(!command.equals("\\nextFill")) {
		
			String[] commandSplitted = command.split(" ");
			
			try {
				coords1 = new double[] {Integer.parseInt(commandSplitted[1]), Integer.parseInt(commandSplitted[2]), Integer.parseInt(commandSplitted[3])};
				coords2 = new double[] {Integer.parseInt(commandSplitted[4]), Integer.parseInt(commandSplitted[5]), Integer.parseInt(commandSplitted[6])};
				direction = directions.contains(commandSplitted[7].toLowerCase()) ? commandSplitted[7] : null;
				if(direction == null) throw new RuntimeException("Unknown direction, posible values: (north | south | east | west).");
				fillInProcess = true;
			}
			catch(Exception e) {
				if(e instanceof RuntimeException) {
					ForgeUtils.sendCommand("/msg " + userNickname + " " + e.getMessage(), MainFrame.serverProcess, MainFrame.serverWriter);
					return;
				}
				ForgeUtils.sendCommand("/msg " + userNickname + " Command '\\playerFill' sintaxis is incorrect. See \\help for more info.", MainFrame.serverProcess, MainFrame.serverWriter);
				return;
			}
			
		}
		else {
			if(!fillInProcess) {
				ForgeUtils.sendCommand("/msg " + userNickname + " there's not an active fill process. To start one type \\playerFill, see more information in \\help.", MainFrame.serverProcess, MainFrame.serverWriter);
				return;
			}
		}
		
		int numberOfIterations = (int) (direction.equals("north") || direction.equals("south") ? Math.max(coords1[0], coords2[0]) - Math.min(coords1[0], coords2[0]) : Math.max(coords1[2], coords2[2]) - Math.min(coords1[2], coords2[2]));
		if (currentChangingCoord == 0) {
			currentChangingCoord = direction.equals("north") || direction.equals("south") ? Math.min(coords1[0], coords2[0]) : Math.min(coords1[2], coords2[2]);
			currentChangingCoord = Double.parseDouble(((int)currentChangingCoord) + ".5");
		}
		int currentFillerNumber = 1;
		for(int i = 0; i < numberOfIterations; i++) {
			if(currentFillerNumber == 2) {
				currentChangingCoord = 0;
				break;
			}
			if(direction.equals("north") || direction.equals("south")) {
				ForgeUtils.sendCommand("/player " + "filler" + currentFillerNumber + " spawn in survival", MainFrame.serverProcess, MainFrame.serverWriter);
				ForgeUtils.sendCommand("/tp " + "filler" + currentFillerNumber + " " + Double.toString(currentChangingCoord) + " " + coords1[1] + " " + coords1[2], MainFrame.serverProcess, MainFrame.serverWriter);
				ForgeUtils.sendCommand("/player " + "filler" + currentFillerNumber + " look " + getOppositeDirection(direction), MainFrame.serverProcess, MainFrame.serverWriter);
				ForgeUtils.sendCommand(getLookAtCommand("filler" + currentFillerNumber, currentChangingCoord, coords1[1], coords1[2], direction) , MainFrame.serverProcess, MainFrame.serverWriter);
				ForgeUtils.sendCommand("/player " + "filler" + currentFillerNumber + " sneak", MainFrame.serverProcess, MainFrame.serverWriter);
				ForgeUtils.sendCommand("/player " + "filler" + currentFillerNumber + " move backward", MainFrame.serverProcess, MainFrame.serverWriter);
				ForgeUtils.sendCommand("/player " + "filler" + currentFillerNumber + " use continuous", MainFrame.serverProcess, MainFrame.serverWriter);
				currentFillerNumber++;
				currentChangingCoord++;
			}
		}
	};
	
	
	
	static {
		commandsActions = new HashMap<>();
		commandsActions.put("\\help", customHelpCommand);
		commandsActions.put("\\stop", customStopCommand);
		commandsActions.put("\\restart", restartServerCommand);
		commandsActions.put("\\op", customOpCommand);
		commandsActions.put("\\tabHearts add", addTabHearts);
		commandsActions.put("\\tabHearts remove", removeTabHearts);
		commandsActions.put("\\player", customCarpetPlayerCommand);
		commandsActions.put("\\playerFill", playerFillCommand);
		commandsActions.put("\\stopFill", playerFillCommand);
		
		commandsInfo = new HashMap<>();
//		commandsInfo.put("\\playerFill <from> <to> <block> <direction(north | south | east | west)>", "This command will join 20 bot players (from the carpet mod, so you will need it) and they are going to do a shift-bridge, you will need to drop them items to start constructing, the coords only determines the width of the platform filled, so you will need to build a stop wall.");
		commandsInfo.put("\\player <name> (attack | dismount | drop | dropStack | hotbar | jump | kill | look | mount | move | shadow | sneak | spawn | sprint | stop | swapHands | unsneak | unsprint | use)", "The original player carpet mod command ported to backslash commands. (It requires to have carpet mod installed)");
		commandsInfo.put("\\tabHearts (add | remove)", "Makes a new scoreboard in the tab list to show everyone's hearts or removes it.");
		commandsInfo.put("\\op <name>", "Adds the specified player to the backslash commands operators list.");
		commandsInfo.put("\\restart", "Closes the server, saves the world into the cloud provider in use in that moment and then turns on the server again.");
		commandsInfo.put("\\stop", "Closes the server as the vanilla command but saves the server into the cloud provider in use in that moment.");
		
		directions = new ArrayList<>();
		directions.add("north");
		directions.add("south");
		directions.add("east");
		directions.add("west");
	}
	
	public static boolean processCustomCommand(String line) {
		int lastColonPos = line.lastIndexOf(':');
		String infoPostColon = line.substring(lastColonPos + 1);
		userNickname = infoPostColon.substring(2, infoPostColon.lastIndexOf(">"));
		command = infoPostColon.substring(infoPostColon.lastIndexOf("\\")).trim();
		String localCommand = command;
		if(directCommands.contains(command.trim().split(" ")[0])) command = command.trim().split(" ")[0];
		
		if(commandsActions.containsKey(command)) {
			
			if(!command.equals("\\help")) {
				String customCommandsOps = ZipUtils.getDataFromPropertiesFile("usersOpsForCustomCommands", GeneralConfigurationsWindows.USER_OPS_PATH);
				if(customCommandsOps != null && !customCommandsOps.isBlank() && !customCommandsOps.contains(userNickname)) {
					ForgeUtils.sendCommand("/msg " + userNickname + " You do not have permission to execute this command. Ask an operator to add you.", MainFrame.serverProcess, MainFrame.serverWriter);
					return false;
				}
			}
			
			Runnable action = commandsActions.get(command);
			if(directCommands.contains(command)) command = localCommand;
			action.run();
			command = null;
			userNickname = null;
			return true;
			
		}
		else 
			ForgeUtils.sendCommand("/msg " + userNickname + " Unknown command '" + command + "', use \\help to get more information.", MainFrame.serverProcess, MainFrame.serverWriter);
			
		command = null;
		userNickname = null;
		return false; 
	}
	
	public static String getOppositeDirection(String direction) {
		switch(direction) {
			case "north": return "south";
			case "south": return "north";
			case "east":  return "west";
			case "west":  return "east";
			default: return null;
		}
	}
	
	public static String getLookAtCommand(String bot, double x, double y, double z, String dir) {

	    final double DIST = 10.0;      // cualquier valor > 1 sirve
	    final double Y_OFFSET = 60;  // este es el único valor que irás ajustando

	    double tx = x;
	    double ty = y - Y_OFFSET;
	    double tz = z;

	    switch (dir) {
	        case "north" -> tz -= DIST;
	        case "south" -> tz += DIST;
	        case "east"  -> tx += DIST;
	        case "west"  -> tx -= DIST;
	    }

	    return String.format(Locale.US,
	            "/player %s look at %.3f %.3f %.3f",
	            bot, tx, ty, tz);
	}
	
	
}
