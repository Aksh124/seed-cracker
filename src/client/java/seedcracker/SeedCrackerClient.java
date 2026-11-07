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
import java.util.LinkedHashSet;
import java.util.List;
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
			for (Observation observation : StructureDetector.scan(chunk)) {
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
		int count = OBSERVATIONS.size();
		String progress = count < SeedSolver.MIN_OBSERVATIONS
			? String.format("%d/%d needed", count, SeedSolver.MIN_OBSERVATIONS)
			: count + " found";
		message(Component.literal(String.format("Found a %s at chunk %d, %d (%s)",
			observation.type().displayName, observation.chunkX(), observation.chunkZ(), progress)));
		if (count >= SeedSolver.MIN_OBSERVATIONS) {
			startSolving();
		}
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
		message(Component.literal("Cracking with " + snapshot.size() + " structures..."));

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
		} else if (count >= SeedSolver.MIN_OBSERVATIONS && result.structureSeeds().length > 200_000) {
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
		dispatcher.register(literal("seedcrack")
			.executes(context -> {
				FabricClientCommandSource source = context.getSource();
				if (foundSeed != null) {
					source.sendFeedback(Component.literal("Seed: ").append(copyable(Long.toString(foundSeed))));
				}
				source.sendFeedback(Component.literal(String.format("%d structures found (%d needed, more is faster)%s",
					OBSERVATIONS.size(), SeedSolver.MIN_OBSERVATIONS, solving ? ", cracking now" : "")));
				int index = 1;
				for (Observation observation : OBSERVATIONS) {
					source.sendFeedback(Component.literal(String.format("  %d. %s at chunk %d, %d (block %d, %d)", index++,
						observation.type().displayName, observation.chunkX(), observation.chunkZ(),
						observation.chunkX() * 16, observation.chunkZ() * 16)));
				}
				return Command.SINGLE_SUCCESS;
			})
			.then(literal("crack").executes(context -> {
				if (OBSERVATIONS.size() < SeedSolver.MIN_OBSERVATIONS) {
					context.getSource().sendError(Component.literal("Need at least " + SeedSolver.MIN_OBSERVATIONS + " structures."));
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
			})));
	}

	private static void reset() {
		OBSERVATIONS.clear();
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
