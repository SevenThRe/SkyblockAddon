package yorickbm.skyblockaddon;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.VersionChecker;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.FileUtils;
import org.slf4j.Logger;
import yorickbm.guilibrary.GUILibraryRegistry;
import yorickbm.guilibrary.events.DefaultEventHandler;
import yorickbm.skyblockaddon.chunk.ChunkTaskScheduler;
import yorickbm.skyblockaddon.configs.SkyblockAddonConfig;
import yorickbm.skyblockaddon.core.SkyblockAddonCore;
import yorickbm.skyblockaddon.core.events.IslandEventBus;
import yorickbm.skyblockaddon.core.islands.Island;
import yorickbm.skyblockaddon.core.islands.IslandManager;
import yorickbm.skyblockaddon.core.permissions.PermissionManager;
import yorickbm.skyblockaddon.core.util.RegistrySelector;
import yorickbm.skyblockaddon.core.util.ResourceManager;
import yorickbm.skyblockaddon.core.util.ThreadManager;
import yorickbm.skyblockaddon.core.util.UsernameCache;
import yorickbm.skyblockaddon.core.util.exceptions.TerralithFoundException;
import yorickbm.skyblockaddon.events.*;
import yorickbm.skyblockaddon.events.Gui.GuiEvents;
import yorickbm.skyblockaddon.events.Gui.IslandGuiEvents;
import yorickbm.skyblockaddon.events.Gui.RegistryGuiEvents;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

// The value here should match an entry in the META-INF/mods.toml file
@Mod("skyblockaddon")
public class SkyBlockAddon {
    private static final Logger LOGGER = LogUtils.getLogger();

    public SkyBlockAddon() {
        //Register Forge ModLoading events
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::processIMC);

