package yorickbm.skyblockaddon.islands;

import net.minecraft.ChatFormatting;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import yorickbm.skyblockaddon.core.SkyblockAddonCore;
import yorickbm.skyblockaddon.core.configs.SkyBlockAddonLanguage;
import yorickbm.skyblockaddon.core.islands.Island;
import yorickbm.skyblockaddon.core.islands.IslandGroup;
import yorickbm.skyblockaddon.core.islands.IslandManager;
import yorickbm.skyblockaddon.core.util.geometry.ChunkRef;
import yorickbm.skyblockaddon.core.util.geometry.Square;
import yorickbm.skyblockaddon.util.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class ForgeIsland extends Island implements NBTSerializable {
    private static final Logger LOGGER = LogManager.getLogger();

    public ForgeIsland() {
        super();
    }
    public ForgeIsland(Island island) {
        super.setId(island.getId());
        super.setOwner(island.getOwner());
        super.setSpawn(island.getSpawn());
        super.setCenter(island.getCenter());
        super.setVisibility(island.isVisible());
        super.setSkullTexture(island.getSkullTexture());
        super.setChunks(island.getLoadedChunks());

        island.getGroups().forEach(super::addGroup);
        island.getMembers().forEach(m -> {
            final UUID groupId = island.getGroupForEntityUUID(m)
                    .map(IslandGroup::getId)
                    .filter(gid -> !gid.equals(SkyblockAddonCore.MOD_UUID2))
                    .orElse(SkyblockAddonCore.MOD_UUID);
            if(!super.addMember(m, groupId)) {
                LOGGER.warn("Failed to copy member {} into island {} during snapshot", m, getId());
            }
        });
    }
    public ForgeIsland(UUID uuid, Vec3i vec) {
        super.setId(UUID.randomUUID());
        super.setOwner(uuid);
        super.setSpawn(ForgeConverter.ForgeToInternalVec3i(vec));
        super.setCenter(ForgeConverter.ForgeToInternalVec3i(vec));
        super.setVisibility(false);

        this.genBasicGroups();
    }

    /**
     * Generate default groups
     */
    public void genBasicGroups() {
        //Add default members group
        final ItemStack item = new ItemStack(Items.RED_MUSHROOM);
        item.setHoverName(new TextComponent(SkyBlockAddonLanguage.getLocalizedString("gui.group.default.name")).withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.BLUE));
        final ItemStack item2 = new ItemStack(Items.BROWN_MUSHROOM);
        item2.setHoverName(new TextComponent(SkyBlockAddonLanguage.getLocalizedString("gui.group.nonmember.name")).withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.BLUE));


        super.addGroup(new ForgeIslandGroup(SkyblockAddonCore.MOD_UUID, item, true));
        super.addGroup(new ForgeIslandGroup(SkyblockAddonCore.MOD_UUID2, item2, false));
    }

    /**
     * Teleport entity to spawn location of island.
     *
     * @param entity - Entity to teleport
     */
    public void teleportTo(final Entity entity) {
        if (entity instanceof final ServerPlayer player) {
            final var event = yorickbm.skyblockaddon.core.events.IslandEventBus.fire(
                    new yorickbm.skyblockaddon.core.events.TeleportToIslandEvent(this, player.getUUID()));
            if (event.isCancelled()) return;
        }

        entity.teleportTo(getSpawn().getX(), getSpawn().getY() + 0.5, getSpawn().getZ());

        if (entity instanceof final ServerPlayer player) {
            ServerHelper.playSongToPlayer(player, SoundEvents.ENDERMAN_TELEPORT, SkyblockAddonCore.EFFECT_SOUND_VOL, 1f);
        }
    }

    /**
     * Kick entity from island
     *
     * @param source - Who is kicking the entity
     * @param entity - UUID of entity to remove
     */
    public void kickMember(final Entity source, final UUID entity) {
        if(isOwner(entity)) {
            if(!getMembers().isEmpty()) super.setOwner(getMembers().get(0));
            else {
                super.setOwner(SkyblockAddonCore.MOD_UUID);
                super.setVisibility(false); //Close island if we are the last one
            }
        } else {
            final Optional<IslandGroup> group = getGroupForEntityUUID(entity);
            group.ifPresentOrElse(
                    g -> super.removeMember(entity, g.getId()),
                    () -> super.removeMember(entity, SkyblockAddonCore.MOD_UUID)
            );
        }

        IslandManager.getInstance().clearCacheForPlayer(entity);

        final net.minecraft.server.MinecraftServer server = source.getServer();
        if (server == null) return;
        // CN fork: the kicked player no longer has an island, so the hub is the correct
        // destination — but the hub is now a generated altar rather than the world spawn, so it
        // goes through HubManager (which builds the altar on first use) instead of
        // getSharedSpawnPos(). Falling back to the level spawn only if that fails.
        final BlockPos hub = yorickbm.skyblockaddon.islands.HubManager.fallbackSpawn();
        final BlockPos worldSpawn = hub != null ? hub
                : (server.getLevel(Level.OVERWORLD) != null
                    ? server.getLevel(Level.OVERWORLD).getSharedSpawnPos()
                    : BlockPos.ZERO);
        final ServerPlayer player = server.getPlayerList().getPlayer(entity);
        if(player != null
                && player.getLevel().dimension() == Level.OVERWORLD
                && getIslandBoundingBox().isInside(ForgeConverter.ForgeToInternalVec3i(player.getOnPos()))
        ) {
            player.teleportTo(worldSpawn.getX(), worldSpawn.getY(), worldSpawn.getZ());
        }
    }

    /**
     * Add entity as member to island
     *
     * @param source - Who is adding the entity
     * @param entity - UUID of entity to add
     */
    public boolean addMember(final Entity source, final UUID entity) {
        if(!super.addMember(entity, SkyblockAddonCore.MOD_UUID)) return false;

        //Teleport entity to island
        final ServerPlayer player = Objects.requireNonNull(source.getServer()).getPlayerList().getPlayer(entity); //Get entity from online player list
        if(player != null) this.teleportTo(player);

        return true;
    }

    /**
     * Set the biome for all loaded chunks of island
     * @param biome - Biome resource location
     * @param serverlevel - Over-world of server
     */
    public void updateBiome(final String biome, final ServerLevel serverlevel) {
        if (biome == null || biome.isEmpty() || biome.equals("Unknown") || !biome.contains(":")) return;

        setBiome(biome); //Store full namespaced id so modded biomes persist correctly

        final BoundingBox boundingbox = ForgeConverter.InternalToForgeBoundingBox(getIslandBoundingBox());
        final Holder<Biome> holder = serverlevel.registryAccess()
                .registryOrThrow(Registry.BIOME_REGISTRY)
                .getOrCreateHolder(ResourceKey.create(ForgeRegistries.BIOMES.getRegistryKey(), new ResourceLocation(biome)));

        for(int j = SectionPos.blockToSectionCoord(boundingbox.minZ()); j <= SectionPos.blockToSectionCoord(boundingbox.maxZ()); ++j) {
            for(int k = SectionPos.blockToSectionCoord(boundingbox.minX()); k <= SectionPos.blockToSectionCoord(boundingbox.maxX()); ++k) {
                final ChunkAccess chunkaccess = serverlevel.getChunk(k, j, ChunkStatus.FULL, false);
                if (chunkaccess == null) {
                    continue; //Skip unloaded chunks
                }
                applyConfiguredBiomeToChunk(chunkaccess, serverlevel, holder, boundingbox);
                BiomeUtil.updateChunk(serverlevel.getChunk(k, j), serverlevel);
            }
        }
    }

    /**
     * Reapply this island's configured biome to a freshly loaded chunk if it doesn't already match.
     * Called from ChunkEvent.Load so that chunks unloaded at the time of biome configuration still
     * pick up the correct biome once they come back. Deliberately does NOT send a client packet -
     * the chunk-load path relies on vanilla's natural chunk send to carry the modified biome arrays.
     *
     * @return true if the biome was rewritten, false if skipped.
     */
    public boolean reapplyBiomeIfNeeded(final ChunkAccess chunk, final ServerLevel serverlevel) {
        final String biomeId = getBiome();
        if (biomeId == null || biomeId.isEmpty() || biomeId.equals("Unknown")) return false;

        final Holder<Biome> targetHolder;
        try {
            targetHolder = serverlevel.registryAccess()
                    .registryOrThrow(Registry.BIOME_REGISTRY)
                    .getOrCreateHolder(ResourceKey.create(ForgeRegistries.BIOMES.getRegistryKey(), new ResourceLocation(biomeId)));
        } catch (Exception ex) {
            return false;
        }

        final Optional<ResourceKey<Biome>> targetKey = targetHolder.unwrapKey();
        if (targetKey.isEmpty()) return false;

        final ChunkPos pos = chunk.getPos();
        final Holder<Biome> existing = chunk.getNoiseBiome(
                QuartPos.fromBlock(pos.getMiddleBlockX()),
                QuartPos.fromBlock(155),
                QuartPos.fromBlock(pos.getMiddleBlockZ()));
        if (existing.is(targetKey.get())) return false;

        final BoundingBox islandBbox = ForgeConverter.InternalToForgeBoundingBox(getIslandBoundingBox());
        applyConfiguredBiomeToChunk(chunk, serverlevel, targetHolder, islandBbox);
        return true;
    }

    private static void applyConfiguredBiomeToChunk(final ChunkAccess chunk, final ServerLevel serverlevel, final Holder<Biome> targetBiomeHolder, final BoundingBox islandBbox) {
        final MutableInt counter = new MutableInt(0);
        chunk.fillBiomesFromNoise(
                BiomeUtil.makeResolver(counter, chunk, islandBbox, targetBiomeHolder, (p) -> true),
                serverlevel.getChunkSource().getGenerator().climateSampler());
        chunk.setUnsaved(true);
    }

    public List<ChunkPos> getModifiedChunks() {
        return getLoadedChunks().stream().map(r -> new ChunkPos(r.x(), r.z())).toList();
    }
    public boolean storeChunk(final ChunkAccess chunk) {
        return this.storeChunk(chunk.getPos());
    }

    public boolean storeChunk(final ChunkPos pos) {
        return super.addChunk(new ChunkRef(pos.x, pos.z));
    }

    public boolean removeChunk(final ChunkPos pos) {
        return super.removeChunk(new ChunkRef(pos.x, pos.z));
    }

    @Override
    public Square getIslandBoundingBoxAsSquare() {
        final yorickbm.skyblockaddon.core.util.geometry.BoundingBox box = getIslandBoundingBox();
        return new yorickbm.skyblockaddon.util.geometry.Square(new Vec3i(box.minX, 0, box.minZ), new Vec3i(box.maxX, 0, box.maxZ));
    }

    @Override
    public Optional<UUID> getGroupByName(String groupName) {
        return getGroups().stream().filter(g -> g.getName().equalsIgnoreCase(groupName)).map(IslandGroup::getId).findFirst();
    }

    @Override
    public CompoundTag serializeNBT() {
        final CompoundTag tag = new CompoundTag();

        tag.putUUID("Id", getId());
        tag.putString("owner", getOwner().toString());
        tag.putString("biome", getBiome());
        tag.putString("skullTexture", getSkullTexture());
        tag.putBoolean("travelability", isVisible());
        tag.put("spawn", NBTUtil.Vec3iToNBT(getSpawn()));
        tag.put("center", NBTUtil.Vec3iToNBT(getCenter()));

        final CompoundTag groups = new CompoundTag();
        for(final IslandGroup group : getGroups()) {
            groups.put(group.getId().toString(), ((ForgeIslandGroup)group).serializeNBT());
        }
        tag.put("groups", groups);

        final CompoundTag members = new CompoundTag();
        for(int i = 0; i < super.members.size(); i++) {
            members.putUUID(i+"", super.members.get(i));
        }
        tag.put("members", members);

        final CompoundTag chunks = new CompoundTag();
        final List<ChunkRef> chunksData = getLoadedChunks();
        for (int i = 0; i < chunksData.size(); i++) {
            chunks.putString(i + "", chunksData.get(i).x() + "," + chunksData.get(i).z());
        }
        tag.put("chunks", chunks);

        return tag;
    }

    @Override
    public void deserializeNBT(final CompoundTag tag) {
        setId(tag.getUUID("Id"));
        if (tag.getString("owner").length() > 3) setOwner(UUID.fromString(tag.getString("owner")));

        setBiome(tag.getString("biome"));
        setVisibility(tag.getBoolean("travelability"));
        setSkullTexture(tag.getString("skullTexture"));
        setSpawn(NBTUtil.NBTToVec3i(tag.getCompound("spawn")));
        setCenter(NBTUtil.NBTToVec3i(tag.getCompound("center")));

        final CompoundTag groups = tag.getCompound("groups");
        for(final String groupUuid : groups.getAllKeys()) {
            final ForgeIslandGroup group = new ForgeIslandGroup();
            group.deserializeNBT(groups.getCompound(groupUuid));
            super.islandGroups.put(group.getId(), group);
        }

        if(super.islandGroups.isEmpty() || super.islandGroups.size() < 2) {
            final ItemStack item = new ItemStack(Items.RED_MUSHROOM);
            item.setHoverName(new TextComponent(SkyBlockAddonLanguage.getLocalizedString("gui.group.default.name")).withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.BLUE));

            final IslandGroup defaultG = new ForgeIslandGroup(SkyblockAddonCore.MOD_UUID, item, true);
            super.islandGroups.put(defaultG.getId(), defaultG);

            final ItemStack item2 = new ItemStack(Items.BROWN_MUSHROOM);
            item2.setHoverName(new TextComponent(SkyBlockAddonLanguage.getLocalizedString("gui.group.nonmember.name")).withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.BLUE));

            final IslandGroup defaultG2 = new ForgeIslandGroup(SkyblockAddonCore.MOD_UUID2, item2, true);
            super.islandGroups.put(defaultG2.getId(), defaultG2);
        }

        final CompoundTag members = tag.getCompound("members");
        for(final String key : members.getAllKeys()) {
            super.members.add(members.getUUID(key));
        }

        final CompoundTag chunks = tag.getCompound("chunks");
        for (final String key : chunks.getAllKeys()) {
            final String[] parts = chunks.getString(key).replaceAll("[\\[\\]\\s]", "").split(",");
            super.addChunk(new ChunkRef(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        }

    }
}
