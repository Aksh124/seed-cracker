package seedcracker;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongUnaryOperator;
import java.util.stream.LongStream;

/**
 * Recovers a world seed from structure positions and the hashed seed the server sends.
 *
 * <p>Structures are placed with an RNG seeded from the world seed, a position and a salt. Only
 * the lower 48 bits of the world seed reach that RNG (java.util.Random's state is 48 bits), so
 * structures can only reveal those bits. Two kinds of observation are used:
 *
 * <ul>
 *   <li><b>Spread structures</b> (pyramids, temples, huts, igloos): one per 32x32-chunk region, at
 *       an offset of nextInt(24) on each axis. That is log2(24) = 4.58 bits per axis. Since 24 is
 *       a multiple of 8, the lowest 3 bits of each offset are bits 17-19 of the RNG state, which
 *       depend only on the seed's lowest 20 bits.</li>
 *   <li><b>Buried treasure</b>: every chunk has a 1% chance, decided by nextFloat() &lt; 0.01 from
 *       an RNG seeded with the chunk position. Finding one is log2(100) = 6.64 bits, all of them
 *       on the upper part of the state.</li>
 *   <li><b>Other spread structures</b>: villages, ruined portals and ancient cities work like the
 *       temples with other spacings. Mansions and End cities use a triangular spread, the average
 *       of two rolls per axis. Only villages add any low bits.</li>
 *   <li><b>End pillars</b>: Random(seed).nextLong() &amp; 65535 picks their order. That is bits
 *       16-31 of the RNG's second step, which depend only on the seed's low 32 bits, so with
 *       them the search tries 2^32 low values, then 2^16 for the rest, and needs no low bits.</li>
 * </ul>
 *
 * <ol>
 *   <li>The lower 20 bits are a 2^20 search using the spread structures' low bits.</li>
 *   <li>For each surviving low half, the other 28 bits are a 2^28 search using every check.
 *       This leaves thousands of candidates even with plenty of data: some seed differences shift
 *       every RNG output by too little to change the result.</li>
 *   <li>The top 16 bits never affect structures. The server sends a SHA-256 hash of the seed for
 *       biome blending, so the 65536 possible full seeds are checked against it.</li>
 * </ol>
 *
 * <p>Pure Java with no Minecraft types, so it can be unit tested against vanilla's placement code.
 */
public final class SeedSolver {
	public enum Kind {
		/** One per region of spacing x spacing chunks, at a random offset. */
		SPREAD,
		/** Like SPREAD, but each offset is the average of two rolls, so it clusters in the middle. */
		TRIANGULAR,
		/** Each chunk independently, with a fixed chance. */
		CHANCE,
		/** The End's ten obsidian pillars: their order is 16 bits of Random(seed).nextLong(). */
		PILLARS
	}

	/** Share codes store these by position, so new types go at the end. */
	public enum StructureType {
		DESERT_PYRAMID("desert pyramid", 32, 8, 14357617),
		JUNGLE_TEMPLE("jungle temple", 32, 8, 14357619),
		SWAMP_HUT("swamp hut", 32, 8, 14357620),
		IGLOO("igloo", 32, 8, 14357618),
		VILLAGE("village", 34, 8, 10387312),
		RUINED_PORTAL("ruined portal", 40, 15, 34222645),
		/** nextInt(16) uses the top bits of the RNG, so ancient cities say nothing about the low bits. */
		ANCIENT_CITY("ancient city", 24, 8, 20083232),
		WOODLAND_MANSION("woodland mansion", Kind.TRIANGULAR, 80, 20, 10387319),
		END_CITY("End city", Kind.TRIANGULAR, 20, 11, 10387313),
		/** The pillar order, stored in the observation's chunkX. */
		END_PILLARS("End pillars", Kind.PILLARS, 1, 0, 0),
		/** Vanilla's "legacy_type_2" frequency check: a fixed salt and a 1% chance per chunk. */
		BURIED_TREASURE("buried treasure", 10387320, 0.01f);

		public final String displayName;
		public final Kind kind;
		public final int spacing;
		public final int separation;
		public final int salt;
		public final float chance;

		StructureType(String displayName, int spacing, int separation, int salt) {
			this(displayName, Kind.SPREAD, spacing, separation, salt);
		}

		StructureType(String displayName, Kind kind, int spacing, int separation, int salt) {
			this.displayName = displayName;
			this.kind = kind;
			this.spacing = spacing;
			this.separation = separation;
			this.salt = salt;
			this.chance = 1f;
		}

		StructureType(String displayName, int salt, float chance) {
			this.displayName = displayName;
			this.kind = Kind.CHANCE;
			this.spacing = 1;
			this.separation = 0;
			this.salt = salt;
			this.chance = chance;
		}

		int bound() {
			return spacing - separation;
		}

		/** Information one observation of this type gives about the 48-bit structure seed. */
		public double bits() {
			return switch (kind) {
				case SPREAD -> 2 * log2(bound());
				case TRIANGULAR -> 2 * triangularEntropy(bound());
				case CHANCE -> -log2(chance);
				case PILLARS -> 16;
			};
		}

