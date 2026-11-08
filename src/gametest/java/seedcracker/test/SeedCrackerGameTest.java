package seedcracker.test;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import seedcracker.SeedCrackerClient;
import seedcracker.SeedSolver;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;
import seedcracker.mixin.BiomeManagerAccess;

/**
 * End to end: makes a world with a known seed, visits structures the server says are there, and
 * checks the mod detects each one and cracks that seed.
 */
public class SeedCrackerGameTest implements FabricClientGameTest {
	private static final long SEED = -6_421_009_337_154_802_113L;
	private static final int STRUCTURES_TO_VISIT = 6;

	private static final Map<StructureType, ResourceKey<Structure>> KEYS = Map.of(
		StructureType.DESERT_PYRAMID, BuiltinStructures.DESERT_PYRAMID,
		StructureType.JUNGLE_TEMPLE, BuiltinStructures.JUNGLE_TEMPLE,
		StructureType.SWAMP_HUT, BuiltinStructures.SWAMP_HUT);

	@Override
	public void runTest(ClientGameTestContext context) {
		// Silent: the test framework turns the volume back up when it starts.
		context.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0));
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(settings -> settings.setSeed(Long.toString(SEED)))
			.create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode spectator @a");

			long hashedSeed = context.computeOnClient(mc -> ((BiomeManagerAccess) mc.level.getBiomeManager()).seedcracker$hashedSeed());
			check(hashedSeed == SeedSolver.hashSeed(SEED), "client's hashed seed doesn't match the world seed");

			List<Observation> targets = world.getServer().computeOnServer(SeedCrackerGameTest::findStructures);
			log("visiting " + targets);
			check(SeedSolver.information(targets).isEnough(), "only found " + targets.size() + " structures near spawn");

			for (Observation target : targets) {
				int x = target.chunkX() * 16 + 8;
				int z = target.chunkZ() * 16 + 8;
				world.getServer().runCommand("tp @a " + x + " 160 " + z);
				world.getConnection().waitForChunksDownload();
				waitUntil(context, 60, () -> SeedCrackerClient.observations().contains(target) || SeedCrackerClient.foundSeed() != null,
					() -> "didn't detect " + target + "; detected " + SeedCrackerClient.observations());
				log("detected " + target);
				if (SeedCrackerClient.foundSeed() != null) {
					break;
				}
			}

			waitUntil(context, 300, () -> SeedCrackerClient.foundSeed() != null && !SeedCrackerClient.isSolving(),
				() -> "no seed after visiting " + SeedCrackerClient.observations());
			check(SeedCrackerClient.foundSeed() == SEED, "cracked " + SeedCrackerClient.foundSeed() + " but the seed is " + SEED);
			log("cracked the seed " + SEED + " from " + SeedCrackerClient.observations().size() + " structures");

			// Sharing the seed: one short code, and importing it gives the seed straight away.
			String code = context.computeOnClient(mc -> SeedCrackerClient.shareCode().encode());
			context.runOnClient(mc -> mc.player.connection.sendCommand("seedcracker clear"));
			context.waitTicks(2);
			context.runOnClient(mc -> mc.player.connection.sendCommand("seedcracker import " + code));
			context.waitTicks(2);
			check(Long.valueOf(SEED).equals(SeedCrackerClient.foundSeed()), "importing the seed code didn't give the seed");
			log("shared the seed as " + code + " and imported it");

			savedProgress(context);
			context.takeScreenshot("seedcracker-found");
		}
	}

	/** Leaving and coming back keeps the seed and finds; a reset world's leftovers are thrown away. */
	private static void savedProgress(ClientGameTestContext context) {
		String key = context.computeOnClient(seedcracker.Progress::key);
		java.nio.file.Path file = seedcracker.Progress.file(key);
		check(java.nio.file.Files.exists(file), "nothing saved at " + file);
		List<Observation> before = SeedCrackerClient.observations();

		context.runOnClient(mc -> SeedCrackerClient.rejoinForTest());
		check(Long.valueOf(SEED).equals(SeedCrackerClient.foundSeed()) && SeedCrackerClient.observations().equals(before),
			"rejoining lost progress: " + SeedCrackerClient.foundSeed() + " " + SeedCrackerClient.observations());
		log("rejoined: seed and " + before.size() + " finds came back from " + file.getFileName());

		// The same server with a different world: leftovers from before the reset are dropped.
		seedcracker.Progress.save(key, 12345L, 678L, before);
		context.runOnClient(mc -> SeedCrackerClient.rejoinForTest());
		check(SeedCrackerClient.foundSeed() == null && SeedCrackerClient.observations().isEmpty() && !java.nio.file.Files.exists(file),
			"a reset world's progress was used");
		log("a reset world's old progress was thrown away");

		// /seedcracker clear removes the save too.
		seedcracker.Progress.save(key, context.computeOnClient(mc ->
			((BiomeManagerAccess) mc.level.getBiomeManager()).seedcracker$hashedSeed()), SEED, before);
		context.runOnClient(mc -> SeedCrackerClient.rejoinForTest());
		check(Long.valueOf(SEED).equals(SeedCrackerClient.foundSeed()), "saved seed not loaded");
		context.runOnClient(mc -> mc.player.connection.sendCommand("seedcracker clear"));
		context.waitTicks(2);
		check(!java.nio.file.Files.exists(file) && SeedCrackerClient.foundSeed() == null, "/seedcracker clear kept the save");
		log("/seedcracker clear deleted the save");
	}

	/** Start chunks of nearby structures, found with the same check /locate uses. */
	private static List<Observation> findStructures(MinecraftServer server) {
		ServerLevel level = server.overworld();
		var structures = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Set<Observation> found = new LinkedHashSet<>();
		int[][] centres = {{0, 0}, {3000, 0}, {0, 3000}, {-3000, 0}, {0, -3000}, {3000, 3000}, {-3000, -3000}, {3000, -3000}, {-3000, 3000}};

		for (int[] centre : centres) {
			for (StructureType type : KEYS.keySet()) {
				Holder<Structure> holder = structures.getOrThrow(KEYS.get(type));
				Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
					.findNearestMapStructure(level, HolderSet.direct(holder), new BlockPos(centre[0], 64, centre[1]), 60, false);
				if (result != null) {
					BlockPos pos = result.getFirst();
					found.add(new Observation(type, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
				}
				if (found.size() >= STRUCTURES_TO_VISIT) {
					return new ArrayList<>(found);
				}
			}
		}
		return new ArrayList<>(found);
	}

	private static void waitUntil(ClientGameTestContext context, int seconds, BooleanSupplier done, java.util.function.Supplier<String> failure) {
		for (int tick = 0; tick < seconds * 20; tick++) {
			if (context.computeOnClient(mc -> done.getAsBoolean())) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(failure.get());
	}

	private static void check(boolean condition, String failure) {
		if (!condition) {
			throw new AssertionError(failure);
		}
	}

	private static void log(String text) {
		System.out.println("[seedcracker-gametest] " + text);
	}
}