        //Register configs
        FileUtils.getOrCreateDirectory(FMLPaths.CONFIGDIR.get().resolve(SkyblockAddonCore.MOD_ID), SkyblockAddonCore.MOD_ID);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SkyblockAddonConfig.SPEC, SkyblockAddonCore.MOD_ID + "/config.toml");

        //Register Forge minecraft events
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new ModEvents());
        MinecraftForge.EVENT_BUS.register(new PlayerEvents());
        MinecraftForge.EVENT_BUS.register(new PermissionEvents());

        //CN fork: level-type detection cache + deferred Terralith compatibility check.
        yorickbm.skyblockaddon.mixins.SkyVaultLevelType.register();
        MinecraftForge.EVENT_BUS.register(new TerralithGuard());

        MinecraftForge.EVENT_BUS.register(new DefaultEventHandler());
        MinecraftForge.EVENT_BUS.register(new GuiEvents());
        MinecraftForge.EVENT_BUS.register(new IslandGuiEvents());
        MinecraftForge.EVENT_BUS.register(new RegistryGuiEvents());

        MinecraftForge.EVENT_BUS.register(new ChunkEvents());

        if(SkyblockAddonConfig.getForKey("island.particles.border").equalsIgnoreCase("TRUE")) MinecraftForge.EVENT_BUS.register(new ParticleEvents());
    }

    public static void CustomDebugMessages(final org.apache.logging.log4j.Logger log, final String msg) {
        if(SkyblockAddonConfig.getForKey("permissions.debug").equalsIgnoreCase("TRUE")) log.info(msg);
    }

    public static boolean isDebugEnabled() {
        return SkyblockAddonConfig.getForKey("permissions.debug").equalsIgnoreCase("TRUE");
    }

    /**
     * Inter Mod Communications.
     * Checks against Terralith
     *
     * CN fork: upstream threw {@link TerralithFoundException} whenever Terralith was present,
     * which hard-crashed server startup — Terralith ships in the Wold's Vaults server pack, so
     * the mod could never be enabled there at all. Terralith's only real conflict is that it
     * zeroes the vanilla overworld region weight (see {@code TerralithRegionWeightMixin}), which
     * we now repair for sky vault worlds. So instead of a fatal error we report what we detected
     * and only refuse to continue if we are NOT on a sky vault world, where the conflict is real.
     */
    private void processIMC(InterModProcessEvent event) {
        LOGGER.info("Got IMC {}", event.getIMCStream().
                map(m -> m.messageSupplier().get()).
                collect(Collectors.toList()));

        if (!ModList.get().isLoaded("terralith")) return;

        LOGGER.warn("检测到 Terralith 模组。Terralith 会把 vanilla_overworld_region_weight 强制归零，"
                + "导致非 Terralith 的 level-type（天空宝库）无法生成地形。"
                + "本模组已在天空宝库世界接管该权重以完成修复。");

        if (yorickbm.skyblockaddon.mixins.SkyVaultLevelType.isSkyVaultWorld()) {
            LOGGER.info("当前世界为天空宝库，已启用 region 权重修复，Terralith 可与其共存。");
            return;
        }

        //At InterModProcessEvent time no MinecraftServer exists yet, so the level-type cannot be
        //read here (see SkyVaultLevelType.probe). Defer the decision to server start, where the
        //answer is actually knowable, instead of crashing a world that may well be fine.
        LOGGER.warn("当前尚未启动世界（InterModProcess 阶段无法读取 level-type），"
                + "延后到服务端启动时再校验 Terralith 冲突。");
        pendingTerralithCheck = true;
    }

    /**
     * CN fork: re-run the Terralith compatibility decision once the level-type is readable.
     * Registered as a normal Forge event handler instead of a static one, because the event bus
     * dispatches to instances.
     */
    public static final class TerralithGuard {
        @SubscribeEvent
        public void onServerStarting(final ServerStartingEvent event) {
            if (!pendingTerralithCheck) return;
            pendingTerralithCheck = false;

            if (!ModList.get().isLoaded("terralith")) return;

            if (yorickbm.skyblockaddon.mixins.SkyVaultLevelType.isSkyVaultWorld()) {
                LOGGER.info("[{}] 天空宝库 level-type 已确认，Terralith 共存检查通过，"
                        + "region 权重修复已生效。", SkyblockAddonCore.MOD_ID);
                return;
            }

            LOGGER.error("[{}] 当前世界不是天空宝库，Terralith 的 region 权重冲突无法自动修复。"
                    + "请把 level-type 设为天空宝库，或从服务端移除 Terralith。", SkyblockAddonCore.MOD_ID);
        }
    }

    private static boolean pendingTerralithCheck = false;

    /**
     * Run Common config setup.
     */
    public void onCommonSetup(final FMLCommonSetupEvent event) {
        //Register Resources
        RegistrySelector selector = new RegistrySelector(Map.of(
                "PermissionRegistry", () -> {
                    if (ModList.get().isLoaded("the_vault")) {
                        return "registries/permissions/vaulthunters.json";
                    } else {
                        return "registries/permissions/default.json";
                    }
                }
        ));
        ResourceManager.commonSetup(FMLPaths.CONFIGDIR.get(), selector, mod -> ModList.get().isLoaded(mod));

        //Register username cache
        UsernameCache.initCache(500);

        //Register guis
        GUILibraryRegistry.registerFolder(SkyblockAddonCore.MOD_ID, FMLPaths.CONFIGDIR.get().resolve(SkyblockAddonCore.MOD_ID + "/guis/"));

        //Register permissions: load groups first, then detect format (new dir vs old file)
        final java.util.function.Predicate<String> isModLoaded = modId -> ModList.get().isLoaded(modId);

        final java.nio.file.Path groupsDir  = FMLPaths.CONFIGDIR.get().resolve(SkyblockAddonCore.MOD_ID + "/registries/groups/");
        final java.nio.file.Path newPermsDir = FMLPaths.CONFIGDIR.get().resolve(SkyblockAddonCore.MOD_ID + "/registries/permissions/");
        final java.nio.file.Path oldPermsFile = FMLPaths.CONFIGDIR.get().resolve(SkyblockAddonCore.MOD_ID + "/registries/PermissionRegistry.json");

        if (groupsDir.toFile().isDirectory()) {
            yorickbm.skyblockaddon.core.registries.PermissionGroupRegistry.getInstance()
                    .loadFromDirectory(groupsDir, isModLoaded);
        }

        if (oldPermsFile.toFile().isFile()) {
            // Backward compat: user has customised old single-file config
            PermissionManager.getInstance().loadPermissions(oldPermsFile);
        } else if (newPermsDir.toFile().isDirectory()) {
            PermissionManager.getInstance().loadPermissions(newPermsDir, isModLoaded);
        }

        //Init Version Checker
        VersionChecker.startVersionCheck();
    }

    /**
     * Runs upon server starting event.
     */
    @SubscribeEvent
    public void onServerStarting(final ServerStartingEvent event) {
        IslandEventBus.register(new ForgeIslandEventBus(event.getServer()));

        // CN fork: the hub (悬浮宝库祭坛) needs the server handle to resolve its spawn level.
        // Reset here so a world reload rebuilds the cache instead of reusing stale coordinates.
        yorickbm.skyblockaddon.islands.HubManager.setServer(event.getServer());

        //Loading public island owner names into username cache form Mojang API
        IslandManager.getInstance().getIslands().stream().filter(Island::isVisible).forEach(Island::updateName);

        LOGGER.info(String.format("Loaded %s guis.", GUILibraryRegistry.getGuis()));
        LOGGER.info(String.format("Loaded %s permissions.", PermissionManager.getInstance().getPermissions().size()));
        LOGGER.info("Vaulthunters Skyblock addon v{} has loaded!", SkyblockAddonCore.VERSION);
    }

    /**
     * Runs upon Shutdown Event of server.
     */
    @SubscribeEvent
    public void onServerShutDown(final ServerStoppedEvent event) {
        // CN fork: drop cached world coordinates so the next start re-derives them.
        yorickbm.skyblockaddon.islands.HubManager.invalidate();
        try {
            ChunkTaskScheduler.clear();
            ThreadManager.terminateAllThreads();
        } catch (final NoClassDefFoundError ex) {
            //Seems to be thrown
        }
    }

    private static CompoundTag IslandNBTData = null;
    public static CompoundTag getIslandNBT(final MinecraftServer server) {
        if (IslandNBTData == null) {
            try {
                final File islandFile = new File(FMLPaths.CONFIGDIR.get().resolve(SkyblockAddonCore.MOD_ID) + "/island.nbt");
                IslandNBTData = NbtIo.readCompressed(islandFile);
            } catch (final IOException e) {
                LOGGER.error("Could not load external island.nbt file, using mod's internal island.nbt file.");
                try {
                    final Resource rs = server.getResourceManager().getResource(new ResourceLocation(SkyblockAddonCore.MOD_ID, "structures/island.nbt"));
                    IslandNBTData = NbtIo.readCompressed(rs.getInputStream());
                } catch (final IOException ex) {
                    LOGGER.error("Could not load mod's internal island.nbt file!!!");
                }
            }
        }
        return IslandNBTData;
    }
}