		/** How many of those bits fall on the seed's lowest 20 bits (used by the first search). */
		public int lowBits() {
			return 2 * lowBitsPerAxis(kind, bound());
		}
	}

	public record Observation(StructureType type, int chunkX, int chunkZ) {}

	/**
	 * How much the observations say about the 48-bit structure seed.
	 *
	 * @param bits total information, before the LCG overlap that makes the real figure lower
	 * @param lowBits information on the lowest 20 bits, which the first search needs
	 */
	public record Information(double bits, int lowBits, boolean pillars) {
		public boolean isEnough() {
			return bits >= BITS_NEEDED && (lowBits >= LOW_BITS_NEEDED || pillars);
		}
	}

	/**
	 * @param structureSeeds candidates for the lower 48 bits
	 * @param worldSeeds full seeds that also match the hashed seed; empty if none did
	 * @param ignored an observation that had to be left out to get a match, or null
	 */
	public record Result(long[] structureSeeds, long[] worldSeeds, Observation ignored) {}

	/**
	 * In theory 48 bits pins down the structure seed. Java's LCG makes observations overlap, so
	 * 45 (five spread structures) is where the candidate list becomes small enough to check.
	 */
	public static final double BITS_NEEDED = 45;
	/**
	 * With 18 of the 20 low bits known, the first search leaves about 4 low halves, each needing
	 * a 2^28 second search. Fewer known bits multiplies that by 4 per missing pair.
	 */
	public static final int LOW_BITS_NEEDED = 18;
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
	private static final float FLOAT_UNIT = 5.9604645E-8F;

	/** An observation turned into the RNG seed offset and what the RNG must produce. */
	private record Constraint(Kind kind, long seedOffset, int bound, int offsetX, int offsetZ, float chance) {}

	/** Bits of each offset that fall on the RNG's low bits, which the first search uses. */
	private static int lowBitsPerAxis(Kind kind, int bound) {
		// nextInt of a power of two takes the top bits instead, and a triangular offset mixes two rolls.
		if (kind != Kind.SPREAD || (bound & -bound) == bound) {
			return 0;
		}
		return Math.min(Integer.numberOfTrailingZeros(bound), LOW_BITS - 17);
	}

	/** Entropy of (nextInt(bound) + nextInt(bound)) / 2. */
	private static double triangularEntropy(int bound) {
		double[] chances = new double[bound];
		for (int a = 0; a < bound; a++) {
			for (int b = 0; b < bound; b++) {
				chances[(a + b) / 2] += 1.0 / ((double) bound * bound);
			}
		}
		double entropy = 0;
		for (double p : chances) {
			entropy -= p > 0 ? p * log2(p) : 0;
		}
		return entropy;
	}

	private SeedSolver() {}

	private static double log2(double value) {
		return Math.log(value) / Math.log(2);
	}

	public static Information information(List<Observation> observations) {
		double bits = 0;
		int lowBits = 0;
		boolean pillars = false;
		for (Observation observation : observations) {
			if (observation.type() == StructureType.END_PILLARS) {
				// Seeing them twice says nothing new.
				if (pillars) {
					continue;
				}
				pillars = true;
			}
			bits += observation.type().bits();
			lowBits += observation.type().lowBits();
		}
		return new Information(bits, Math.min(lowBits, LOW_BITS), pillars);
	}

	/** False if the chunk cannot hold this structure type at all. */
	public static boolean isPossible(Observation observation) {
		return toConstraint(observation) != null;
	}

