package seedcracker;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongUnaryOperator;
import java.util.stream.LongStream;

/**
 * Recovers a world seed from structure positions and the hashed seed the server sends.
 *
 * <p>Random-spread structures pick their chunk inside each region with an RNG seeded from the
 * world seed, region coordinates and a salt. Only the lower 48 bits of the world seed reach that
 * RNG (java.util.Random's state is 48 bits), so structures can only reveal those bits:
 *
 * <ol>
 *   <li>The offset in a region is nextInt(24). Since 24 is a multiple of 8, its lowest 3 bits are
 *       bits 17-19 of the RNG state, which depend only on the lowest 20 bits of the seed. That
 *       makes the lower 20 bits a 2^20 search.</li>
 *   <li>For each surviving low half, the other 28 bits are a 2^28 search using the full check.
 *       This leaves thousands of candidates even with many structures: some seed differences
 *       shift every RNG output by too little to change an offset.</li>
 *   <li>The top 16 bits never affect structures. The server sends a SHA-256 hash of the seed for
 *       biome blending, so the 65536 possible full seeds are checked against it.</li>
 * </ol>
 *
 * <p>Pure Java with no Minecraft types, so it can be unit tested against vanilla's placement code.
 */
public final class SeedSolver {
	public enum StructureType {
		DESERT_PYRAMID("desert pyramid", 32, 8, 14357617),
		JUNGLE_TEMPLE("jungle temple", 32, 8, 14357619),
		SWAMP_HUT("swamp hut", 32, 8, 14357620);

		public final String displayName;
		public final int spacing;
		public final int separation;
		public final int salt;

		StructureType(String displayName, int spacing, int separation, int salt) {
			this.displayName = displayName;
			this.spacing = spacing;
			this.separation = separation;
			this.salt = salt;
		}

		int bound() {
			return spacing - separation;
		}
	}

	public record Observation(StructureType type, int chunkX, int chunkZ) {}

	/**
	 * @param structureSeeds candidates for the lower 48 bits
	 * @param worldSeeds full seeds that also match the hashed seed; empty if none did
	 * @param ignored an observation that had to be left out to get a match, or null
	 */
	public record Result(long[] structureSeeds, long[] worldSeeds, Observation ignored) {}

	public static final int MIN_OBSERVATIONS = 5;
	/**
	 * Above this many 48-bit candidates, checking all of them against the hash takes too long.
	 * Java's LCG leaves thousands of candidates even with many structures, so this is generous.
	 */
	private static final int MAX_STRUCTURE_SEEDS = 200_000;

