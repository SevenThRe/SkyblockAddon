package yorickbm.skyblockaddon.events;

import com.mojang.authlib.GameProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.command.ConfigCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import yorickbm.skyblockaddon.capabilities.SkyblockAddonWorldCapability;
import yorickbm.skyblockaddon.capabilities.SkyblockAddonWorldProvider;
import yorickbm.skyblockaddon.commands.*;
import yorickbm.skyblockaddon.commands.op.*;
import yorickbm.skyblockaddon.core.SkyblockAddonCore;
import yorickbm.skyblockaddon.core.islands.Island;
import yorickbm.skyblockaddon.core.islands.IslandManager;
import yorickbm.skyblockaddon.core.util.UsernameCache;
import yorickbm.skyblockaddon.islands.ForgeIsland;

public class ModEvents {
    private static final Logger LOGGER = LogManager.getLogger();

    @SubscribeEvent
    public void onCommandRegister(final RegisterCommandsEvent event) {
        //Basic commands
        new IslandCommand(event.getDispatcher());
        new IslandCreateCommand(event.getDispatcher());
        new IslandInviteCommand(event.getDispatcher());
        new IslandLeaveCommand(event.getDispatcher());
        new IslandRequestTeleportCommand(event.getDispatcher());
        new IslandTeleportCommand(event.getDispatcher());
        new IslandTravelCommand(event.getDispatcher());
        new IslandHubCommand(event.getDispatcher());
        new IslandAddGroupMemberCommand(event.getDispatcher());

        //Functional commands
        new FunctionRegistryCommand(event.getDispatcher());

        //Admin commands
        new AdminGetIdCommand(event.getDispatcher());
        new AdminMenuCommand(event.getDispatcher());
        new AdminTeleportCommand(event.getDispatcher());
        new ConfigReloadCommand(event.getDispatcher());
        new AdminAddMemberCommand(event.getDispatcher());
        new DebugCommand(event.getDispatcher());
        new AdminPurgeCommand(event.getDispatcher());
        new AdminSkullCommand(event.getDispatcher());
        new AdminLoadIslandCommand(event.getDispatcher());
        new AdminCleanChunksCommand(event.getDispatcher());

        ConfigCommand.register(event.getDispatcher());
        LOGGER.info("Registered commands for " + SkyblockAddonCore.MOD_ID);
    }

    @SubscribeEvent
    public void onWorldSave(final WorldEvent.Save event) {
        if (event.getWorld().isClientSide()) return;
        if (!(event.getWorld() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return; //World save fires once per dimension; only act on the overworld.
        level.getCapability(SkyblockAddonWorldProvider.SKYBLOCKADDON_WORLD_CAPABILITY).ifPresent(SkyblockAddonWorldCapability::saveIslandsToDisk);
    }

    @SubscribeEvent
    public void onRegisterCapabilities(final RegisterCapabilitiesEvent event) {
        event.register(SkyblockAddonWorldCapability.class);
    }

    @SubscribeEvent
    public void onAttachCapabilitiesWorld(final AttachCapabilitiesEvent<Level> event) {
        if (event.getObject().dimension() != Level.OVERWORLD) return;
        if (event.getObject().getCapability(SkyblockAddonWorldProvider.SKYBLOCKADDON_WORLD_CAPABILITY).isPresent())
            return;

        if (event.getObject() instanceof final ServerLevel level) {
            event.addCapability(new ResourceLocation(SkyblockAddonCore.MOD_ID, "properties"), new SkyblockAddonWorldProvider(event.getObject().getServer()));
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(final PlayerEvent.PlayerLoggedInEvent event) {
        final GameProfile profile = event.getPlayer().getGameProfile();
        UsernameCache.registerPlayername(profile.getId(), profile.getName());

        event.getPlayer().getLevel().getServer().getLevel(Level.OVERWORLD).getCapability(SkyblockAddonWorldProvider.SKYBLOCKADDON_WORLD_CAPABILITY).ifPresent(cap -> {
            //Load user island into cache
            IslandManager.getInstance().loadIslandIntoReverseCache(event.getPlayer().getUUID());

            // CN fork: make the landing spot unambiguous.
            //
            // Vanilla drops a joining player at the level's spawn point, which is the hub. For a
            // player who already owns an island that is wrong twice over: the hub is not theirs,
            // and the hub is a large built altar that used to be a byte-identical copy of their
            // own starting island — so the confusion the mod was supposed to prevent was in fact
            // caused by this very line's absence.
            //
            // Players with an island are placed on their own island spawn; players without one
            // are placed on the hub, which is generated on demand by HubManager.
            // PlayerEvent#getPlayer is typed as Player; on a dedicated server it is always a
            // ServerPlayer, but the cast is checked rather than assumed.
            if (!(event.getPlayer() instanceof final ServerPlayer player)) return;
            final Island island = IslandManager.getInstance().getIslandByEntityUUID(player.getUUID());
            if (island instanceof ForgeIsland forgeIsland) {
                forgeIsland.teleportTo(player);
            } else {
                final net.minecraft.core.BlockPos hub =
                        yorickbm.skyblockaddon.islands.HubManager.fallbackSpawn();
                if (hub != null) {
                    player.teleportTo(hub.getX(), hub.getY(), hub.getZ());
                }
            }
        });
    }
}
