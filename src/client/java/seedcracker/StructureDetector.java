package seedcracker;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/**
 * Finds structure start chunks from the blocks in a loaded chunk.
 *
 * <p>All three structures place their piece at the start chunk's minimum corner. Jungle temples
 * (12x15) and swamp huts (7x9) fit inside that chunk, so any of their marker blocks gives the
 * chunk. Desert pyramids are 21x21, so the marker used is the trap's pressure plate at the centre,
 * 10 blocks from the corner, which is always in the start chunk. Each structure needs a
 * combination of blocks, so a single player-placed block is not enough to count.
 */
final class StructureDetector {
	private StructureDetector() {}

	static List<Observation> scan(LevelChunk chunk) {
		boolean stickyPiston = false, tripwireHook = false, mossyCobblestone = false, chiseledStoneBricks = false;
		boolean pottedRedMushroom = false, cauldron = false, sprucePlanks = false;
		boolean pyramidTrap = false;

		LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section.hasOnlyAir() || !section.maybeHas(StructureDetector::isInteresting)) {
				continue;
			}
			int sectionMinY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!isInteresting(state)) {
							continue;
						}
						if (state.is(Blocks.STICKY_PISTON)) stickyPiston = true;
						else if (state.is(Blocks.TRIPWIRE_HOOK)) tripwireHook = true;
						else if (state.is(Blocks.MOSSY_COBBLESTONE)) mossyCobblestone = true;
						else if (state.is(Blocks.CHISELED_STONE_BRICKS)) chiseledStoneBricks = true;
						else if (state.is(Blocks.POTTED_RED_MUSHROOM)) pottedRedMushroom = true;
						else if (state.is(Blocks.CAULDRON)) cauldron = true;
						else if (state.is(Blocks.SPRUCE_PLANKS)) sprucePlanks = true;
						else if (state.is(Blocks.STONE_PRESSURE_PLATE) && hasTntBelow(chunk, x, sectionMinY + y, z)) pyramidTrap = true;
					}
				}
			}
		}

		ChunkPos pos = chunk.getPos();
		List<Observation> found = new ArrayList<>();
		if ((stickyPiston || tripwireHook) && mossyCobblestone && chiseledStoneBricks) {
			found.add(new Observation(StructureType.JUNGLE_TEMPLE, pos.x(), pos.z()));
		}
		if (pottedRedMushroom && cauldron && sprucePlanks) {
			found.add(new Observation(StructureType.SWAMP_HUT, pos.x(), pos.z()));
		}
		if (pyramidTrap) {
			found.add(new Observation(StructureType.DESERT_PYRAMID, pos.x(), pos.z()));
		}
		found.removeIf(observation -> !SeedSolver.isPossible(observation));
		return found;
	}

	private static boolean isInteresting(BlockState state) {
		return state.is(Blocks.STICKY_PISTON) || state.is(Blocks.TRIPWIRE_HOOK) || state.is(Blocks.MOSSY_COBBLESTONE)
			|| state.is(Blocks.CHISELED_STONE_BRICKS) || state.is(Blocks.POTTED_RED_MUSHROOM) || state.is(Blocks.CAULDRON)
			|| state.is(Blocks.SPRUCE_PLANKS) || state.is(Blocks.STONE_PRESSURE_PLATE);
	}

	/** The pyramid's pressure plate sits a couple of blocks above its TNT. */
	private static boolean hasTntBelow(LevelChunk chunk, int x, int y, int z) {
		ChunkPos pos = chunk.getPos();
		for (int down = 1; down <= 3; down++) {
			if (chunk.getBlockState(pos.getBlockAt(x, y - down, z)).is(Blocks.TNT)) {
				return true;
			}
		}
		return false;
	}
}
