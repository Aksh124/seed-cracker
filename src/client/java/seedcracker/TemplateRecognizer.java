package seedcracker;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/**
 * Recognises structures by matching the game's own structure templates (the .nbt files in the
 * Minecraft jar) against the world, in every rotation and mirror vanilla uses.
 *
 * <p>Each structure's first piece is placed at a fixed spot relative to its start chunk, so a
 * template only counts if, once lined up with the blocks, that spot lands on the chunk grid. Then
 * most of its blocks must be there too. Together that rules out player builds and lookalikes.
 *
 * <ul>
 *   <li><b>Ruined portals:</b> the template's corner is the start chunk's corner, rotated and
 *       maybe mirrored around the template's centre. Matched on obsidian (or crying obsidian).</li>
 *   <li><b>Villages:</b> the town centre's corner is the start chunk's corner, rotated around it.
 *       Matched from the bell.</li>
 *   <li><b>Ancient cities:</b> the centre's "city_anchor" jigsaw sits on the start chunk's corner.
 *       Matched on reinforced deepslate.</li>
 *   <li><b>Woodland mansions:</b> the entrance is 9 blocks (rotated) west of block 7, 7 of the
 *       start chunk.</li>
 *   <li><b>End cities:</b> the base floor's corner is block 7, 7 of the start chunk.</li>
 * </ul>
 */
public final class TemplateRecognizer {
	private static final Logger LOGGER = LoggerFactory.getLogger("seedcracker");
	/** Hypotheses waiting for the chunks they cover to load. */
	private static final int MAX_PENDING = 4000;

	private record TemplateBlock(BlockPos pos, Block block, String jigsawName) {}

	private record Template(String path, int sizeX, int sizeZ, List<TemplateBlock> blocks) {}

	/** One template in one placement, relative to where its origin lands in the world. */
	private record Shape(StructureType type, String name, List<BlockPos> anchors, Block anchorBlock, List<BlockPos> checks,
		List<Block> expected, BlockPos startPoint, int startRemainder, BlockPos min, BlockPos max, double threshold,
		List<BlockPos> extras, List<Block> extraExpected, double extraThreshold) {}

	private record Hypothesis(Shape shape, BlockPos origin) {}

	private record Kind(StructureType type, Set<ResourceKey<Level>> dimensions, List<Shape> shapes) {}

	private static List<Kind> kinds;
	private static Set<Block> anchorBlocks = Set.of();
	private static final Set<Hypothesis> TRIED = new HashSet<>();
	/** Hypotheses still to check, with how many times each has failed. Blocks can arrive late, so a failure is retried. */
	private static final Map<Hypothesis, Integer> PENDING = new java.util.LinkedHashMap<>();
	private static final int RETRIES = 40;

	private TemplateRecognizer() {}

	static void reset() {
		TRIED.clear();
		PENDING.clear();
	}

	static boolean isAnchor(Block block) {
		load();
		return anchorBlocks.contains(block);
	}

