package seedcracker;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/**
 * The End's ten obsidian pillars stand in a fixed circle, but which is which comes from the seed:
 * vanilla takes Random(seed).nextLong() &amp; 65535 and uses it to shuffle sizes 0-9. Size n is
 * 76 + 3n tall. Reading the heights gives the shuffle, and trying all 65536 values finds the
 * number behind it: 16 bits of the seed in one go.
 */
public final class EndPillars {
	private static Map<List<Integer>, Integer> ORDERS;
	private static boolean done;
	/** What the last look saw, for working out a miss. */
	public static String lastSeen = "not looked yet";

	private EndPillars() {}

	static void reset() {
		done = false;
	}

	/** The pillars, once all ten are loaded and look untouched; null otherwise. */
	static Observation check(Level level) {
		if (done) {
			return null;
		}
		int[] sizes = new int[10];
		int[] heights = new int[10];
		for (int i = 0; i < 10; i++) {
			double angle = 2.0 * (-Math.PI + 0.3141592653589793 * i);
			int x = Mth.floor(42.0 * Math.cos(angle));
			int z = Mth.floor(42.0 * Math.sin(angle));
			if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
				return null;
			}
			int top = topObsidian(level, x, z);
			// The pillar is obsidian below its height, with bedrock on top.
			int height = top + 1;
			heights[i] = top == Integer.MIN_VALUE ? -1 : height;
			if (top == Integer.MIN_VALUE || (height - 76) % 3 != 0 || height < 76 || height > 76 + 27) {
				// Maybe the blocks aren't all in yet; look again later.
				lastSeen = "pillar " + i + " at " + x + ", " + z + ": heights so far " + java.util.Arrays.toString(heights);
				return null;
			}
			sizes[i] = (height - 76) / 3;
		}
		Integer order = orders().get(IntStream.of(sizes).boxed().toList());
		lastSeen = "heights " + java.util.Arrays.toString(heights) + ", order " + order;
		// All ten read cleanly: the answer won't change, so stop looking.
		done = true;
		return order == null ? null : new Observation(StructureType.END_PILLARS, order, 0);
	}

	private static int topObsidian(Level level, int x, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getMaxY(), z);
		for (; pos.getY() > 50; pos.move(0, -1, 0)) {
			if (level.getBlockState(pos).is(Blocks.OBSIDIAN)) {
				return pos.getY();
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Every shuffle vanilla can make, with the number that makes it. Shuffles that two numbers make are left out. */
	private static synchronized Map<List<Integer>, Integer> orders() {
		if (ORDERS == null) {
			Map<List<Integer>, Integer> orders = new HashMap<>();
			Map<List<Integer>, Boolean> clash = new HashMap<>();
			for (int order = 0; order < 65536; order++) {
				IntArrayList shuffled = Util.toShuffledList(IntStream.range(0, 10), RandomSource.createThreadLocalInstance(order));
				List<Integer> key = List.copyOf(shuffled);
				if (orders.putIfAbsent(key, order) != null) {
					clash.put(key, true);
				}
			}
			clash.keySet().forEach(orders::remove);
			ORDERS = orders;
		}
		return ORDERS;
	}
}
