package net.seasonal.tempus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


public class Tempus implements DedicatedServerModInitializer {
	public static final String MOD_ID = "tempus";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final String CONFIG_FILE = "config/tempus_config.json";
	private static int backupInterval;
	private static int maxBackups;
	private static Timer backupTimer = new Timer(true);

	@Override
	public void onInitializeServer() {
		LOGGER.info("Initializing Tempus");
		loadConfig();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("backup")
					.executes(context -> {
						backupServer();
						context.getSource().sendFeedback(() -> Text.of("§aBackup started."), false);
						return 1;
					})

					.then(CommandManager.literal("set")
							.then(CommandManager.literal("interval")
									.then(CommandManager.argument("hours", IntegerArgumentType.integer(0))
											.then(CommandManager.argument("minutes", IntegerArgumentType.integer(0))
													.executes(context -> {
														int hours = IntegerArgumentType.getInteger(context, "hours");
														int minutes = IntegerArgumentType.getInteger(context, "minutes");
														setBackupInterval(hours, minutes);
														context.getSource().sendFeedback(() -> Text.of("§aBackup interval set to §6" + hours + " §ahours and §6" + minutes + " §aminutes."), false);
														return 1;
													})
											)
									)
							)
							.then(CommandManager.literal("maxbackups")
									.then(CommandManager.argument("maxBackups", IntegerArgumentType.integer(1))
											.executes(context -> {
												int max = IntegerArgumentType.getInteger(context, "maxBackups");
												setMaxBackups(max);
												context.getSource().sendFeedback(() -> Text.of("§aMax backups set to §6" + max + " §afiles."), false);
												return 1;
											})
									)
							)
					)

					.then(CommandManager.literal("get")
							.then(CommandManager.literal("interval")
									.executes(context -> {
										context.getSource().sendFeedback(() -> Text.of("§aCurrent backup interval: §6" + (backupInterval / 60000) + " §aminutes."), false);
										return 1;
									})
							)
							.then(CommandManager.literal("maxbackups")
									.executes(context -> {
										context.getSource().sendFeedback(() -> Text.of("§aCurrent max backups: §6" + maxBackups + " §afiles."), false);
										return 1;
									})
							)
					)

					.then(CommandManager.literal("credits")
							.executes(context -> {
								String creditsMessage = "§aMod created by Sea. Special thanks to all contributors!";
								context.getSource().sendFeedback(() -> Text.of(creditsMessage), false);
								return 1;
							})
					)
			);
		});


		LOGGER.info("Registered Commands");
		startBackupTask();
		LOGGER.info("Started Backup");
	}

	private static void loadConfig() {
		try {
			File configFile = new File(CONFIG_FILE);
			if (configFile.exists()) {
				try (Reader reader = new FileReader(configFile)) {
					JsonObject config = new Gson().fromJson(reader, JsonObject.class);
					backupInterval = config.has("backupInterval") ? config.get("backupInterval").getAsInt() : 5 * 60 * 1000;
					maxBackups = config.has("maxBackups") ? config.get("maxBackups").getAsInt() : 5;
				}
			} else {
				backupInterval = 5 * 60 * 1000;
				maxBackups = 5;
				saveConfig();
			}
			LOGGER.info("Settings loaded: Backup Interval = {}ms, Max Backups = {}", backupInterval, maxBackups);
		} catch (IOException e) {
			LOGGER.error("Failed to load config", e);
		}
	}

	private static void saveConfig() {
		JsonObject config = new JsonObject();
		config.addProperty("backupInterval", backupInterval);
		config.addProperty("maxBackups", maxBackups);

		try (Writer writer = new FileWriter(CONFIG_FILE)) {
			new Gson().toJson(config, writer);
		} catch (IOException e) {
			LOGGER.error("Error", e);
		}
	}

	private static void startBackupTask() {
		LOGGER.info("Starting Backup Task in {}ms", backupInterval);
		backupTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				backupServer();
			}
		}, backupInterval, backupInterval);
	}

	private static void setBackupInterval(int hours, int minutes) {
		backupInterval = (hours * 60 * 60 * 1000) + (minutes * 60 * 1000);
		saveConfig();
		backupTimer.cancel();
		backupTimer = new Timer(true);
		LOGGER.info("Interval set to: {}ms", backupInterval);
		startBackupTask();
	}

	private static void setMaxBackups(int maxBackups) {
		Tempus.maxBackups = maxBackups;
		saveConfig();
		LOGGER.info("Max Backups set to: {}", maxBackups);
	}

	private static void backupServer() {
		try {
			File worldDir = new File("world");
			Path backupDir = Paths.get("backups");

			if (!Files.exists(backupDir)) {
				Files.createDirectories(backupDir);
			}

			Path backupFile = Paths.get(backupDir.toString(), "world_backup_" + System.currentTimeMillis() + ".zip");
			zipDirectory(worldDir.toPath(), backupFile);

			cleanUpBackups(backupDir);
		} catch (Exception e) {
			LOGGER.error("Error", e);
		}
	}

	private static void zipDirectory(Path sourceDir, Path zipFile) throws Exception {
		try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
			Files.walk(sourceDir).forEach(path -> {
				try {
					String entryName = sourceDir.relativize(path).toString();
					if (Files.isDirectory(path)) return;
					zos.putNextEntry(new ZipEntry(entryName));
					Files.copy(path, zos);
					zos.closeEntry();
				} catch (Exception ignored) {}
			});
		}
	}

	private static void cleanUpBackups(Path backupDir) {
		File[] backups = backupDir.toFile().listFiles((dir, name) -> name.endsWith(".zip"));
		if (backups == null || backups.length <= maxBackups) return;

		Arrays.sort(backups, Comparator.comparingLong(File::lastModified));

		for (int i = 0; i < backups.length - maxBackups; i++) {
			if (backups[i].delete()) {
				LOGGER.info("Deleted old backup: {}", backups[i].getName());
			}
		}
	}
}
