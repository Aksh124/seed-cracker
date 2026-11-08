package seedcracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

class ShareCodeTest {
	@Test
	void findsRoundTrip() {
		List<Observation> finds = List.of(new Observation(StructureType.RUINED_PORTAL, -1200, 37),
			new Observation(StructureType.END_PILLARS, 57473, 0), new Observation(StructureType.BURIED_TREASURE, 5, -5),
			new Observation(StructureType.WOODLAND_MANSION, 31, -74));
		ShareCode code = ShareCode.of(-123456789012345L, null, finds);
		ShareCode back = ShareCode.decode(code.encode());
		assertEquals(code, back);
		assertEquals(4, back.observations().size());
		assertNull(back.seed());
	}

	@Test
	void aKnownSeedIsAllThatsSent() {
		ShareCode code = ShareCode.of(42L, -6_421_009_337_154_802_113L, List.of(new Observation(StructureType.IGLOO, 1, 2)));
		ShareCode back = ShareCode.decode(code.encode());
		assertEquals(-6_421_009_337_154_802_113L, back.seed());
		assertTrue(back.observations().isEmpty());
		assertTrue(code.encode().length() < 40, code.encode());
	}

	@Test
	void manyFindsAreCutToFitOneMessage() {
		Random random = new Random(1);
		List<Observation> finds = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			finds.add(new Observation(StructureType.values()[random.nextInt(StructureType.values().length)],
				random.nextInt(4000) - 2000, random.nextInt(4000) - 2000));
		}
		String text = ShareCode.of(7L, null, finds).encode();
		assertTrue(text.length() <= ShareCode.MAX_LENGTH, "length " + text.length());
		// The most informative finds go first.
		List<Observation> kept = ShareCode.decode(text).observations();
		assertTrue(kept.size() > 10, "only " + kept.size());
		assertEquals(StructureType.END_PILLARS, kept.getFirst().type());
	}

	@Test
	void foundInChat() {
		String code = ShareCode.of(1L, 2L, List.of()).encode();
		assertEquals(code, ShareCode.find("Friend whispers to you: Seed Cracker progress (needs the Seed Cracker mod): " + code));
		assertNull(ShareCode.find("no code here sc1."));
	}

	@Test
	void damagedCodesAreRefused() {
		String code = ShareCode.of(1L, null, List.of(new Observation(StructureType.VILLAGE, 3, 4))).encode();
		assertThrows(IllegalArgumentException.class, () -> ShareCode.decode(code.substring(0, code.length() - 4)));
		assertThrows(IllegalArgumentException.class, () -> ShareCode.decode("hello"));
	}
}
