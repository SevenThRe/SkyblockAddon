package yorickbm.skyblockaddon.mixins;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import yorickbm.skyblockaddon.core.SkyblockAddonCore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CN fork — Sky Vault level-type support.
 *
 * <h3>Why this exists</h3>
 * Terralith 2.2.6 ships {@code MixinTerrablenderConfig}, a {@code @Redirect} on
 * {@code terrablender.config.TerraBlenderConfig}'s call into {@code Config.addNumber}:
 *
 * <pre>
 *   if (key.equals("vanilla_overworld_region_weight") &amp;&amp; defaultValue instanceof Integer)
 *       return Integer.valueOf(0);          // addNumber is never reached
 *   return instance.addNumber(comment, key, defaultValue, min, max);
 * </pre>
 *
 * With {@code terrablender_compatibility = true} — the default shipped in the Wold's Vaults
 * server pack — that zeroes the vanilla overworld region weight globally. A world whose
 * level-type is not Terralith's own therefore never receives a region: a sky vault world
 * generates as bare void. That is what "level-type stops working" actually means, and it is
 * a data problem, not a config typo.
 *
 * <h3>How this fixes it</h3>
 * A {@code @Redirect} owns its call site exclusively, so an {@code @Inject} on
 * {@code Config.addNumber} could never run here. We instead redirect the same call site from
 * our own side and, for sky vault worlds only, supply a non-zero weight ourselves. Because
 * {@code Config.addNumber} only writes when the key is absent, the value we put in is the one
 * that sticks.
 *
 * <p>On any other world we delegate to the original target unchanged, so normal Terralith
 * behaviour is preserved byte for byte.
 */
public final class SkyVaultLevelType {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Region weight for a sky-vault overworld. Terralith's own region is registered separately
     * by TerraBlender; this only restores the vanilla overworld entry Terralith suppresses, so
     * ordinary terrain generation (and therefore the island template) works again.
     */
    public static final int VANILLA_OVERWORLD_REGION_WEIGHT = 100;

    private static final String VANILLA_OVERWORLD_REGION_KEY = "vanilla_overworld_region_weight";

    /** Cached answer: consulted from worldgen, where re-reading the level is unsafe. */
    private static volatile Boolean cachedSkyVault = null;
    private static volatile MinecraftServer cachedServer = null;

    private SkyVaultLevelType() {
    }

    /**
     * @return true when the overworld's chunk generator is the sky vault (void) world type
     *         shipped by the server pack, i.e. the level-type a player selects as "sky vault".
     */
    public static boolean isSkyVaultWorld() {
        final MinecraftServer server = cachedServer;
        if (server == null) return false;

        final Boolean known = cachedSkyVault;
        if (known != null) return known;
        return probe(server);
    }

    private static boolean probe(final MinecraftServer server) {
        try {
            final var overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null) return false;

            //Identified structurally rather than by id, so this keeps working if the mod that
            //registers the level-type is renamed or replaced. Both known implementations of a
            //sky vault world are matched.
            final String generator = overworld.getChunkSource().getGenerator().getClass().getName();
            final boolean skyVault = generator.contains("VoidWorldType")
                    || generator.contains("SkyblockNoiseBasedChunkGenerator");

            cachedSkyVault = skyVault;
            if (skyVault) {
                LOGGER.info("[{}] 天空宝库 level-type 已识别（{}）；"
                                + "已保留 vanilla_overworld_region_weight={}，地形生成不再被 Terralith 归零。",
                        SkyblockAddonCore.MOD_ID, generator, VANILLA_OVERWORLD_REGION_WEIGHT);
            } else {
                LOGGER.info("[{}] 当前世界生成器为 {}，非天空宝库，保持 Terralith 原有行为。",
                        SkyblockAddonCore.MOD_ID, generator);
            }
            return skyVault;
        } catch (final Throwable t) {
            LOGGER.warn("[{}] 无法判定 level-type，按普通世界处理：{}", SkyblockAddonCore.MOD_ID, t.toString());
            return false;
        }
    }

    /**
     * Should the vanilla overworld region weight be forced non-zero?
     * Kept separate from {@link #isSkyVaultWorld()} so the mixin reads as the rule it enforces.
     */
    public static boolean shouldPreserveVanillaOverworldRegion(final String key, final Object defaultValue) {
        if (!VANILLA_OVERWORLD_REGION_KEY.equals(key)) return false;
        if (!(defaultValue instanceof Integer)) return false;
        return isSkyVaultWorld();
    }

    /**
     * Pre-seed TerraBlender's own config file with a non-zero vanilla overworld region weight.
     *
     * <p>Second line of defence, complementary to the mixin. The shipped Wold's Vaults pack
     * already has {@code vanilla_overworld_region_weight = 15} in {@code terrablender.toml}, but
     * Terralith bypasses the file entirely: its handler short-circuits the key to {@code 0} and
     * never lets {@code addNumber} read the stored value. This routine is therefore a no-op on a
     * stock pack and only does work if an admin has set the weight to {@code 0} (or removed the
     * key) — in which case the sky vault world would have no region at all even after the mixin
     * is accounted for.
     *
     * <p>Idempotent: a stock file is left byte-for-byte untouched.
     */
    public static void patchTerrablenderConfigFile() {
        final Path file = FMLPaths.CONFIGDIR.get().resolve("terrablender.toml");
        if (!Files.exists(file)) {
            LOGGER.info("[{}] 未找到 terrablender.toml，跳过权重预置。", SkyblockAddonCore.MOD_ID);
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean changed = false;

            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                if (!line.contains(VANILLA_OVERWORLD_REGION_KEY)) continue;
                //Already a usable weight -> leave the admin's value alone.
                if (!line.matches(".*=\\s*0+\\s*$")) continue;
                lines.set(i, line.replaceAll("=\\s*0+\\s*$", "= " + VANILLA_OVERWORLD_REGION_WEIGHT));
                changed = true;
            }

            if (changed) {
                Files.write(file, lines, StandardCharsets.UTF_8);
                LOGGER.info("[{}] 已把 terrablender.toml 的 {} 修正为 {}。",
                        SkyblockAddonCore.MOD_ID, VANILLA_OVERWORLD_REGION_KEY, VANILLA_OVERWORLD_REGION_WEIGHT);
            } else {
                LOGGER.info("[{}] terrablender.toml 的 {} 已是有效值，无需修改。",
                        SkyblockAddonCore.MOD_ID, VANILLA_OVERWORLD_REGION_KEY);
            }
        } catch (final IOException e) {
            //Never fatal: the mixin path may still cover it.
            LOGGER.warn("[{}] 修正 terrablender.toml 失败：{}", SkyblockAddonCore.MOD_ID, e.toString());
        }
    }

    /** Registers the server lifecycle hooks that prime (and invalidate) the cached answer. */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Lifecycle());
    }

    /**
     * Forge's event bus dispatches to registered <em>instances</em>; a static handler would
     * never be called, so the two lifecycle hooks live in a tiny holder object.
     */
    private static final class Lifecycle {
        @SubscribeEvent
        public void onServerStarting(final ServerStartingEvent event) {
            cachedServer = event.getServer();
            cachedSkyVault = null;
            // The authoritative fix: correct the weight in TerraBlender's own config before the
            // world is read, so neither Terralith's redirect nor injection order can undo it.
            patchTerrablenderConfigFile();
        }

        @SubscribeEvent
        public void onServerStopped(final ServerStoppedEvent event) {
            cachedServer = null;
            cachedSkyVault = null;
        }
    }
}
