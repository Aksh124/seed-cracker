package seedcracker;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
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
	private static int ticks;
	/** Which server or world the progress belongs to, and its hashed seed; null when not in one. */
	private static String progressKey;
	private static long progressHashedSeed;

	@Override
	public void onInitializeClient() {
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			for (Observation observation : StructureDetector.scan(level, chunk)) {
				addObservation(observation);
			}
		});
		// Structures whose blocks arrived late get rechecked even when no new chunks are loading.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level != null && ++ticks % 40 == 0) {
				List<Observation> found = new ArrayList<>(TemplateRecognizer.check(mc.level, List.of()));
				if (mc.level.dimension() == Level.END) {
					Observation pillars = EndPillars.check(mc.level);
					if (pillars != null) {
						found.add(pillars);
					}
				}
				for (Observation observation : found) {
					if (SeedSolver.isPossible(observation)) {
						addObservation(observation);
					}
				}
			}
		});
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) ->
			onMessage(message, sender == null ? null : sender.name()));
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || onMessage(message, null));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(SeedCrackerClient::loadProgress));
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
		saveProgress();
		message(Component.literal(observation.type() == SeedSolver.StructureType.END_PILLARS
			? String.format("Read the End pillars: order %d (%s)", observation.chunkX(), progress())
			: String.format("Found %s at chunk %d, %d (%s)",
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
		return ("aeiouAEIOU".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
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
			saveProgress();
			message(Component.literal(String.format("Ignored the %s at chunk %d, %d. It doesn't fit the others, so it may be player-built.",
				bad.type().displayName, bad.chunkX(), bad.chunkZ())).withStyle(ChatFormatting.YELLOW));
		}

		if (result.worldSeeds().length == 1) {
			long seed = result.worldSeeds()[0];
			foundSeed = seed;
			saveProgress();
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
			"Other spread structures: villages (34-chunk regions, 9.4 bits, 2 low), ruined portals (40, 9.3 bits, Overworld or Nether),"
				+ " ancient cities (24, 8 bits), woodland mansions (80, 11.3 bits) and End cities (20, 5.8 bits). Mansions and End cities"
				+ " use the average of two rolls per axis, so they cluster in the middle of their region.",
			"End pillars: their heights are a shuffle picked by Random(seed).nextLong() & 65535, so reading them gives 16 bits at once."
				+ " They depend only on the lowest 32 bits, so with them step 1 tries 2^32 low values instead and needs no low-bit structures.",
			"Buried treasure: every chunk has a 1% chance, so each chest is log2(100) = 6.6 bits, none on the lowest 20."
				+ " Chests are read from chunk data, so there's no need to dig them up.",
			String.format(Locale.ROOT, "Step 1 tries all 2^20 values of the lowest 20 bits and needs %d low bits (3 temples or igloos), or uses the End pillars."
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
			int missingLow = info.pillars() ? 0 : Math.max(0, SeedSolver.LOW_BITS_NEEDED - info.lowBits());
			double missingBits = Math.max(0, SeedSolver.BITS_NEEDED - info.bits());
			int spreadForLow = (int) Math.ceil(missingLow / 6.0);
			int spreadForBits = (int) Math.ceil(missingBits / SeedSolver.StructureType.IGLOO.bits());
			int treasuresForBits = (int) Math.ceil(missingBits / SeedSolver.StructureType.BURIED_TREASURE.bits());
			String need = spreadForLow > 0
				? String.format(Locale.ROOT, "Still need %d more temple%s or igloo%s for the low bits, or the End pillars", spreadForLow,
					spreadForLow == 1 ? "" : "s", spreadForLow == 1 ? "" : "s")
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
					source.sendFeedback(Component.literal(observation.type() == SeedSolver.StructureType.END_PILLARS
						? String.format("  %d. End pillars, order %d", index++, observation.chunkX())
						: String.format("  %d. %s at chunk %d, %d (block %d, %d)", index++,
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
				saveProgress();
				context.getSource().sendFeedback(Component.literal("Removed structure " + number + "."));
				return Command.SINGLE_SUCCESS;
			})))
			.then(literal("share")
				.executes(context -> share(context.getSource(), null))
				.then(argument("player", StringArgumentType.word())
					.suggests((context, builder) -> {
						Minecraft mc = Minecraft.getInstance();
						if (mc.getConnection() != null) {
							for (var info : mc.getConnection().getOnlinePlayers()) {
								String player = info.getProfile().name();
								if (!player.equals(mc.getUser().getName()) && player.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
									builder.suggest(player);
								}
							}
						}
						return builder.buildFuture();
					})
					.executes(context -> share(context.getSource(), StringArgumentType.getString(context, "player")))))
			.then(literal("import").then(argument("code", StringArgumentType.greedyString()).executes(context -> {
				String error = importCode(StringArgumentType.getString(context, "code"), null);
				if (error != null) {
					context.getSource().sendError(Component.literal(error));
					return 0;
				}
				return Command.SINGLE_SUCCESS;
			})))
			.then(literal("clear").executes(context -> {
				Progress.delete(progressKey);
				clearFinds();
				context.getSource().sendFeedback(Component.literal("Cleared all structures and the found seed here, including the saved progress."));
				return Command.SINGLE_SUCCESS;
			}));
	}

	/** Leaving a server: forget it in memory. Its progress stays saved for next time. */
	private static void reset() {
		clearFinds();
		progressKey = null;
	}

	private static void clearFinds() {
		OBSERVATIONS.clear();
		StructureDetector.reset();
		foundSeed = null;
		solveAgain = false;
	}

	private static void saveProgress() {
		Progress.save(progressKey, progressHashedSeed, foundSeed, OBSERVATIONS);
	}

	/** Joining a server or world: pick up what was found there before. */
	private static void loadProgress() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		progressKey = Progress.key(mc);
		progressHashedSeed = ((BiomeManagerAccess) mc.level.getBiomeManager()).seedcracker$hashedSeed();
		Progress.Saved saved = Progress.load(progressKey);
		if (saved == null) {
			return;
		}
		if (saved.hashedSeed() != progressHashedSeed) {
			// Same address, different world: the server was reset. Old finds would only get in the way.
			Progress.delete(progressKey);
			message(Component.literal("This server's world has changed since you were last here, so the saved progress was cleared.")
				.withStyle(ChatFormatting.YELLOW));
			return;
		}
		OBSERVATIONS.addAll(saved.observations());
		if (saved.seed() != null) {
			foundSeed = saved.seed();
			message(Component.literal("Seed for this world, found earlier: ").append(copyable(Long.toString(foundSeed))));
			notifyListeners(foundSeed);
		} else if (!OBSERVATIONS.isEmpty()) {
			message(Component.literal(String.format("Picked up %d structure%s found here before (%s).", OBSERVATIONS.size(),
				OBSERVATIONS.size() == 1 ? "" : "s", progress())));
			if (information().isEnough()) {
				startSolving();
			}
		}
	}

	// Sharing

	/** The code for what's been found here: the seed if known, otherwise the finds. */
	public static ShareCode shareCode() {
		return ShareCode.of(progressHashedSeed, foundSeed, new ArrayList<>(OBSERVATIONS));
	}

	private static int share(FabricClientCommandSource source, String player) {
		if (progressKey == null) {
			source.sendError(Component.literal("Join a world first."));
			return 0;
		}
		if (foundSeed == null && OBSERVATIONS.isEmpty()) {
			source.sendError(Component.literal("Nothing to share yet."));
			return 0;
		}
		ShareCode code = shareCode();
		String what = code.seed() != null ? "the seed" : code.observations().size() + " find" + (code.observations().size() == 1 ? "" : "s")
			+ (code.observations().size() < OBSERVATIONS.size() ? " (the most useful ones that fit)" : "");
		if (player != null) {
			Minecraft.getInstance().player.connection.sendCommand("msg " + player + " Seed Cracker progress (needs the Seed Cracker mod): " + code.encode());
			message(Component.literal("Sent " + what + " to " + player + "."));
		} else {
			message(Component.literal("Code for " + what + " (click to copy, then /seedcracker import <code> on their side): ")
				.append(copyable(code.encode())));
		}
		return Command.SINGLE_SUCCESS;
	}

	/** Adds what a code holds. Returns an error to show, or null. */
	public static String importCode(String text, String from) {
		Minecraft mc = Minecraft.getInstance();
		if (progressKey == null || mc.level == null) {
			return "Join the world the code is for first.";
		}
		ShareCode code;
		try {
			code = ShareCode.decode(text);
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		if (code.hashedSeed() != progressHashedSeed) {
			return "That code is from a different world or server, so it was left out.";
		}
		if (code.seed() != null) {
			if (SeedSolver.hashSeed(code.seed()) != progressHashedSeed) {
				return "That code's seed doesn't match this world.";
			}
			if (Long.valueOf(code.seed()).equals(foundSeed)) {
				message(Component.literal("You already have that seed."));
				return null;
			}
			foundSeed = code.seed();
			saveProgress();
			message(Component.literal("Seed" + (from == null ? "" : " from " + from) + ": ").append(copyable(Long.toString(foundSeed))));
			saveSeed(foundSeed);
			notifyListeners(foundSeed);
			return null;
		}
		if (foundSeed != null) {
			message(Component.literal("You already have this world's seed, so the finds weren't needed."));
			return null;
		}
		int added = 0;
		for (Observation observation : code.observations()) {
			if (SeedSolver.isPossible(observation) && OBSERVATIONS.add(observation)) {
				added++;
			}
		}
		saveProgress();
		message(Component.literal(String.format("Added %d new find%s%s (%s).", added, added == 1 ? "" : "s",
			from == null ? "" : " from " + from, progress())));
		if (added > 0 && information().isEnough()) {
			startSolving();
		}
		return null;
	}

	/** Returns false to hide a chat message that carried a code, and offers to import it instead. */
	private static boolean onMessage(Component message, String sender) {
		String text = ShareCode.find(message.getString());
		if (text == null) {
			return true;
		}
		ShareCode code;
		try {
			code = ShareCode.decode(text);
		} catch (IllegalArgumentException e) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (sender != null && sender.equals(mc.getUser().getName())) {
			return false; // Our own /msg echoed back.
		}
		String raw = message.getString();
		String from = sender != null ? sender : raw.contains(" whispers to you") ? raw.substring(0, raw.indexOf(" whispers to you")) : "Someone";
		if (raw.startsWith("You whisper to")) {
			return false;
		}
		mc.execute(() -> offerImport(code, text, from));
		return false;
	}

	private static void offerImport(ShareCode code, String text, String from) {
		if (code.hashedSeed() != progressHashedSeed) {
			message(Component.literal(from + " shared Seed Cracker progress from a different world.").withStyle(ChatFormatting.GRAY));
			return;
		}
		String what = code.seed() != null ? "the seed" : code.observations().size() + " find" + (code.observations().size() == 1 ? "" : "s");
		MutableComponent prompt = Component.literal(from + " shared Seed Cracker progress: " + what + ". ");
		prompt.append(Component.literal("[Import]").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/seedcracker import " + text))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Add it to your Seed Cracker progress")))));
		message(prompt);
	}

	/** For the game test: what leaving and joining again does, without leaving. */
	public static void rejoinForTest() {
		reset();
		loadProgress();
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
