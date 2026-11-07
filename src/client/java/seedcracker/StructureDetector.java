package seedcracker;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/**
 * Finds structure start chunks from the blocks in a loaded chunk.
 *
 * <ul>
 *   <li><b>Jungle temples</b> (12x15) and <b>swamp huts</b> (7x9) are placed at the start chunk's
 *       minimum corner and fit inside it, so any of their marker blocks gives the chunk.</li>
 *   <li><b>Desert pyramids</b> are 21x21. The marker is the trap's pressure plate at the centre,
 *       10 blocks from the corner, which is always in the start chunk.</li>
 *   <li><b>Igloos</b> use the "igloo/top" template rotated around (3, 5, 5) and can spill into the
 *       next chunk. Its furnace (template position 1, 1, 3, facing east) shows the rotation, and
 *       undoing that rotation gives the chunk corner, which must land on a multiple of 16.</li>
 *   <li><b>Buried treasure</b> is a chest at block 9, 9 of its chunk, under sand or similar.</li>
 * </ul>
 *
 * <p>Each needs a combination of blocks, so a single player-placed block is not enough to count.
 */
final class StructureDetector {
	private static final BlockPos IGLOO_PIVOT = new BlockPos(3, 5, 5);
	private static final BlockPos IGLOO_FURNACE = new BlockPos(1, 1, 3);
	private static final BlockPos IGLOO_CRAFTING_TABLE = new BlockPos(1, 1, 5);
	private static final BlockPos IGLOO_BED_HEAD = new BlockPos(5, 1, 5);

	/** Igloo furnaces whose other blocks were in a chunk that hadn't loaded yet. Client thread only. */
	private static final Set<BlockPos> PENDING_IGLOOS = new LinkedHashSet<>();

	private StructureDetector() {}

	static void reset() {
		PENDING_IGLOOS.clear();
	}

	static List<Observation> scan(Level level, LevelChunk chunk) {
		boolean stickyPiston = false, tripwireHook = false, mossyCobblestone = false, chiseledStoneBricks = false;
		boolean pottedRedMushroom = false, cauldron = false, sprucePlanks = false;
		boolean pyramidTrap = false, treasure = false;
		List<BlockPos> furnaces = new ArrayList<>();
		ChunkPos pos = chunk.getPos();

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
						int blockY = sectionMinY + y;
						if (state.is(Blocks.STICKY_PISTON)) stickyPiston = true;
						else if (state.is(Blocks.TRIPWIRE_HOOK)) tripwireHook = true;
						else if (state.is(Blocks.MOSSY_COBBLESTONE)) mossyCobblestone = true;
						else if (state.is(Blocks.CHISELED_STONE_BRICKS)) chiseledStoneBricks = true;
						else if (state.is(Blocks.POTTED_RED_MUSHROOM)) pottedRedMushroom = true;
						else if (state.is(Blocks.CAULDRON)) cauldron = true;
						else if (state.is(Blocks.SPRUCE_PLANKS)) sprucePlanks = true;
						else if (state.is(Blocks.STONE_PRESSURE_PLATE) && hasTntBelow(chunk, x, blockY, z)) pyramidTrap = true;
						else if (state.is(Blocks.FURNACE)) furnaces.add(pos.getBlockAt(x, blockY, z));
						else if (state.is(Blocks.CHEST) && x == 9 && z == 9 && isBuried(chunk, x, blockY, z)) treasure = true;
					}
				}
			}
		}

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
		if (treasure) {
			found.add(new Observation(StructureType.BURIED_TREASURE, pos.x(), pos.z()));
		}

		PENDING_IGLOOS.addAll(furnaces);
		for (Iterator<BlockPos> it = PENDING_IGLOOS.iterator(); it.hasNext(); ) {
			IglooCheck check = checkIgloo(level, it.next());
			if (check != IglooCheck.WAITING) {
				it.remove();
			}
			if (check instanceof IglooCheck.Found igloo) {
				found.add(igloo.observation());
			}
		}

		found.removeIf(observation -> !SeedSolver.isPossible(observation));
		return found;
	}

	private sealed interface IglooCheck {
		IglooCheck WAITING = new Waiting();
		IglooCheck NOT_IGLOO = new NotIgloo();

		record Waiting() implements IglooCheck {}

		record NotIgloo() implements IglooCheck {}

		record Found(Observation observation) implements IglooCheck {}
	}

	/** Works back from a furnace to the igloo's start chunk, checking the other blocks agree. */
	private static IglooCheck checkIgloo(Level level, BlockPos furnace) {
		BlockState state = level.getBlockState(furnace);
		if (!state.is(Blocks.FURNACE)) {
			return IglooCheck.NOT_IGLOO;
		}
		Direction facing = state.getValue(AbstractFurnaceBlock.FACING);
		for (Rotation rotation : Rotation.values()) {
			if (rotation.rotate(Direction.EAST) != facing) {
				continue;
			}
			BlockPos furnaceOffset = StructureTemplate.transform(IGLOO_FURNACE, Mirror.NONE, rotation, IGLOO_PIVOT);
			BlockPos origin = furnace.subtract(furnaceOffset);
			if (Math.floorMod(origin.getX(), 16) != 0 || Math.floorMod(origin.getZ(), 16) != 0) {
				return IglooCheck.NOT_IGLOO;
			}
			BlockPos table = origin.offset(StructureTemplate.transform(IGLOO_CRAFTING_TABLE, Mirror.NONE, rotation, IGLOO_PIVOT));
			BlockPos bed = origin.offset(StructureTemplate.transform(IGLOO_BED_HEAD, Mirror.NONE, rotation, IGLOO_PIVOT));
			if (!isLoaded(level, table) || !isLoaded(level, bed)) {
				return IglooCheck.WAITING;
			}
			if (!level.getBlockState(table).is(Blocks.CRAFTING_TABLE) || !(level.getBlockState(bed).getBlock() instanceof BedBlock)) {
				return IglooCheck.NOT_IGLOO;
			}
			return new IglooCheck.Found(new Observation(StructureType.IGLOO,
				SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ())));
		}
		return IglooCheck.NOT_IGLOO;
	}

	private static boolean isLoaded(Level level, BlockPos pos) {
		return level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
	}

	private static boolean isInteresting(BlockState state) {
		Block block = state.getBlock();
		return block == Blocks.STICKY_PISTON || block == Blocks.TRIPWIRE_HOOK || block == Blocks.MOSSY_COBBLESTONE
			|| block == Blocks.CHISELED_STONE_BRICKS || block == Blocks.POTTED_RED_MUSHROOM || block == Blocks.CAULDRON
			|| block == Blocks.SPRUCE_PLANKS || block == Blocks.STONE_PRESSURE_PLATE || block == Blocks.FURNACE
			|| block == Blocks.CHEST;
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

	/** Buried treasure is covered by the beach: sand, sandstone, gravel or stone, not air. */
	private static boolean isBuried(LevelChunk chunk, int x, int y, int z) {
		BlockState above = chunk.getBlockState(chunk.getPos().getBlockAt(x, y + 1, z));
		return above.is(Blocks.SAND) || above.is(Blocks.SANDSTONE) || above.is(Blocks.GRAVEL) || above.is(Blocks.RED_SAND)
			|| above.is(Blocks.STONE) || above.is(Blocks.DIRT) || above.is(Blocks.SUSPICIOUS_SAND);
	}
}
