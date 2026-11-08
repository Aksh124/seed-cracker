package seedcracker.test;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import seedcracker.SeedCrackerClient;
import seedcracker.SeedSolver;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;

/**
 * Only the structures added in 1.3.0, no temples: ruined portals (Overworld and Nether), villages,
 * an ancient city, a woodland mansion and End cities, each checked against the start chunk the
 * server's own locate reports. Then the End pillars, which make it enough to crack.
 */
public class NewStructuresGameTest implements FabricClientGameTest {
	private static final long SEED = 3_141_592_653_589_793_238L;

	private record Target(ResourceKey<Level> dimension, Observation observation) {}

	@Override
	public void runTest(ClientGameTestContext context) {
		// Silent: the test framework turns the volume back up when it starts.
		context.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0));
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(settings -> settings.setSeed(Long.toString(SEED)))
			.create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode spectator @a");

			List<Target> targets = new ArrayList<>();
			targets.addAll(find(world, Level.OVERWORLD, StructureType.RUINED_PORTAL, "1".equals(System.getenv("SEEDCRACKER_QUICK")) ? 0 : 3, 900, List.of(BuiltinStructures.RUINED_PORTAL_STANDARD,
				BuiltinStructures.RUINED_PORTAL_DESERT, BuiltinStructures.RUINED_PORTAL_JUNGLE, BuiltinStructures.RUINED_PORTAL_SWAMP,
				BuiltinStructures.RUINED_PORTAL_MOUNTAIN, BuiltinStructures.RUINED_PORTAL_OCEAN)));
			targets.addAll(find(world, Level.NETHER, StructureType.RUINED_PORTAL, "1".equals(System.getenv("SEEDCRACKER_QUICK")) ? 0 : 1, 900, List.of(BuiltinStructures.RUINED_PORTAL_NETHER)));
			targets.addAll(find(world, Level.OVERWORLD, StructureType.VILLAGE, "1".equals(System.getenv("SEEDCRACKER_QUICK")) ? 0 : 2, 900, List.of(BuiltinStructures.VILLAGE_PLAINS,
				BuiltinStructures.VILLAGE_DESERT, BuiltinStructures.VILLAGE_SAVANNA, BuiltinStructures.VILLAGE_SNOWY, BuiltinStructures.VILLAGE_TAIGA)));
			targets.addAll(find(world, Level.OVERWORLD, StructureType.ANCIENT_CITY, 1, 900, List.of(BuiltinStructures.ANCIENT_CITY)));
			targets.addAll(find(world, Level.OVERWORLD, StructureType.WOODLAND_MANSION, 1, 3000, List.of(BuiltinStructures.WOODLAND_MANSION)));
			targets.addAll(find(world, Level.END, StructureType.END_CITY, 2, 1200, List.of(BuiltinStructures.END_CITY)));
			log("visiting " + targets);

			for (Target target : targets) {
				Observation observation = target.observation();
				world.getServer().runCommand("execute in " + target.dimension().identifier() + " run tp @a "
					+ (observation.chunkX() * 16 + 8) + " " + (target.dimension() == Level.NETHER ? 100 : 160) + " " + (observation.chunkZ() * 16 + 8));
				world.getConnection().waitForChunksDownload();
				waitUntil(context, 40, () -> SeedCrackerClient.observations().contains(observation),
					() -> "didn't detect " + target + "; closest placement: " + context.computeOnClient(mc ->
						seedcracker.TemplateRecognizer.diagnose(mc.level, observation.type(), observation.chunkX(), observation.chunkZ())));
				log("detected " + observation + ": " + context.computeOnClient(mc ->
					seedcracker.TemplateRecognizer.diagnose(mc.level, observation.type(), observation.chunkX(), observation.chunkZ())));
			}
			// Leaving and coming back keeps everything found so far.
			List<Observation> before = SeedCrackerClient.observations();
			context.runOnClient(mc -> SeedCrackerClient.rejoinForTest());
			check(SeedCrackerClient.observations().equals(before), "rejoining lost finds: " + SeedCrackerClient.observations());
			log("rejoined: all " + before.size() + " finds came back");

			// Sharing: the finds go out as a code in a whisper and come back with [Import].
			String code = context.computeOnClient(mc -> SeedCrackerClient.shareCode().encode());
			context.runOnClient(mc -> mc.player.connection.sendCommand("seedcracker clear"));
			context.waitTicks(2);
			check(SeedCrackerClient.observations().isEmpty(), "clear didn't clear");
			context.runOnClient(mc -> mc.getConnection().handleSystemChat(new net.minecraft.network.protocol.game.ClientboundSystemChatPacket(
				net.minecraft.network.chat.Component.literal("Friend whispers to you: Seed Cracker progress (needs the Seed Cracker mod): " + code), false)));
			context.waitTicks(2);
			List<String> chat = context.computeOnClient(mc -> lastChatLines(mc, 3));
			check(chat.getFirst().contains("Friend shared Seed Cracker progress: " + before.size() + " finds") && chat.getFirst().contains("[Import]")
				&& chat.stream().noneMatch(line -> line.contains(code)), "no import prompt, or the raw code showed: " + chat);
			context.runOnClient(mc -> mc.player.connection.sendCommand("seedcracker import " + code));
			context.waitTicks(2);
			check(new java.util.HashSet<>(SeedCrackerClient.observations()).equals(new java.util.HashSet<>(before)), "import didn't bring the finds back: "
				+ SeedCrackerClient.observations());
			String otherWorld = seedcracker.ShareCode.of(99L, null, List.of(new Observation(StructureType.VILLAGE, 1, 1))).encode();
			check(context.computeOnClient(mc -> SeedCrackerClient.importCode(otherWorld, null)).contains("different world"), "another world's code was taken");
			log("shared " + before.size() + " finds in a " + code.length() + "-character code, whisper showed [Import], import brought them back, another world's code refused");

			check(SeedCrackerClient.foundSeed() == null && !SeedSolver.information(SeedCrackerClient.observations()).isEnough(),
				"enough without the pillars, so this isn't testing them");

			// The pillars: 16 bits, and the search that doesn't need temples.
			world.getServer().runCommand("execute in minecraft:the_end run tp @a 0 120 0");
			world.getConnection().waitForChunksDownload();
			waitUntil(context, 60, () -> SeedCrackerClient.observations().stream().anyMatch(o -> o.type() == StructureType.END_PILLARS),
				() -> "didn't read the End pillars: " + seedcracker.EndPillars.lastSeen);
			int order = world.getServer().computeOnServer(server ->
				(int) (net.minecraft.util.RandomSource.createThreadLocalInstance(server.getLevel(Level.END).getSeed()).nextLong() & 65535L));
			check(SeedCrackerClient.observations().contains(new Observation(StructureType.END_PILLARS, order, 0)), "pillar order wrong: "
				+ SeedCrackerClient.observations() + ", vanilla says " + order);
			log("read the End pillars: order " + order);

			waitUntil(context, 300, () -> SeedCrackerClient.foundSeed() != null && !SeedCrackerClient.isSolving(),
				() -> "no seed from " + SeedCrackerClient.observations());
			check(SeedCrackerClient.foundSeed() == SEED, "cracked " + SeedCrackerClient.foundSeed() + " but the seed is " + SEED);
			log("cracked " + SEED + " without any temples, from " + SeedCrackerClient.observations().size() + " observations");
			context.runOnClient(mc -> mc.player.connection.sendCommand("seedcracker"));
			context.waitTicks(20);
			context.takeScreenshot("seedcracker-new-structures");
		}
	}

	/** Start chunks of real structures, from the server's own locate, spread out so they're distinct. */
	private static List<Target> find(TestSingleplayerContext world, ResourceKey<Level> dimension, StructureType type, int count, int step,
		List<ResourceKey<Structure>> keys) {
		return world.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(dimension);
			var registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			List<Holder<Structure>> holders = keys.stream().<Holder<Structure>>map(registry::getOrThrow).toList();
			Set<Target> found = new LinkedHashSet<>();
			int[][] directions = {{0, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
			for (int ring = 0; ring < 5 && found.size() < count; ring++) {
				for (int[] dir : directions) {
					BlockPos centre = new BlockPos(dir[0] * step * ring, 64, dir[1] * step * ring);
					if (dimension == Level.END && ring == 0) {
						centre = new BlockPos(1000, 64, 0);
					}
					Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
						.findNearestMapStructure(level, HolderSet.direct(holders), centre, 100, false);
					if (result != null) {
						BlockPos pos = result.getFirst();
						found.add(new Target(dimension, new Observation(type, SectionPos.blockToSectionCoord(pos.getX()),
							SectionPos.blockToSectionCoord(pos.getZ()))));
					}
					if (found.size() >= count) {
						break;
					}
				}
			}
			check(found.size() >= count, "only found " + found.size() + " " + type + " in " + dimension);
			return new ArrayList<>(found);
		});
	}

	/** The newest received chat messages, newest first. */
	@SuppressWarnings("unchecked")
	private static List<String> lastChatLines(net.minecraft.client.Minecraft mc, int count) {
		try {
			var field = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
			field.setAccessible(true);
			var messages = (List<net.minecraft.client.multiplayer.chat.GuiMessage>) field.get(mc.gui.hud.getChat());
			List<String> lines = new ArrayList<>();
			for (int i = 0; i < messages.size() && lines.size() < count; i++) {
				lines.add(messages.get(i).content().getString());
			}
			return lines;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void waitUntil(ClientGameTestContext context, int seconds, BooleanSupplier done, Supplier<String> failure) {
		for (int tick = 0; tick < seconds * 20; tick++) {
			if (context.computeOnClient(mc -> done.getAsBoolean())) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(failure.get());
	}

	private static void check(boolean condition, String failure) {
		if (!condition) {
			throw new AssertionError(failure);
		}
	}

	private static void log(String text) {
		System.out.println("[seedcracker-gametest] " + text);
	}
}
