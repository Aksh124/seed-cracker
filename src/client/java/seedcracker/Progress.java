package seedcracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/**
 * What's been found on each server or single-player world, in config/seedcracker/, so it
 * survives leaving and relaunching. Each file also keeps the server's hashed seed: if that has
 * changed, the world was reset and the old finds are thrown away instead of mixing in.
 */
public final class Progress {
	private static final Logger LOGGER = LoggerFactory.getLogger("seedcracker");
	public static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("seedcracker");

	/** What a file held. */
	public record Saved(long hashedSeed, Long seed, List<Observation> observations) {}

	private Progress() {}

	/** Which server or world this is, as a file name: the address, or the single-player save folder. */
	public static String key(Minecraft mc) {
		ServerData server = mc.getCurrentServer();
		String name;
		IntegratedServer integrated = mc.getSingleplayerServer();
		if (integrated != null) {
			Path root = integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			name = "singleplayer-" + root.getFileName();
		} else if (server != null) {
			name = server.ip;
		} else {
			return null;
		}
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
	}

	public static Path file(String key) {
		return DIRECTORY.resolve(key + ".txt");
	}

	public static void save(String key, long hashedSeed, Long seed, Collection<Observation> observations) {
		if (key == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		lines.add("# Seed Cracker progress. Delete this file, or use /seedcracker clear, to start over here.");
		lines.add("hashedSeed=" + hashedSeed);
		if (seed != null) {
			lines.add("seed=" + seed);
		}
		for (Observation observation : observations) {
			lines.add("found=" + observation.type().name() + "," + observation.chunkX() + "," + observation.chunkZ());
		}
		try {
			Files.createDirectories(DIRECTORY);
			Files.write(file(key), lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Couldn't save progress to {}", file(key), e);
		}
	}

	/** What was saved for this server, or null if nothing was. Lines it doesn't understand are skipped. */
	public static Saved load(String key) {
		if (key == null || !Files.exists(file(key))) {
			return null;
		}
		Long hashedSeed = null;
		Long seed = null;
		List<Observation> observations = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(file(key), StandardCharsets.UTF_8)) {
				line = line.trim();
				try {
					if (line.startsWith("hashedSeed=")) {
						hashedSeed = Long.parseLong(line.substring("hashedSeed=".length()));
					} else if (line.startsWith("seed=")) {
						seed = Long.parseLong(line.substring("seed=".length()));
					} else if (line.startsWith("found=")) {
						String[] parts = line.substring("found=".length()).split(",");
						Observation observation = new Observation(StructureType.valueOf(parts[0]),
							Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
						if (SeedSolver.isPossible(observation)) {
							observations.add(observation);
						}
					}
				} catch (RuntimeException e) {
					LOGGER.warn("Skipping a line in {}: {}", file(key), line);
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Couldn't read {}", file(key), e);
			return null;
		}
		return hashedSeed == null ? null : new Saved(hashedSeed, seed, observations);
	}

	public static void delete(String key) {
		if (key == null) {
			return;
		}
		try {
			Files.deleteIfExists(file(key));
		} catch (IOException e) {
			LOGGER.warn("Couldn't delete {}", file(key), e);
		}
	}
}
