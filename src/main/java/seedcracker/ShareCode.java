package seedcracker;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/**
 * Progress packed into a short code to pass to another player: "sc1." then base64url of a
 * version byte, the world's hashed seed (so a code from another world is refused), and either
 * the seed or the finds. Short enough to send in one /msg.
 */
public record ShareCode(long hashedSeed, Long seed, List<Observation> observations) {
	private static final String PREFIX = "sc1.";
	private static final Pattern FIND = Pattern.compile("sc1\\.[A-Za-z0-9_-]{12,300}");
	/** Leaves room in a 256-character chat message for "msg name " and the text around it. */
	public static final int MAX_LENGTH = 180;
	private static final int VERSION = 1;

	public String encode() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(VERSION);
		out.write(seed != null ? 1 : 0);
		out.writeBytes(ByteBuffer.allocate(8).putLong(hashedSeed).array());
		if (seed != null) {
			out.writeBytes(ByteBuffer.allocate(8).putLong(seed).array());
		} else {
			writeVarInt(out, observations.size());
			for (Observation observation : observations) {
				out.write(observation.type().ordinal());
				writeVarInt(out, zigzag(observation.chunkX()));
				writeVarInt(out, zigzag(observation.chunkZ()));
			}
		}
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
	}

	/**
	 * The code for this progress. If the seed is known it's just that; otherwise as many finds as
	 * fit, the most informative first.
	 */
	public static ShareCode of(long hashedSeed, Long seed, List<Observation> observations) {
		if (seed != null) {
			return new ShareCode(hashedSeed, seed, List.of());
		}
		List<Observation> sorted = new ArrayList<>(observations);
		sorted.sort(Comparator.comparingDouble((Observation o) -> o.type().bits()).reversed());
		List<Observation> kept = new ArrayList<>();
		for (Observation observation : sorted) {
			kept.add(observation);
			if (new ShareCode(hashedSeed, null, kept).encode().length() > MAX_LENGTH) {
				kept.removeLast();
				break;
			}
		}
		return new ShareCode(hashedSeed, null, List.copyOf(kept));
	}

	/** The first code in some text, such as a chat message, or null. */
	public static String find(String text) {
		Matcher matcher = FIND.matcher(text);
		return matcher.find() ? matcher.group() : null;
	}

	public static ShareCode decode(String text) {
		text = text.trim();
		if (!text.startsWith(PREFIX)) {
			throw new IllegalArgumentException("Not a Seed Cracker code: it should start with " + PREFIX);
		}
		ByteBuffer in;
		try {
			in = ByteBuffer.wrap(Base64.getUrlDecoder().decode(text.substring(PREFIX.length())));
			if (in.get() != VERSION) {
				throw new IllegalArgumentException("This code is from a different Seed Cracker version.");
			}
			boolean hasSeed = in.get() == 1;
			long hashedSeed = in.getLong();
			if (hasSeed) {
				return new ShareCode(hashedSeed, in.getLong(), List.of());
			}
			int count = readVarInt(in);
			List<Observation> observations = new ArrayList<>();
			StructureType[] types = StructureType.values();
			for (int i = 0; i < count; i++) {
				int type = in.get() & 0xFF;
				int x = unzigzag(readVarInt(in));
				int z = unzigzag(readVarInt(in));
				if (type < types.length) {
					observations.add(new Observation(types[type], x, z));
				}
			}
			return new ShareCode(hashedSeed, null, List.copyOf(observations));
		} catch (RuntimeException e) {
			if (e instanceof IllegalArgumentException illegal && illegal.getMessage() != null && illegal.getMessage().startsWith("This code")) {
				throw illegal;
			}
			throw new IllegalArgumentException("That code is damaged or cut off.");
		}
	}

	private static int zigzag(int value) {
		return (value << 1) ^ (value >> 31);
	}

	private static int unzigzag(int value) {
		return (value >>> 1) ^ -(value & 1);
	}

	private static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	private static int readVarInt(ByteBuffer in) {
		int value = 0;
		for (int shift = 0; shift < 35; shift += 7) {
			byte b = in.get();
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new IllegalArgumentException("bad number");
	}
}
