package yorickbm.skyblockaddon.islands;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import yorickbm.skyblockaddon.core.SkyblockAddonCore;
import yorickbm.skyblockaddon.core.configs.SkyBlockAddonLanguage;

/**
 * CN fork — owns the hub (主城) as a distinct place from any player island.
 *
 * <h3>Why this is separated from {@link IslandManager}</h3>
 * The hub is deliberately <em>not</em> registered as an {@code Island}. Islands are keyed by
 * owner UUID and drive permission, chunk-purge and travel-map bookkeeping; a shared spawn
 * point has none of those. Upstream conflated the two by placing the player island template at
 * the world spawn, which made "the hub" and "my new island" the same object on screen.
 *
 * <p>Keeping it out of {@code IslandManager} also means the hub can never be handed to a player
 * by {@code /island create}, can never appear in the travel map, and can never be purged —
 * removing the whole class of "wrongly sent back to the hub" bugs at the source.
 *
 * <h3>Lifecycle</h3>
 * The altar is generated once per world, lazily, the first time somebody actually needs a
 * spawn point (join, {@code /island hub}, or being kicked off an island). Generation is
 * idempotent per world: a marker in the level capability prevents a rebuild after a restart,
 * because the blocks are already in the chunks and re-placing them is wasted work.
 */
public final class HubManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final MinecraftServer serverRef[] = new MinecraftServer[1];
    private static BlockPos hubPos = null;
    private static boolean generated = false;

    private HubManager() {
    }

    public static void setServer(final MinecraftServer server) {
        serverRef[0] = server;
        hubPos = null;
        generated = false;
    }

    public static void invalidate() {
        hubPos = null;
        generated = false;
    }

    /**
     * Spawn position of the hub, generating the altar on first use.
     * Server thread only (it writes blocks).
     */
    public static BlockPos getHubPosition(final ServerLevel overworld) {
        if (hubPos != null) return hubPos;

        if (generated) {
            //Already built once this session but the position is unknown (e.g. the saved spawn
            //was in unloaded chunks).Fall back to the level's own spawn rather than rebuilding.
            hubPos = overworld.getSharedSpawnPos().above();
            return hubPos;
        }
        generated = true;

        final int centerX = overworld.getSharedSpawnPos().getX();
        final int centerZ = overworld.getSharedSpawnPos().getZ();
        final int floorY = Integer.parseInt(
                yorickbm.skyblockaddon.configs.SkyblockAddonConfig.getForKey("hub.spawn.height"));

        try {
            final HubStructurePlacer placer = new HubStructurePlacer(overworld);
            //The placer returns a walkable spot on the plaza, already accounting for the crystal
            //column at the exact centre. Deliberately NOT re-derived from a Heightmap: the blocks
            //are written straight into the chunk, so WORLD_SURFACE_WG has not been recomputed yet
            //and would hand back void height.
            hubPos = placer.place(centerX, floorY, centerZ);
            LOGGER.info("[{}] 主城（悬浮宝库祭坛）就绪，玩家落点 {}。", SkyblockAddonCore.MOD_ID, hubPos);
        } catch (final Throwable t) {
            //Never let hub generation take a world down: fall back to the level's spawn point.
            LOGGER.error("[{}] 主城生成失败，回退到世界出生点：{}", SkyblockAddonCore.MOD_ID, t.toString());
            hubPos = overworld.getSharedSpawnPos().above();
        }
        return hubPos;
    }

    /**
     * Where a player who currently has no island should be sent.
     *
     * <p>This is the only sanctioned "send them to the hub" path. Callers that used to jump
     * straight to {@code getSharedSpawnPos()} now come through here, so the hub is guaranteed
     * to exist (and to look like a hub) before anyone lands on it.
     */
    public static BlockPos fallbackSpawn() {
        final MinecraftServer server = serverRef[0];
        if (server == null) return null;
        final ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return null;
        return getHubPosition(overworld);
    }
}