	/** Anchor blocks found in a newly loaded chunk, plus any earlier ones now fully loaded. */
	static List<Observation> check(Level level, List<BlockPos> anchors) {
		load();
		List<Observation> found = new ArrayList<>();
		for (BlockPos anchor : anchors) {
			Block block = level.getBlockState(anchor).getBlock();
			for (Kind kind : kinds) {
				if (!kind.dimensions().contains(level.dimension())) {
					continue;
				}
				for (Shape shape : kind.shapes()) {
					if (shape.anchorBlock() != block && !(shape.anchorBlock() == Blocks.OBSIDIAN && block == Blocks.CRYING_OBSIDIAN)) {
						continue;
					}
					for (BlockPos local : shape.anchors()) {
						BlockPos origin = anchor.subtract(local);
						BlockPos start = origin.offset(shape.startPoint());
						if (Math.floorMod(start.getX(), 16) != shape.startRemainder() || Math.floorMod(start.getZ(), 16) != shape.startRemainder()) {
							continue;
						}
						Hypothesis hypothesis = new Hypothesis(shape, origin);
						if (TRIED.add(hypothesis)) {
							PENDING.put(hypothesis, 0);
						}
					}
				}
			}
		}

		for (Iterator<Map.Entry<Hypothesis, Integer>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Hypothesis, Integer> entry = it.next();
			Hypothesis hypothesis = entry.getKey();
			Boolean matches = matches(level, hypothesis);
			if (matches == null) {
				continue;
			}
			if (!matches) {
				if (entry.getValue() >= RETRIES) {
					it.remove();
				} else {
					entry.setValue(entry.getValue() + 1);
				}
				continue;
			}
			it.remove();
			{
				Shape shape = hypothesis.shape();
				BlockPos start = hypothesis.origin().offset(shape.startPoint());
				found.add(new Observation(shape.type(), Math.floorDiv(start.getX() - shape.startRemainder(), 16),
					Math.floorDiv(start.getZ() - shape.startRemainder(), 16)));
			}
		}
		while (PENDING.size() > MAX_PENDING) {
			PENDING.remove(PENDING.keySet().iterator().next());
		}
		return found;
	}

