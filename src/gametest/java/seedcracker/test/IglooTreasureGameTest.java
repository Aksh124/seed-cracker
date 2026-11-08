package seedcracker.test;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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

/**
 * Visits real igloos (every rotation the world happens to have) and buried treasure, checks
 * each is detected at the start chunk the server reports, and checks the seed cracks.
 */
public class IglooTreasureGameTest implements FabricClientGameTest {
	private static final long SEED = -6_421_009_337_154_802_113L;
	private static final int IGLOOS = 4;
	private static final int TREASURES = 4;

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

			List<Observation> targets = new ArrayList<>();
			targets.addAll(world.getServer().computeOnServer(server -> find(server, BuiltinStructures.IGLOO, StructureType.IGLOO, IGLOOS)));
			targets.addAll(world.getServer().computeOnServer(server -> find(server, BuiltinStructures.BURIED_TREASURE, StructureType.BURIED_TREASURE, TREASURES)));
			log("visiting " + targets);
			check(targets.stream().filter(t -> t.type() == StructureType.IGLOO).count() >= 3, "fewer than 3 igloos near spawn");
			check(targets.stream().filter(t -> t.type() == StructureType.BURIED_TREASURE).count() >= 3, "fewer than 3 treasures near spawn");

			for (Observation target : targets) {
				world.getServer().runCommand("tp @a " + (target.chunkX() * 16 + 8) + " 140 " + (target.chunkZ() * 16 + 8));
				world.getConnection().waitForChunksDownload();
				waitUntil(context, 60, () -> SeedCrackerClient.observations().contains(target) || SeedCrackerClient.foundSeed() != null,
					() -> "didn't detect " + target + "; detected " + SeedCrackerClient.observations());
				log("detected " + target);
				if (SeedCrackerClient.foundSeed() != null) {
					break;
				}
			}

			waitUntil(context, 300, () -> SeedCrackerClient.foundSeed() != null && !SeedCrackerClient.isSolving(),
				() -> "no seed after " + SeedCrackerClient.observations());
			check(SeedCrackerClient.foundSeed() == SEED, "cracked " + SeedCrackerClient.foundSeed());
			log("cracked " + SEED + " from " + SeedCrackerClient.observations());

			context.runOnClient(mc -> mc.player.connection.sendCommand("seedcracker info"));
			context.waitTicks(20);
			context.takeScreenshot("seedcracker-info");
		}
	}

	/** Start chunks the server's own locate reports, from several places so they're distinct. */
	private static List<Observation> find(MinecraftServer server, ResourceKey<Structure> key, StructureType type, int count) {
		ServerLevel level = server.overworld();
		Holder<Structure> holder = server.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
		Set<Observation> found = new LinkedHashSet<>();
		int step = type == StructureType.IGLOO ? 2500 : 600;
		for (int ring = 0; ring < 6 && found.size() < count; ring++) {
			for (int[] dir : new int[][] {{0, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
				BlockPos centre = new BlockPos(dir[0] * step * ring, 64, dir[1] * step * ring);
				Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
					.findNearestMapStructure(level, HolderSet.direct(holder), centre, type == StructureType.IGLOO ? 60 : 40, false);
				if (result != null) {
					BlockPos pos = result.getFirst();
					found.add(new Observation(type, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
				}
				if (found.size() >= count) {
					break;
				}
			}
		}
		return new ArrayList<>(found);
	}

	private static void waitUntil(ClientGameTestContext context, int seconds, BooleanSupplier done, Supplier<String> failure) {
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

	static {
		// Keeps SeedSolver referenced so a missing class fails here rather than mid-test.
		SeedSolver.information(List.of());
	}
}