	private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	});

	/**
	 * Same result as vanilla's BiomeManager.obfuscateSeed (Guava's sha256().hashLong().asLong()),
	 * without the per-call allocations, since it runs 65536 times per candidate.
	 */
	public static long hashSeed(long seed) {
		byte[] input = new byte[8];
		for (int i = 0; i < 8; i++) {
			input[i] = (byte) (seed >>> (8 * i));
		}
		byte[] digest = SHA_256.get().digest(input);
		long result = 0;
		for (int i = 7; i >= 0; i--) {
			result = (result << 8) | (digest[i] & 0xFF);
		}
		return result;
	}

	private static final long MULTIPLIER = 0x5DEECE66DL;
	private static final long ADDEND = 0xBL;
	private static final long MASK_48 = (1L << 48) - 1;
	private static final long REGION_X_MULTIPLIER = 341873128712L;
	private static final long REGION_Z_MULTIPLIER = 132897987541L;
	private static final int LOW_BITS = 20;
	private static final long LOW_MASK = (1L << LOW_BITS) - 1;

	/** An observation turned into the RNG seed offset and the offsets it must produce. */
	private record Constraint(long seedOffset, int bound, int offsetX, int offsetZ) {}

	private SeedSolver() {}

	/** False if the chunk cannot be a start chunk for this structure type at all. */
	public static boolean isPossible(Observation observation) {
		return toConstraint(observation) != null;
	}

	public static Result crack(List<Observation> observations, long hashedSeed, LongUnaryOperator seedHash) {
		Result result = crackAll(observations, hashedSeed, seedHash, null);
		if (result.structureSeeds().length > 0 || observations.size() <= MIN_OBSERVATIONS) {
			return result;
		}
		// No 48-bit value fits every observation, so one is wrong (a player-built copy, say).
		for (Observation suspect : observations) {
			List<Observation> rest = new ArrayList<>(observations);
			rest.remove(suspect);
			Result retry = crackAll(rest, hashedSeed, seedHash, suspect);
			if (retry.worldSeeds().length > 0) {
				return retry;
			}
		}
		return result;
	}

	private static Result crackAll(List<Observation> observations, long hashedSeed, LongUnaryOperator seedHash, Observation ignored) {
		long[] structureSeeds = structureSeeds(observations);
		if (structureSeeds.length == 0 || structureSeeds.length > MAX_STRUCTURE_SEEDS) {
			return new Result(structureSeeds, new long[0], ignored);
		}
		long[] worldSeeds = Arrays.stream(structureSeeds)
			.flatMap(structureSeed -> Arrays.stream(worldSeeds(structureSeed, hashedSeed, seedHash)))
			.toArray();
		return new Result(structureSeeds, worldSeeds, ignored);
	}

	/** All values of the lower 48 bits that put every observed structure where it was seen. */
	public static long[] structureSeeds(List<Observation> observations) {
		List<Constraint> constraints = new ArrayList<>();
		for (Observation observation : observations) {
			Constraint constraint = toConstraint(observation);
			if (constraint == null) {
				return new long[0];
			}
			constraints.add(constraint);
		}

		long[] lowHalves = LongStream.range(0, 1L << LOW_BITS)
			.parallel()
			.filter(low -> matchesLowBits(low, constraints))
			.toArray();

		List<long[]> found = new ArrayList<>();
		for (long low : lowHalves) {
			found.add(LongStream.range(0, 1L << (48 - LOW_BITS))
				.parallel()
				.map(high -> (high << LOW_BITS) | low)
				.filter(seed -> matchesAll(seed, constraints))
				.toArray());
		}
		return found.stream().flatMapToLong(Arrays::stream).sorted().toArray();
	}

	/** Full 64-bit seeds with these lower 48 bits whose hash matches the one the server sent. */
	public static long[] worldSeeds(long structureSeed, long hashedSeed, LongUnaryOperator seedHash) {
		return LongStream.range(0, 1L << 16)
			.parallel()
			.map(top -> (top << 48) | structureSeed)
			.filter(seed -> seedHash.applyAsLong(seed) == hashedSeed)
			.toArray();
	}

	private static Constraint toConstraint(Observation observation) {
		StructureType type = observation.type();
		int regionX = Math.floorDiv(observation.chunkX(), type.spacing);
		int regionZ = Math.floorDiv(observation.chunkZ(), type.spacing);
		int offsetX = observation.chunkX() - regionX * type.spacing;
		int offsetZ = observation.chunkZ() - regionZ * type.spacing;
		if (offsetX >= type.bound() || offsetZ >= type.bound()) {
			return null;
		}
		long seedOffset = regionX * REGION_X_MULTIPLIER + regionZ * REGION_Z_MULTIPLIER + type.salt;
		return new Constraint(seedOffset, type.bound(), offsetX, offsetZ);
	}

	private static boolean matchesLowBits(long low, List<Constraint> constraints) {
		for (Constraint c : constraints) {
			int knownBits = Math.min(Integer.numberOfTrailingZeros(c.bound()), LOW_BITS - 17);
			if (knownBits == 0) {
				continue;
			}
			long mask = (1L << knownBits) - 1;
			long state = ((low + c.seedOffset()) ^ MULTIPLIER) & LOW_MASK;
			state = (state * MULTIPLIER + ADDEND) & LOW_MASK;
			if (((state >>> 17) & mask) != (c.offsetX() & mask)) {
				return false;
			}
			state = (state * MULTIPLIER + ADDEND) & LOW_MASK;
			if (((state >>> 17) & mask) != (c.offsetZ() & mask)) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesAll(long seed, List<Constraint> constraints) {
		for (Constraint c : constraints) {
			long state = ((seed + c.seedOffset()) ^ MULTIPLIER) & MASK_48;
			long packed = nextInt(state, c.bound());
			if ((int) (packed >>> 48) != c.offsetX()) {
				return false;
			}
			packed = nextInt(packed & MASK_48, c.bound());
			if ((int) (packed >>> 48) != c.offsetZ()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * java.util.Random.nextInt(bound), which LegacyRandomSource matches. Returns the new state in
	 * the low 48 bits and the result above them, to avoid allocating in the 2^28 loop.
	 */
	private static long nextInt(long state, int bound) {
		state = (state * MULTIPLIER + ADDEND) & MASK_48;
		int bits = (int) (state >>> 17);
		if ((bound & -bound) == bound) {
			return state | ((long) (int) ((bound * (long) bits) >> 31) << 48);
		}
		int value = bits % bound;
		while (bits - value + (bound - 1) < 0) {
			state = (state * MULTIPLIER + ADDEND) & MASK_48;
			bits = (int) (state >>> 17);
			value = bits % bound;
		}
		return state | ((long) value << 48);
	}
}
