package seedcracker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/** Checks the solver against vanilla's own placement and hashing code rather than a copy of it. */
class SeedSolverTest {
	private static final List<StructureType> SPREAD_TYPES = List.of(
		StructureType.DESERT_PYRAMID, StructureType.JUNGLE_TEMPLE, StructureType.SWAMP_HUT, StructureType.IGLOO);
	private static final long MASK_48 = (1L << 48) - 1;

	/** Vanilla's buried treasure placement, as data/minecraft/worldgen/structure_set/buried_treasures.json declares it. */
	private static RandomSpreadStructurePlacement treasurePlacement;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		treasurePlacement = new RandomSpreadStructurePlacement(new Vec3i(9, 0, 9),
			StructurePlacement.FrequencyReductionMethod.LEGACY_TYPE_2, 0.01f, 0, Optional.empty(), 1, 0, RandomSpreadType.LINEAR);
	}

	/** Where vanilla puts the structure of this type in the given region. */
	private static Observation vanilla(long worldSeed, StructureType type, int regionX, int regionZ) {
		RandomSpreadStructurePlacement placement =
			new RandomSpreadStructurePlacement(type.spacing, type.separation, RandomSpreadType.LINEAR, type.salt);
		ChunkPos chunk = placement.getPotentialStructureChunk(worldSeed, regionX * type.spacing, regionZ * type.spacing);
		return new Observation(type, chunk.x(), chunk.z());
	}

	/** Spread structures within about 10,000 blocks of spawn, like a player would find. */
	private static List<Observation> observe(long worldSeed, int count, long layoutSeed) {
		Random layout = new Random(layoutSeed);
		List<Observation> observations = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			observations.add(vanilla(worldSeed, SPREAD_TYPES.get(i % SPREAD_TYPES.size()), layout.nextInt(40) - 20, layout.nextInt(40) - 20));
		}
		return observations;
	}

	/** Chunks where vanilla's 1% check would allow buried treasure, searched outward from a random spot. */
	private static List<Observation> treasures(long worldSeed, int count, long layoutSeed) {
		Random layout = new Random(layoutSeed);
		List<Observation> found = new ArrayList<>();
		while (found.size() < count) {
			int x = layout.nextInt(600) - 300;
			int z = layout.nextInt(600) - 300;
			if (treasurePlacement.applyAdditionalChunkRestrictions(x, z, worldSeed)) {
				found.add(new Observation(StructureType.BURIED_TREASURE, x, z));
			}
		}
		return found;
	}

	private static boolean contains(long[] values, long value) {
		return Arrays.stream(values).anyMatch(v -> v == value);
	}

	@Test
	void fastHashMatchesVanilla() {
		Random random = new Random(7);
		for (int i = 0; i < 1000; i++) {
			long seed = random.nextLong();
			assertEquals(BiomeManager.obfuscateSeed(seed), SeedSolver.hashSeed(seed));
		}
	}

	@Test
	void treasureChanceMatchesVanilla() {
		Random random = new Random(8);
		int passed = 0;
		for (int i = 0; i < 20_000; i++) {
			long seed = random.nextLong();
			int x = random.nextInt(20_000) - 10_000;
			int z = random.nextInt(20_000) - 10_000;
			boolean vanilla = treasurePlacement.applyAdditionalChunkRestrictions(x, z, seed);
			assertEquals(vanilla, SeedSolver.passesChance(seed & MASK_48, StructureType.BURIED_TREASURE, x, z), "chunk " + x + ", " + z);
			passed += vanilla ? 1 : 0;
		}
		// About 1% should pass, or the test isn't checking anything.
		assertTrue(passed > 100 && passed < 320, "passed " + passed);
	}

	@Test
	void vanillaPlacementIsAlwaysPossible() {
		for (Observation observation : observe(-123456789L, 40, 1)) {
			assertTrue(SeedSolver.isPossible(observation), observation.toString());
		}
	}

	@Test
	void bitCounts() {
		assertEquals(9.17, StructureType.IGLOO.bits(), 0.01);
		assertEquals(6, StructureType.IGLOO.lowBits());
		assertEquals(6.64, StructureType.BURIED_TREASURE.bits(), 0.01);
		assertEquals(0, StructureType.BURIED_TREASURE.lowBits());

		assertFalse(SeedSolver.information(observe(1, 4, 1)).isEnough());
		assertTrue(SeedSolver.information(observe(1, 5, 1)).isEnough());
		// Treasure adds bits but not low bits, so it can't replace every spread structure.
		List<Observation> twoAndTreasure = new ArrayList<>(observe(1, 2, 1));
		twoAndTreasure.addAll(treasures(1, 6, 1));
		assertFalse(SeedSolver.information(twoAndTreasure).isEnough());
	}

	@Test
	void fiveStructuresGiveTheExactWorldSeed() {
		long worldSeed = 0x7A3F_19C2_5D0E_8B41L;
		SeedSolver.Result result = SeedSolver.crack(observe(worldSeed, 5, 2), SeedSolver.hashSeed(worldSeed), SeedSolver::hashSeed);

		assertArrayEquals(new long[] {worldSeed}, result.worldSeeds());
		assertEquals(null, result.ignored());
	}

	@Test
	void fiveIgloosAlone() {
		long worldSeed = 5_550_123_987L;
		Random layout = new Random(9);
		List<Observation> igloos = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			igloos.add(vanilla(worldSeed, StructureType.IGLOO, layout.nextInt(40) - 20, layout.nextInt(40) - 20));
		}
		SeedSolver.Result result = SeedSolver.crack(igloos, SeedSolver.hashSeed(worldSeed), SeedSolver::hashSeed);
		assertArrayEquals(new long[] {worldSeed}, result.worldSeeds());
	}

	@Test
	void threeStructuresAndThreeTreasures() {
		long worldSeed = -3_141_592_653_589_793L;
		List<Observation> observations = new ArrayList<>(observe(worldSeed, 3, 10));
		observations.addAll(treasures(worldSeed, 3, 10));
		assertTrue(SeedSolver.information(observations).isEnough());

		SeedSolver.Result result = SeedSolver.crack(observations, SeedSolver.hashSeed(worldSeed), SeedSolver::hashSeed);
		assertArrayEquals(new long[] {worldSeed}, result.worldSeeds());
	}

	@Test
	void sixStructuresWithANegativeSeed() {
		long worldSeed = -4_872_301_955_120_334_771L;
		SeedSolver.Result result = SeedSolver.crack(observe(worldSeed, 6, 3), SeedSolver.hashSeed(worldSeed), SeedSolver::hashSeed);
		assertArrayEquals(new long[] {worldSeed}, result.worldSeeds());
	}

	@Test
	void realSeedIsAlwaysAmongTheStructureSeeds() {
		long worldSeed = 1234567890123L;
		assertTrue(contains(SeedSolver.structureSeeds(observe(worldSeed, 6, 4)), worldSeed & MASK_48));
	}

	@Test
	void aFakeStructureIsLeftOut() {
		long worldSeed = 99_887_766_554_433L;
		List<Observation> observations = observe(worldSeed, 6, 5);
		Observation real = observations.get(2);
		Observation fake = new Observation(real.type(), real.chunkX() + 1, real.chunkZ());
		observations.set(2, fake);

		SeedSolver.Result result = SeedSolver.crack(observations, SeedSolver.hashSeed(worldSeed), SeedSolver::hashSeed);
		assertArrayEquals(new long[] {worldSeed}, result.worldSeeds());
		assertEquals(fake, result.ignored());
	}

	@Test
	void wrongHashKeepsTheStructureSeedButNoWorldSeed() {
		long worldSeed = 42L;
		SeedSolver.Result result = SeedSolver.crack(observe(worldSeed, 6, 6), SeedSolver.hashSeed(43L), SeedSolver::hashSeed);
		assertEquals(0, result.worldSeeds().length);
		assertTrue(contains(result.structureSeeds(), worldSeed));
	}

	@Test
	void chunkOutsideTheSpreadIsImpossible() {
		// Offsets 24-31 in a 32-chunk region can never hold a start, because separation is 8.
		assertFalse(SeedSolver.isPossible(new Observation(StructureType.SWAMP_HUT, 30, 5)));
		assertTrue(SeedSolver.isPossible(new Observation(StructureType.BURIED_TREASURE, 30, 5)));
	}

	/** Not a pass/fail check: prints how the candidate count falls, for the info command's numbers. */
	@Test
	void reportCandidateCounts() {
		Random seeds = new Random(11);
		int[][] mixes = {{5, 0}, {6, 0}, {8, 0}, {3, 3}, {4, 2}, {4, 4}};
		for (int[] mix : mixes) {
			long[] sizes = new long[5];
			for (int trial = 0; trial < sizes.length; trial++) {
				long worldSeed = seeds.nextLong();
				List<Observation> observations = new ArrayList<>(observe(worldSeed, mix[0], seeds.nextLong()));
				observations.addAll(treasures(worldSeed, mix[1], seeds.nextLong()));
				long[] found = SeedSolver.structureSeeds(observations);
				assertTrue(contains(found, worldSeed & MASK_48));
				sizes[trial] = found.length;
			}
			Arrays.sort(sizes);
			System.out.printf("%d structures + %d treasures: candidates %s (median %d)%n",
				mix[0], mix[1], Arrays.toString(sizes), sizes[sizes.length / 2]);
		}
	}
}
