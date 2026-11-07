package seedcracker;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import seedcracker.SeedSolver.Observation;
import seedcracker.mixin.BiomeManagerAccess;

public final class SeedCrackerClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("seedcracker");
	private static final ExecutorService SOLVER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Seed Cracker");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	/** Touched only on the client thread. */
	private static final Set<Observation> OBSERVATIONS = new LinkedHashSet<>();
	private static boolean solving;
	private static boolean solveAgain;
	private static int solvedWithCount;
	private static volatile Long foundSeed;

	@Override
	public void onInitializeClient() {
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (level.dimension() != Level.OVERWORLD) {
				return;
			}
			for (Observation observation : StructureDetector.scan(level, chunk)) {
				addObservation(observation);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(SeedCrackerClient::reset));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
	}

	public static List<Observation> observations() {
		return List.copyOf(OBSERVATIONS);
	}

	public static Long foundSeed() {
		return foundSeed;
	}

	public static boolean isSolving() {
		return solving;
	}

	private static void addObservation(Observation observation) {
		if (foundSeed != null || !OBSERVATIONS.add(observation)) {
			return;
		}
		message(Component.literal(String.format("Found %s at chunk %d, %d (%s)",
			withArticle(observation.type().displayName), observation.chunkX(), observation.chunkZ(), progress())));
		if (information().isEnough()) {
			startSolving();
		}
	}

	private static SeedSolver.Information information() {
		return SeedSolver.information(new ArrayList<>(OBSERVATIONS));
	}

	private static String progress() {
		SeedSolver.Information info = information();
		return String.format(Locale.ROOT, "%.1f/%.0f bits, %d/%d low bits",
			info.bits(), SeedSolver.BITS_NEEDED, info.lowBits(), SeedSolver.LOW_BITS_NEEDED);
	}

	private static String withArticle(String name) {
		return (name.startsWith("i") ? "an " : "a ") + name;
	}

	private static void startSolving() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || foundSeed != null) {
			return;
		}
		if (solving) {
			solveAgain = true;
			return;
		}
		solving = true;
		solveAgain = false;
		List<Observation> snapshot = new ArrayList<>(OBSERVATIONS);
		long hashedSeed = ((BiomeManagerAccess) mc.level.getBiomeManager()).seedcracker$hashedSeed();
		message(Component.literal("Cracking with " + snapshot.size() + " structures (" + progress() + ")..."));

		SOLVER.execute(() -> {
			long started = System.nanoTime();
			SeedSolver.Result result;
			try {
				result = SeedSolver.crack(snapshot, hashedSeed, SeedSolver::hashSeed);
			} catch (RuntimeException e) {
				LOGGER.error("Seed cracking failed", e);
				mc.execute(() -> {
					solving = false;
					message(Component.literal("Cracking failed: " + e).withStyle(ChatFormatting.RED));
				});
				return;
			}
			double seconds = (System.nanoTime() - started) / 1e9;
			mc.execute(() -> onSolved(result, snapshot.size(), seconds));
		});
	}

	private static void onSolved(SeedSolver.Result result, int count, double seconds) {
		solving = false;
		solvedWithCount = count;
		if (result.ignored() != null) {
			Observation bad = result.ignored();
			OBSERVATIONS.remove(bad);
			message(Component.literal(String.format("Ignored the %s at chunk %d, %d. It doesn't fit the others, so it may be player-built.",
				bad.type().displayName, bad.chunkX(), bad.chunkZ())).withStyle(ChatFormatting.YELLOW));
		}

		if (result.worldSeeds().length == 1) {
			long seed = result.worldSeeds()[0];
			foundSeed = seed;
			message(Component.literal(String.format("Seed found in %.1fs: ", seconds)).append(copyable(Long.toString(seed))));
			saveSeed(seed);
			notifyListeners(seed);
		} else if (result.worldSeeds().length > 1) {
			MutableComponent text = Component.literal(result.worldSeeds().length + " seeds fit. Visit more structures to narrow it down: ");
			for (long seed : result.worldSeeds()) {
				text.append(copyable(Long.toString(seed))).append(" ");
			}
			message(text);
		} else if (result.structureSeeds().length == 0) {
			message(Component.literal("No seed fits these structures. One may be player-built: check /seedcrack and remove it.")
				.withStyle(ChatFormatting.RED));
		} else if (result.structureSeeds().length > 200_000) {
			message(Component.literal("Too many possibilities to check yet. Visit another structure."));
		} else {
			message(Component.literal(String.format(
				"%d structure seeds fit, but none match the server's hashed seed. The server may hide or change it, "
					+ "or use custom structure seeds (Spigot/Paper settings).", result.structureSeeds().length))
				.withStyle(ChatFormatting.YELLOW));
		}

		if (solveAgain && foundSeed == null && OBSERVATIONS.size() > solvedWithCount) {
			startSolving();
		}
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		// Same commands under both names.
		for (String name : List.of("seedcrack", "seedcracker")) {
			dispatcher.register(commandTree(name));
		}
	}

	private static void sendInfo(FabricClientCommandSource source) {
		String[] lines = {
			"How Seed Cracker finds the seed:",
			"A world seed is 64 bits. Structures only use the lower 48 (the structure seed). The top 16 come from the hashed seed the server sends.",
			"Spread structures (desert pyramid, jungle temple, swamp hut, igloo): one per 32x32-chunk region, moved by nextInt(24) on each axis."
				+ " Each one is 2 x log2(24) = 9.2 bits, and 6 of those fall on the lowest 20 bits.",
			"Buried treasure: every chunk has a 1% chance, so each chest is log2(100) = 6.6 bits, none on the lowest 20."
				+ " Chests are read from chunk data, so there's no need to dig them up.",
			String.format(Locale.ROOT, "Step 1 tries all 2^20 values of the lowest 20 bits and needs %d low bits (3 spread structures)."
				+ " Step 2 tries 2^28 values for the rest and needs about %.0f bits in total (5 structures, or 3 plus 3 treasures).",
				SeedSolver.LOW_BITS_NEEDED, SeedSolver.BITS_NEEDED),
			"Java's random generator makes similar seeds give similar results, so structure bits overlap. Measured median 48-bit"
				+ " candidates: about 10,000 with 5 structures, 1,250 with 6, 500 with 8. Treasure overlaps much less:"
				+ " 3 structures plus 3 treasures leaves about 30. Fewer candidates means a faster step 3.",
			"Step 3 tries the 65,536 possible top 16 bits against the server's SHA-256 hashed seed, which leaves one seed.",
		};
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}

		Map<SeedSolver.StructureType, Integer> counts = new EnumMap<>(SeedSolver.StructureType.class);
		for (Observation observation : OBSERVATIONS) {
			counts.merge(observation.type(), 1, Integer::sum);
		}
		source.sendFeedback(Component.literal("Found so far:").withStyle(ChatFormatting.GOLD));
		for (SeedSolver.StructureType type : SeedSolver.StructureType.values()) {
			int count = counts.getOrDefault(type, 0);
			source.sendFeedback(Component.literal(String.format(Locale.ROOT, "  %s: %d x %.1f bits = %.1f bits",
				type.displayName, count, type.bits(), count * type.bits())));
		}

		SeedSolver.Information info = information();
		source.sendFeedback(Component.literal("Total: " + progress()).withStyle(ChatFormatting.GOLD));
		if (foundSeed != null) {
			source.sendFeedback(Component.literal("Seed found: ").append(copyable(Long.toString(foundSeed))));
		} else if (info.isEnough()) {
			source.sendFeedback(Component.literal(solving ? "Enough data, cracking now." : "Enough data. Use /seedcracker crack to try again."));
		} else {
			int missingLow = Math.max(0, SeedSolver.LOW_BITS_NEEDED - info.lowBits());
			double missingBits = Math.max(0, SeedSolver.BITS_NEEDED - info.bits());
			int spreadForLow = (int) Math.ceil(missingLow / 6.0);
			int spreadForBits = (int) Math.ceil(missingBits / SeedSolver.StructureType.IGLOO.bits());
			int treasuresForBits = (int) Math.ceil(missingBits / SeedSolver.StructureType.BURIED_TREASURE.bits());
			String need = spreadForLow > 0
				? String.format(Locale.ROOT, "Still need %d more spread structure%s for the low bits", spreadForLow, spreadForLow == 1 ? "" : "s")
				: String.format(Locale.ROOT, "Still need %.1f more bits: %d more spread structure%s or %d treasure%s",
					missingBits, spreadForBits, spreadForBits == 1 ? "" : "s", treasuresForBits, treasuresForBits == 1 ? "" : "s");
			source.sendFeedback(Component.literal(need + "."));
		}
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> commandTree(String name) {
		return literal(name)
			.executes(context -> {
				FabricClientCommandSource source = context.getSource();
				if (foundSeed != null) {
					source.sendFeedback(Component.literal("Seed: ").append(copyable(Long.toString(foundSeed))));
				}
				source.sendFeedback(Component.literal(String.format("%d found (%s)%s. /%s info explains the numbers.",
					OBSERVATIONS.size(), progress(), solving ? ", cracking now" : "", name)));
				int index = 1;
				for (Observation observation : OBSERVATIONS) {
					source.sendFeedback(Component.literal(String.format("  %d. %s at chunk %d, %d (block %d, %d)", index++,
						observation.type().displayName, observation.chunkX(), observation.chunkZ(),
						observation.chunkX() * 16, observation.chunkZ() * 16)));
				}
				return Command.SINGLE_SUCCESS;
			})
			.then(literal("info").executes(context -> {
				sendInfo(context.getSource());
				return Command.SINGLE_SUCCESS;
			}))
			.then(literal("crack").executes(context -> {
				if (!information().isEnough()) {
					context.getSource().sendError(Component.literal("Not enough data yet (" + progress() + "). See /" + name + " info."));
					return 0;
				}
				foundSeed = null;
				startSolving();
				return Command.SINGLE_SUCCESS;
			}))
			.then(literal("remove").then(argument("number", IntegerArgumentType.integer(1)).executes(context -> {
				int number = IntegerArgumentType.getInteger(context, "number");
				List<Observation> list = new ArrayList<>(OBSERVATIONS);
				if (number > list.size()) {
					context.getSource().sendError(Component.literal("There is no structure " + number + "."));
					return 0;
				}
				OBSERVATIONS.remove(list.get(number - 1));
				context.getSource().sendFeedback(Component.literal("Removed structure " + number + "."));
				return Command.SINGLE_SUCCESS;
			})))
			.then(literal("clear").executes(context -> {
				reset();
				context.getSource().sendFeedback(Component.literal("Cleared all structures and the found seed."));
				return Command.SINGLE_SUCCESS;
			}));
	}

	private static void reset() {
		OBSERVATIONS.clear();
		StructureDetector.reset();
		foundSeed = null;
		solveAgain = false;
	}

	private static MutableComponent copyable(String text) {
		return Component.literal(text).withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.CopyToClipboard(text)));
	}

	private static void message(Component text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.empty()
				.append(Component.literal("[Seed Cracker] ").withStyle(ChatFormatting.GOLD))
				.append(text));
		}
		LOGGER.info(text.getString());
	}

	/**
	 * Other mods can receive the seed by declaring a "seedcracker:seed_found" entrypoint that
	 * implements java.util.function.LongConsumer. It is called on the client thread.
	 */
	private static void notifyListeners(long seed) {
		for (EntrypointContainer<LongConsumer> listener
			: FabricLoader.getInstance().getEntrypointContainers("seedcracker:seed_found", LongConsumer.class)) {
			try {
				listener.getEntrypoint().accept(seed);
			} catch (RuntimeException e) {
				LOGGER.warn("{} failed to take the seed", listener.getProvider().getMetadata().getId(), e);
			}
		}
	}

	/** Keeps a record per server in config/seedcracker-seeds.txt, so it isn't lost with the chat. */
	private static void saveSeed(long seed) {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		String where = server != null ? server.ip : "singleplayer";
		Path file = FabricLoader.getInstance().getConfigDir().resolve("seedcracker-seeds.txt");
		try {
			Files.writeString(file, LocalDateTime.now() + "  " + where + "  " + seed + System.lineSeparator(),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.warn("Could not save the seed to {}", file, e);
		}
	}
}
