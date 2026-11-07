package seedcracker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/** Checks the solver against vanilla's own placement and hashing code rather than a copy of it. */
class SeedSolverTest {
	private static final StructureType[] TYPES = StructureType.values();
	private static final long MASK_48 = (1L << 48) - 1;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Where vanilla puts the structure of this type in the given region. */
	private static Observation vanilla(long worldSeed, StructureType type, int regionX, int regionZ) {
		RandomSpreadStructurePlacement placement =
			new RandomSpreadStructurePlacement(type.spacing, type.separation, RandomSpreadType.LINEAR, type.salt);
		ChunkPos chunk = placement.getPotentialStructureChunk(worldSeed, regionX * type.spacing, regionZ * type.spacing);
		return new Observation(type, chunk.x(), chunk.z());
	}

	/** Structures within about 10,000 blocks of spawn, like a player would find. */
	private static List<Observation> observe(long worldSeed, int count, long layoutSeed) {
		Random layout = new Random(layoutSeed);
		List<Observation> observations = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			observations.add(vanilla(worldSeed, TYPES[i % TYPES.length], layout.nextInt(40) - 20, layout.nextInt(40) - 20));
		}
		return observations;
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
	void vanillaPlacementIsAlwaysPossible() {
		for (Observation observation : observe(-123456789L, 30, 1)) {
			assertTrue(SeedSolver.isPossible(observation), observation.toString());
		}
	}

	@Test
	void fiveStructuresGiveTheExactWorldSeed() {
		long worldSeed = 0x7A3F_19C2_5D0E_8B41L;
		SeedSolver.Result result = SeedSolver.crack(observe(worldSeed, 5, 2), SeedSolver.hashSeed(worldSeed), SeedSolver::hashSeed);

		assertArrayEquals(new long[] {worldSeed}, result.worldSeeds());
		assertEquals(null, result.ignored());
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
	}

	/** Not a pass/fail check: prints how the candidate count falls, to pick the recommended count. */
	@Test
	void reportCandidateCounts() {
		Random seeds = new Random(11);
		for (int count = 4; count <= 8; count++) {
			long[] sizes = new long[6];
			for (int trial = 0; trial < sizes.length; trial++) {
				long worldSeed = seeds.nextLong();
				long[] found = SeedSolver.structureSeeds(observe(worldSeed, count, seeds.nextLong()));
				assertTrue(contains(found, worldSeed & MASK_48));
				sizes[trial] = found.length;
			}
			Arrays.sort(sizes);
			System.out.printf("%d structures: candidates %s (median %d)%n", count, Arrays.toString(sizes), sizes[sizes.length / 2]);
		}
	}
}