	public static Result crack(List<Observation> observations, long hashedSeed, LongUnaryOperator seedHash) {
		Result result = crackAll(observations, hashedSeed, seedHash, null);
		if (result.structureSeeds().length > 0) {
			return result;
		}
		// No 48-bit value fits every observation, so one is wrong (a player-built copy, say).
		for (Observation suspect : observations) {
			List<Observation> rest = new ArrayList<>(observations);
			rest.remove(suspect);
			if (!information(rest).isEnough()) {
				continue;
			}
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

	/** All values of the lower 48 bits that fit every observation. */
	public static long[] structureSeeds(List<Observation> observations) {
		List<Constraint> constraints = new ArrayList<>();
		for (Observation observation : observations) {
			Constraint constraint = toConstraint(observation);
			if (constraint == null) {
				return new long[0];
			}
			constraints.add(constraint);
		}
		// Spread checks rule out more per call, so they go first in the inner loop.
		constraints.sort(Comparator.comparing(Constraint::kind));

		Constraint pillars = constraints.stream().filter(c -> c.kind() == Kind.PILLARS).findFirst().orElse(null);
		if (pillars != null) {
			return structureSeedsFromPillars(pillars.offsetX(), constraints);
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

	/**
	 * With the End pillars, the low 32 bits come first: the pillar order is bits 16-31 of the
	 * RNG's second step, which depends only on the seed's low 32 bits. Trying all 2^32 leaves about
	 * 2^16, and each needs only the 2^16 values of the remaining bits. No low-bit structures needed.
	 */
	private static long[] structureSeedsFromPillars(int pillarOrder, List<Constraint> constraints) {
		long[] lowHalves = LongStream.range(0, 1L << 32)
			.parallel()
			.filter(low -> pillarOrder(low) == pillarOrder && matchesLowBits(low & LOW_MASK, constraints))
			.toArray();
		return Arrays.stream(lowHalves)
			.parallel()
			.flatMap(low -> LongStream.range(0, 1L << 16)
				.map(high -> (high << 32) | low)
				.filter(seed -> matchesAll(seed, constraints)))
			.sorted()
			.toArray();
	}

	/** RandomSource.createThreadLocalInstance(seed).nextLong() & 65535: picks the End pillars' order. */
	public static int pillarOrder(long seed) {
		long state = (seed ^ MULTIPLIER) & MASK_48;
		state = (state * MULTIPLIER + ADDEND) & MASK_48;
		state = (state * MULTIPLIER + ADDEND) & MASK_48;
		// nextLong() is (next(32) << 32) + next(32); its low 16 bits are the second call's.
		return (int) (state >>> 16) & 0xFFFF;
	}

	/** Full 64-bit seeds with these lower 48 bits whose hash matches the one the server sent. */
	public static long[] worldSeeds(long structureSeed, long hashedSeed, LongUnaryOperator seedHash) {
		return LongStream.range(0, 1L << 16)
			.parallel()
			.map(top -> (top << 48) | structureSeed)
			.filter(seed -> seedHash.applyAsLong(seed) == hashedSeed)
			.toArray();
	}

	/** Whether these lower 48 bits fit every observation. For tests. */
	static boolean matches(long structureSeed, List<Observation> observations) {
		List<Constraint> constraints = new ArrayList<>();
		for (Observation observation : observations) {
			Constraint constraint = toConstraint(observation);
			if (constraint == null) {
				return false;
			}
			constraints.add(constraint);
		}
		return matchesAll(structureSeed, constraints);
	}

	/** Whether vanilla's 1% check passes for this chunk, given the lower 48 bits. For tests. */
	static boolean passesChance(long structureSeed, StructureType type, int chunkX, int chunkZ) {
		return matchesAll(structureSeed, List.of(toConstraint(new Observation(type, chunkX, chunkZ))));
	}

	private static Constraint toConstraint(Observation observation) {
		StructureType type = observation.type();
		if (type.kind == Kind.PILLARS) {
			return new Constraint(Kind.PILLARS, 0, 1, observation.chunkX(), 0, 1f);
		}
		if (type.kind == Kind.CHANCE) {
			long seedOffset = observation.chunkX() * REGION_X_MULTIPLIER + observation.chunkZ() * REGION_Z_MULTIPLIER + type.salt;
			return new Constraint(Kind.CHANCE, seedOffset, 1, 0, 0, type.chance);
		}
		int regionX = Math.floorDiv(observation.chunkX(), type.spacing);
		int regionZ = Math.floorDiv(observation.chunkZ(), type.spacing);
		int offsetX = observation.chunkX() - regionX * type.spacing;
		int offsetZ = observation.chunkZ() - regionZ * type.spacing;
		if (offsetX >= type.bound() || offsetZ >= type.bound()) {
			return null;
		}
		long seedOffset = regionX * REGION_X_MULTIPLIER + regionZ * REGION_Z_MULTIPLIER + type.salt;
		return new Constraint(type.kind, seedOffset, type.bound(), offsetX, offsetZ, 1f);
	}

	private static boolean matchesLowBits(long low, List<Constraint> constraints) {
		for (Constraint c : constraints) {
			int knownBits = lowBitsPerAxis(c.kind(), c.bound());
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
			if (c.kind() == Kind.PILLARS) {
				if (pillarOrder(seed) != c.offsetX()) {
					return false;
				}
				continue;
			}
			long state = ((seed + c.seedOffset()) ^ MULTIPLIER) & MASK_48;
			if (c.kind() == Kind.CHANCE) {
				// nextFloat(): next(24) / 2^24.
				state = (state * MULTIPLIER + ADDEND) & MASK_48;
				if (!((int) (state >>> 24) * FLOAT_UNIT < c.chance())) {
					return false;
				}
				continue;
			}
			if (c.kind() == Kind.TRIANGULAR) {
				// Each axis is the average of two rolls, x first.
				long a = nextInt(state, c.bound());
				long b = nextInt(a & MASK_48, c.bound());
				if ((int) (((a >>> 48) + (b >>> 48)) / 2) != c.offsetX()) {
					return false;
				}
				a = nextInt(b & MASK_48, c.bound());
				b = nextInt(a & MASK_48, c.bound());
				if ((int) (((a >>> 48) + (b >>> 48)) / 2) != c.offsetZ()) {
					return false;
				}
				continue;
			}
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