	/** Whether enough of the template is there, or null if part of it isn't loaded yet. */
	private static Boolean matches(Level level, Hypothesis hypothesis) {
		Shape shape = hypothesis.shape();
		BlockPos min = hypothesis.origin().offset(shape.min());
		BlockPos max = hypothesis.origin().offset(shape.max());
		for (int cx = SectionPos.blockToSectionCoord(min.getX()); cx <= SectionPos.blockToSectionCoord(max.getX()); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(min.getZ()); cz <= SectionPos.blockToSectionCoord(max.getZ()); cz++) {
				if (!level.hasChunk(cx, cz)) {
					return null;
				}
			}
		}
		int needed = (int) Math.ceil(shape.checks().size() * shape.threshold());
		int missesAllowed = shape.checks().size() - needed;
		int matched = 0;
		int missed = 0;
		for (int i = 0; i < shape.checks().size(); i++) {
			BlockState actual = level.getBlockState(hypothesis.origin().offset(shape.checks().get(i)));
			if (same(shape.expected().get(i), actual.getBlock())) {
				matched++;
			} else if (++missed > missesAllowed) {
				return false;
			}
		}
		if (matched < needed) {
			return false;
		}
		return extraRatio(level, hypothesis.origin(), shape) >= shape.extraThreshold();
	}

	/** How much of the second check is there: for portals, their stone and netherrack around the frame. */
	private static double extraRatio(Level level, BlockPos origin, Shape shape) {
		if (shape.extras().isEmpty()) {
			return 1;
		}
		int matched = 0;
		for (int i = 0; i < shape.extras().size(); i++) {
			if (group(shape.extraExpected().get(i)).equals(group(level.getBlockState(origin.offset(shape.extras().get(i))).getBlock()))) {
				matched++;
			}
		}
		return (double) matched / shape.extras().size();
	}

	/** Loose families: ruined portals age their stone and turn it to blackstone in the Nether. */
	private static String group(Block block) {
		String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (id.contains("netherrack") || id.contains("magma")) {
			return "nether";
		}
		if (id.contains("stone") || id.contains("blackstone")) {
			return "stone";
		}
		return id;
	}

	private static boolean isPortalExtra(Block block) {
		String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return id.contains("stone") || id.contains("netherrack");
	}

	/**
	 * For working out a miss: every placement of this type whose start lands on the given chunk,
	 * at every height, and how much of it matched at best.
	 */
	public static String diagnose(Level level, StructureType type, int chunkX, int chunkZ) {
		load();
		String best = "nothing";
		double bestRatio = -1;
		for (Kind kind : kinds) {
			if (kind.type() != type) {
				continue;
			}
			for (Shape shape : kind.shapes()) {
				BlockPos start = new BlockPos(chunkX * 16 + shape.startRemainder(), 0, chunkZ * 16 + shape.startRemainder());
				for (int y = level.getMinY(); y < level.getMaxY(); y++) {
					BlockPos origin = start.subtract(shape.startPoint()).atY(y - shape.startPoint().getY());
					int matched = 0;
					for (int i = 0; i < shape.checks().size(); i++) {
						if (same(shape.expected().get(i), level.getBlockState(origin.offset(shape.checks().get(i))).getBlock())) {
							matched++;
						}
					}
					double ratio = (double) matched / shape.checks().size();
					if (ratio > bestRatio) {
						bestRatio = ratio;
						best = String.format(java.util.Locale.ROOT, "%s at y %d: %d/%d (%.0f%%, needs %.0f%%), surroundings %.0f%% (needs %.0f%%)",
							shape.name(), y, matched, shape.checks().size(), ratio * 100, shape.threshold() * 100,
							extraRatio(level, origin, shape) * 100, shape.extraThreshold() * 100)
							+ "; tried " + TRIED.contains(new Hypothesis(shape, origin)) + ", pending " + PENDING.get(new Hypothesis(shape, origin))
							+ ", result " + matches(level, new Hypothesis(shape, origin)) + ", anchor block " + shape.anchorBlock()
							+ " x" + shape.anchors().size() + ", in world: " + shape.anchors().stream().filter(a -> level.getBlockState(origin.offset(a)).getBlock() == shape.anchorBlock()).count();
					}
				}
			}
		}
		return best;
	}

	/** Same block, allowing for what vanilla's processors and aging do to it. */
	private static boolean same(Block expected, Block actual) {
		if (expected == actual) {
			return true;
		}
		return normalize(expected) == normalize(actual);
	}

	private static Block normalize(Block block) {
		if (block == Blocks.CRYING_OBSIDIAN) return Blocks.OBSIDIAN;
		if (block == Blocks.MOSSY_COBBLESTONE) return Blocks.COBBLESTONE;
		if (block == Blocks.MOSSY_STONE_BRICKS || block == Blocks.CRACKED_STONE_BRICKS) return Blocks.STONE_BRICKS;
		if (block == Blocks.WALL_TORCH) return Blocks.TORCH;
		return block;
	}

	// Loading

	private static synchronized void load() {
		if (kinds != null) {
			return;
		}
		List<Kind> loaded = new ArrayList<>();
		Set<ResourceKey<Level>> overworld = Set.of(Level.OVERWORLD);
		// Each kind on its own, so one bad template only loses that kind.
		addKind(loaded, StructureType.RUINED_PORTAL, Set.of(Level.OVERWORLD, Level.NETHER), TemplateRecognizer::ruinedPortals);
		addKind(loaded, StructureType.VILLAGE, overworld, TemplateRecognizer::villages);
		addKind(loaded, StructureType.ANCIENT_CITY, overworld, TemplateRecognizer::ancientCities);
		addKind(loaded, StructureType.WOODLAND_MANSION, overworld, TemplateRecognizer::mansions);
		addKind(loaded, StructureType.END_CITY, Set.of(Level.END), TemplateRecognizer::endCities);
		Set<Block> anchors = new HashSet<>();
		for (Kind kind : loaded) {
			for (Shape shape : kind.shapes()) {
				anchors.add(shape.anchorBlock());
				if (shape.anchorBlock() == Blocks.OBSIDIAN) {
					anchors.add(Blocks.CRYING_OBSIDIAN);
				}
			}
		}
		anchorBlocks = Set.copyOf(anchors);
		kinds = List.copyOf(loaded);
	}

	private interface ShapeLoader {
		List<Shape> load() throws IOException;
	}

	private static void addKind(List<Kind> kinds, StructureType type, Set<ResourceKey<Level>> dimensions, ShapeLoader loader) {
		try {
			kinds.add(new Kind(type, dimensions, loader.load()));
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Couldn't load the {} templates, so they won't be found", type.displayName, e);
		}
	}

	private static List<Shape> ruinedPortals() throws IOException {
		List<Shape> shapes = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (int i = 1; i <= 10; i++) names.add("ruined_portal/portal_" + i);
		for (int i = 1; i <= 3; i++) names.add("ruined_portal/giant_portal_" + i);
		for (String name : names) {
			Template template = template(name);
			BlockPos pivot = new BlockPos(template.sizeX() / 2, 0, template.sizeZ() / 2);
			for (Mirror mirror : new Mirror[] {Mirror.NONE, Mirror.FRONT_BACK}) {
				for (Rotation rotation : Rotation.values()) {
					shapes.add(shape(StructureType.RUINED_PORTAL, template, mirror, rotation, pivot, Blocks.OBSIDIAN,
						block -> block == Blocks.OBSIDIAN, BlockPos.ZERO, 0, 0.7, TemplateRecognizer::isPortalExtra, 0.4));
				}
			}
		}
		return shapes;
	}

	private static List<Shape> villages() throws IOException {
		Map<String, Integer> centres = Map.of("plains", 3, "desert", 3, "savanna", 4, "snowy", 3, "taiga", 2);
		List<String> names = new ArrayList<>();
		for (Map.Entry<String, Integer> biome : centres.entrySet()) {
			for (String zombie : new String[] {"", "zombie/"}) {
				for (int i = 1; i <= biome.getValue(); i++) {
					names.add("village/" + biome.getKey() + "/" + zombie + "town_centers/" + biome.getKey() + "_meeting_point_" + i);
				}
			}
		}
		names.add("village/plains/town_centers/plains_fountain_01");
		names.add("village/plains/zombie/town_centers/plains_fountain_01");
		List<Shape> shapes = new ArrayList<>();
		for (String name : names) {
			Template template = template(name);
			// Nearly every town centre has a bell; for the rest, their rarest block.
			Block anchor = template.blocks().stream().anyMatch(block -> block.block() == Blocks.BELL) ? Blocks.BELL : rarestBlock(template);
			for (Rotation rotation : Rotation.values()) {
				shapes.add(shape(StructureType.VILLAGE, template, Mirror.NONE, rotation, BlockPos.ZERO, anchor,
					TemplateRecognizer::isSolidTemplateBlock, BlockPos.ZERO, 0, 0.5, null, 0));
			}
		}
		return shapes;
	}

	private static List<Shape> ancientCities() throws IOException {
		List<Shape> shapes = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			Template template = template("ancient_city/city_center/city_center_" + i);
			BlockPos anchor = template.blocks().stream().filter(block -> "minecraft:city_anchor".equals(block.jigsawName()))
				.findFirst().orElseThrow().pos();
			for (Rotation rotation : Rotation.values()) {
				BlockPos start = StructureTemplate.transform(anchor, Mirror.NONE, rotation, BlockPos.ZERO);
				shapes.add(shape(StructureType.ANCIENT_CITY, template, Mirror.NONE, rotation, BlockPos.ZERO, Blocks.REINFORCED_DEEPSLATE,
					block -> block == Blocks.REINFORCED_DEEPSLATE, start, 0, 0.9, null, 0));
			}
		}
		return shapes;
	}

	private static List<Shape> mansions() throws IOException {
		Template template = template("woodland_mansion/entrance");
		List<Shape> shapes = new ArrayList<>();
		for (Rotation rotation : Rotation.values()) {
			// The entrance goes 9 blocks (rotated) west of the start point, so the start point is 9 east of it.
			Direction west = rotation.rotate(Direction.WEST);
			BlockPos start = BlockPos.ZERO.relative(west.getOpposite(), 9);
			shapes.add(shape(StructureType.WOODLAND_MANSION, template, Mirror.NONE, rotation, BlockPos.ZERO, Blocks.CARPET.white(),
				TemplateRecognizer::isSolidTemplateBlock, start, 7, 0.7, null, 0));
		}
		return shapes;
	}

	private static List<Shape> endCities() throws IOException {
		Template template = template("end_city/base_floor");
		List<Shape> shapes = new ArrayList<>();
		for (Rotation rotation : Rotation.values()) {
			shapes.add(shape(StructureType.END_CITY, template, Mirror.NONE, rotation, BlockPos.ZERO, Blocks.PURPUR_PILLAR,
				TemplateRecognizer::isSolidTemplateBlock, BlockPos.ZERO, 7, 0.7, null, 0));
		}
		return shapes;
	}

	private static Block rarestBlock(Template template) {
		Map<Block, Integer> counts = new java.util.HashMap<>();
		for (TemplateBlock block : template.blocks()) {
			if (isSolidTemplateBlock(block.block())) {
				counts.merge(block.block(), 1, Integer::sum);
			}
		}
		return counts.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
	}

	/** Blocks the template really places: not air, markers, or jigsaws (replaced when placed). */
	private static boolean isSolidTemplateBlock(Block block) {
		return block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.STRUCTURE_VOID && block != Blocks.JIGSAW
			&& block != Blocks.STRUCTURE_BLOCK;
	}

	private static Shape shape(StructureType type, Template template, Mirror mirror, Rotation rotation, BlockPos pivot, Block anchorBlock,
		java.util.function.Predicate<Block> checked, BlockPos start, int startRemainder, double threshold,
		java.util.function.Predicate<Block> extraChecked, double extraThreshold) {
		List<BlockPos> extras = new ArrayList<>();
		List<Block> extraExpected = new ArrayList<>();
		List<BlockPos> anchors = new ArrayList<>();
		List<BlockPos> checks = new ArrayList<>();
		List<Block> expected = new ArrayList<>();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (TemplateBlock block : template.blocks()) {
			BlockPos pos = StructureTemplate.transform(block.pos(), mirror, rotation, pivot);
			// Only the blocks that get checked need to be loaded, not the whole template.
			if (checked.test(block.block()) || (extraChecked != null && extraChecked.test(block.block()))) {
				minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
				maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
			}
			if (block.block() == anchorBlock) {
				anchors.add(pos);
			}
			if (extraChecked != null && extraChecked.test(block.block()) && !checked.test(block.block())) {
				extras.add(pos);
				extraExpected.add(block.block());
			}
			if (checked.test(block.block())) {
				checks.add(pos);
				expected.add(block.block());
			}
		}
		if (anchors.isEmpty() || checks.isEmpty()) {
			throw new IllegalStateException(template.path() + " has no " + anchorBlock);
		}
		return new Shape(type, template.path() + " " + rotation + " " + mirror, List.copyOf(anchors), anchorBlock, List.copyOf(checks),
			List.copyOf(expected), start, startRemainder, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), threshold,
			List.copyOf(extras), List.copyOf(extraExpected), extraThreshold);
	}

	/** Reads a template straight from the Minecraft jar, which holds the same files the server uses. */
	private static Template template(String path) throws IOException {
		String resource = "/data/minecraft/structure/" + path + ".nbt";
		CompoundTag tag;
		try (InputStream in = Minecraft.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("missing " + resource);
			}
			tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
		}
		ListTag palette = tag.getListOrEmpty("palette");
		ListTag blocks = tag.getListOrEmpty("blocks");
		ListTag size = tag.getListOrEmpty("size");
		List<TemplateBlock> result = new ArrayList<>(blocks.size());
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag entry = blocks.getCompoundOrEmpty(i);
			String name = palette.getCompoundOrEmpty(entry.getIntOr("state", 0)).getStringOr("Name", "minecraft:air");
			Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(name));
			ListTag pos = entry.getListOrEmpty("pos");
			String jigsawName = block == Blocks.JIGSAW ? entry.getCompoundOrEmpty("nbt").getStringOr("name", "") : null;
			result.add(new TemplateBlock(new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)), block, jigsawName));
		}
		return new Template(path, size.getIntOr(0, 0), size.getIntOr(2, 0), List.copyOf(result));
	}
}
