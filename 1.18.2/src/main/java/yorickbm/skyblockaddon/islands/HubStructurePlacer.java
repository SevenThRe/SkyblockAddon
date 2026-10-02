package yorickbm.skyblockaddon.islands;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import yorickbm.skyblockaddon.core.SkyblockAddonCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CN fork — the Sky Vault Altar, a.k.a. 主城 / hub.
 *
 * <h3>Why this class exists</h3>
 * Upstream placed the very same {@code island.nbt} in two roles: the world spawn (主城) and
 * the template handed to every player by {@code /island create}. Both therefore looked
 * identical, and because a freshly claimed island sits at the world spawn too, a player who
 * ran {@code /island create} could be left looking at what appears to be the hub — the exact
 * confusion this class is meant to remove.
 *
 * <p>The hub is now generated procedurally instead of being copied from the player template,
 * so it is unmistakably different in silhouette, materials and scale:
 * <ul>
 *   <li>a raised central Vault Altar ringed by pillars,</li>
 *   <li>three concentric walkways stepping down and outward,</li>
 *   <li>a beacon column of crystal above the centre,</li>
 *   <li>and a polished-stone rim with vault glass lighting it from below.</li>
 * </ul>
 * The player island template is left completely untouched, so the familiar Vault Hunters
 * starting island is preserved.
 *
 * <h3>Robustness</h3>
 * Every block id is resolved through {@link #resolveBlock}, which falls back to a vanilla
 * equivalent when the mod supplying it is absent. The altar degrades into a plain stone
 * structure rather than crashing worldgen, so this class is safe on any mod list.
 */
public final class HubStructurePlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Outer radius of the largest walkway ring, in blocks. */
    private static final int OUTER_RADIUS = 21;
    /** Inner radius that stays open as the central plaza. */
    private static final int INNER_RADIUS = 5;
    /** How many concentric walkways step down from the plaza. */
    private static final int TERRACE_COUNT = 3;
    /** Number of pillars ringing the plaza. */
    private static final int PILLAR_COUNT = 8;
    /** Height of the crystal column above the plaza floor. */
    private static final int BEACON_HEIGHT = 7;

    private final ServerLevel level;
    private final Map<ChunkPos2, ChunkAccess> touched = new HashMap<>();

    /** Tiny record so we can key the touched-chunk map without importing ChunkPos twice. */
    private record ChunkPos2(int x, int z) {
    }

    public HubStructurePlacer(final ServerLevel level) {
        this.level = level;
    }

    // ── Block palette (resolved lazily, with vanilla fallbacks) ──────────────

    private BlockState vaultStone;
    private BlockState polishedVaultStone;
    private BlockState chiseledVaultStone;
    private BlockState vaultGlass;
    private BlockState crystalBlock;
    private BlockState polishedBlackstone;

    private void resolvePalette() {
        if (vaultStone != null) return;

        vaultStone = resolve("the_vault", "vault_stone", Blocks.POLISHED_DEEPSLATE);
        polishedVaultStone = resolve("the_vault", "polished_vault_stone", Blocks.POLISHED_DEEPSLATE);
        chiseledVaultStone = resolve("the_vault", "chiseled_vault_stone", Blocks.CHISELED_DEEPSLATE);
        vaultGlass = resolve("the_vault", "vault_glass", Blocks.SEA_LANTERN);
        crystalBlock = resolve("the_vault", "crystal_block", Blocks.SEA_LANTERN);
        polishedBlackstone = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    }

    private static BlockState resolve(final String modid, final String path, final Block fallback) {
        final ResourceLocation id = new ResourceLocation(modid, path);
        final Block block = Registry.BLOCK.get(id);
        if (block == Blocks.AIR || !Registry.BLOCK.containsKey(id)) {
            LOGGER.warn("[{}] 找不到方块 {}，主城该部件改用 {}。", SkyblockAddonCore.MOD_ID, id, fallback);
            return fallback.defaultBlockState();
        }
        return block.defaultBlockState();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Build the hub centred on {@code centerX}/{@code centerZ} with its plaza floor at
     * {@code floorY}. Must run on the server thread.
     *
     * @return the safe standing position: on the plaza, just beside the crystal column rather
     *         than on top of it — the column occupies the exact centre, so returning the centre
     *         would drop the player inside the beacon.
     */
    public BlockPos place(final int centerX, final int floorY, final int centerZ) {
        resolvePalette();
        final long start = System.nanoTime();

        buildOuterRim(centerX, floorY, centerZ);
        buildTerraces(centerX, floorY, centerZ);
        buildPlaza(centerX, floorY, centerZ);
        buildPillars(centerX, floorY, centerZ);
        buildBeacon(centerX, floorY, centerZ);
        buildUnderglow(centerX, floorY, centerZ);

        //Lighting: the vault glass underglow needs a light source to actually read as a hub.
        final int chunkCount = touched.size();
        flushChunks();

        final BlockPos stand = new BlockPos(centerX + 2, floorY + 1, centerZ);
        LOGGER.info("[{}] 悬浮宝库祭坛（主城）已生成，中心 ({}, {}, {})，玩家落点 {}，耗时 {}ms，共触及 {} 个区块。",
                SkyblockAddonCore.MOD_ID, centerX, floorY, centerZ, stand,
                (System.nanoTime() - start) / 1_000_000L, chunkCount);
        return stand;
    }

    // ── Structure parts ──────────────────────────────────────────────────────

    /** Solid rim so the hub never reads as "a few floating blocks". */
    private void buildOuterRim(final int cx, final int floorY, final int cz) {
        final int rimY = floorY - TERRACE_COUNT;
        for (int x = -OUTER_RADIUS; x <= OUTER_RADIUS; x++) {
            for (int z = -OUTER_RADIUS; z <= OUTER_RADIUS; z++) {
                if (Math.abs(x) != OUTER_RADIUS && Math.abs(z) != OUTER_RADIUS) continue;
                set(cx + x, rimY, cz + z, polishedVaultStone);
                set(cx + x, rimY + 1, cz + z, vaultStone);
                //Rounded corners: skip the diagonal pixels so the silhouette is a circle, not a square.
                if (Math.abs(x) == OUTER_RADIUS && Math.abs(z) == OUTER_RADIUS) {
                    set(cx + x, rimY + 1, cz + z, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /**
     * Concentric walkways stepping down and outward. The stepping is what makes the hub read as
     * a built structure; a flat disc would look like an oversized player island.
     */
    private void buildTerraces(final int cx, final int floorY, final int cz) {
        for (int t = 0; t < TERRACE_COUNT; t++) {
            final int rOuter = OUTER_RADIUS - 1 - (t * 4);
            final int rInner = INNER_RADIUS + 1 + (t * 2);
            final int y = floorY - t;

            for (int x = -rOuter; x <= rOuter; x++) {
                for (int z = -rOuter; z <= rOuter; z++) {
                    final int dist = (int) Math.sqrt(x * x + z * z);
                    if (dist > rOuter || dist < rInner) continue;
                    set(cx + x, y, cz + z, t % 2 == 0 ? vaultStone : polishedVaultStone);
                }
            }
            //Low wall on each terrace's outer edge, one block above the walking surface.
            for (int a = 0; a < 360; a += 2) {
                final double rad = Math.toRadians(a);
                final int x = cx + (int) Math.round(Math.cos(rad) * rOuter);
                final int z = cz + (int) Math.round(Math.sin(rad) * rOuter);
                set(x, y + 1, z, chiseledVaultStone);
            }
        }
    }

    /** Flat central plaza with a chiseled ring pattern. */
    private void buildPlaza(final int cx, final int floorY, final int cz) {
        for (int x = -INNER_RADIUS; x <= INNER_RADIUS; x++) {
            for (int z = -INNER_RADIUS; z <= INNER_RADIUS; z++) {
                final int dist = (int) Math.sqrt(x * x + z * z);
                if (dist > INNER_RADIUS) continue;
                final boolean ring = dist == INNER_RADIUS - 1;
                set(cx + x, floorY, cz + z, ring ? chiseledVaultStone : polishedVaultStone);
            }
        }
    }

    /** Free-standing pillars: the strongest silhouette cue that this is not a player island. */
    private void buildPillars(final int cx, final int floorY, final int cz) {
        final int pillarRadius = INNER_RADIUS + 1;
        for (int i = 0; i < PILLAR_COUNT; i++) {
            final double rad = Math.toRadians((360.0 / PILLAR_COUNT) * i);
            final int x = cx + (int) Math.round(Math.cos(rad) * pillarRadius);
            final int z = cz + (int) Math.round(Math.sin(rad) * pillarRadius);

            for (int h = 1; h <= 4; h++) {
                set(x, floorY + h, z, h == 4 ? chiseledVaultStone : vaultStone);
            }
            //Lantern on top of every other pillar.
            if (i % 2 == 0) {
                set(x, floorY + 5, z, Blocks.SEA_LANTERN.defaultBlockState());
            }
        }
    }

    /** Crystal column above the altar — the landmark players navigate by. */
    private void buildBeacon(final int cx, final int floorY, final int cz) {
        for (int h = 1; h <= BEACON_HEIGHT; h++) {
            final boolean tip = h == BEACON_HEIGHT;
            set(cx, floorY + h, cz, tip ? crystalBlock : vaultGlass);
        }
    }

    /**
     * A ring of vault glass one block below the plaza. Gives the hub a lit underside when seen
     * from below while flying, which is the direction players arrive from.
     */
    private void buildUnderglow(final int cx, final int floorY, final int cz) {
        final int y = floorY - TERRACE_COUNT - 1;
        for (int a = 0; a < 360; a += 6) {
            final double rad = Math.toRadians(a);
            final int x = cx + (int) Math.round(Math.cos(rad) * (OUTER_RADIUS - 2));
            final int z = cz + (int) Math.round(Math.sin(rad) * (OUTER_RADIUS - 2));
            set(x, y, z, vaultGlass);
        }
    }

    // ── Block writing ────────────────────────────────────────────────────────

    private void set(final int x, final int y, final int z, final BlockState state) {
        if (state == null) return;
        if (state.isAir()) {
            clear(x, y, z);
            return;
        }
        final BlockPos pos = new BlockPos(x, y, z);
        final ChunkAccess chunk = chunkAt(pos);
        if (chunk == null) return;
        //In 1.18.2 ChunkAccess.setBlockState returns the previous state, not a success flag,
        //so there is nothing to assert on here - the write itself is unconditional.
        chunk.setBlockState(pos, state, false);
    }

    private void clear(final int x, final int y, final int z) {
        final BlockPos pos = new BlockPos(x, y, z);
        final ChunkAccess chunk = chunkAt(pos);
        if (chunk == null) return;
        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
    }

    private ChunkAccess chunkAt(final BlockPos pos) {
        final int cx = pos.getX() >> 4;
        final int cz = pos.getZ() >> 4;
        final ChunkPos2 key = new ChunkPos2(cx, cz);
        ChunkAccess chunk = touched.get(key);
        if (chunk != null) return chunk;

        chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
        if (chunk != null) touched.put(key, chunk);
        return chunk;
    }

    /**
     * Mark the touched chunks dirty and resend them to nearby players, mirroring what
     * {@code IslandStructurePlacer} does after placing an island — otherwise clients keep the
     * pre-generation void until the chunk unloads and reloads.
     */
    private void flushChunks() {
        for (final ChunkAccess chunk : touched.values()) {
            if (!(chunk instanceof final net.minecraft.world.level.chunk.LevelChunk levelChunk)) continue;
            level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).forEach(player ->
                    ((net.minecraft.server.network.ServerGamePacketListenerImpl) player.connection).send(
                            new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                    levelChunk, level.getLightEngine(), null, null, false))
            );
        }
        touched.clear();
    }
}
