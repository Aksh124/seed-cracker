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
			context.takeScreenshot("seedcracker-found");
		}
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
