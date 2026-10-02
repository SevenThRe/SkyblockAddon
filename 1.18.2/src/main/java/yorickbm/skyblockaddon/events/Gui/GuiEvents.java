package yorickbm.skyblockaddon.events.Gui;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import yorickbm.guilibrary.GUILibraryRegistry;
import yorickbm.guilibrary.events.GuiDrawItemEvent;
import yorickbm.guilibrary.events.OpenMenuEvent;
import yorickbm.skyblockaddon.core.configs.SkyBlockAddonLanguage;
import yorickbm.skyblockaddon.core.islands.IslandGroup;
import yorickbm.skyblockaddon.core.islands.IslandManager;
import yorickbm.skyblockaddon.core.util.UsernameCache;
import yorickbm.skyblockaddon.islands.ForgeIsland;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class GuiEvents {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * CN fork: category_id -> Chinese display name for {@code %group_category%}.
     * Kept in sync with the category buttons in {@code assets/skyblockaddon/guis/permissions.json}.
     */
    private static final Map<String, String> CATEGORY_NAMES = Map.of(
            "admin", "管理控制",
            "general", "基础",
            "redstone", "红石",
            "storage", "存储",
            "create", "机械动力",
            "miscellaneous", "杂项",
            "vaulthunters", "宝库猎人"
    );

    @SubscribeEvent
    public void itemRenderer(final GuiDrawItemEvent event) {
        if(event.isCanceled()) return; //Skip if canceled

        //Ignore condition filter if no island_id is set
        if(!event.getHolder().getData().contains("island_id")) { return; }

        final ForgeIsland island = (ForgeIsland) IslandManager.getInstance().getIslandByUUID(event.getHolder().getData().getUUID("island_id"));

        //Ignore condition if not found
        if(island == null) { return; }

        final boolean isAdmin = event.getItemHolder().hasCondition("is_admin");
        final boolean isNotAdmin = event.getItemHolder().hasCondition("!is_admin");

        if (isAdmin || isNotAdmin) {
            final Optional<IslandGroup> group = island.getGroupForEntityUUID(event.getHolder().getOwner().getUUID());
            final boolean canAccessAdminMenu = group.map(g -> g.canDo("admin_menu")).orElse(true);
            final boolean isOwner = island.isOwner(event.getHolder().getOwner().getUUID());
            final boolean hasAdminPermissions = event.getHolder().getOwner().hasPermissions(Commands.LEVEL_ADMINS);

            final boolean shouldCancel = isAdmin
                    ? !canAccessAdminMenu && !isOwner && !hasAdminPermissions // Same logic as before
                    : canAccessAdminMenu || isOwner || hasAdminPermissions;   // Negated logic

            event.setCanceled(shouldCancel);
        }

        if(event.getItemHolder().hasCondition("is_op")) {
            event.setCanceled(!event.getHolder().getOwner().hasPermissions(Commands.LEVEL_ADMINS));
        }

        if(event.getItemHolder().hasCondition("is_part")) {
            event.setCanceled(!island.isPartOf(event.getHolder().getOwner().getUUID()));
        } else if(event.getItemHolder().hasCondition("!is_part")) {
            event.setCanceled(island.isPartOf(event.getHolder().getOwner().getUUID()));
        }

        // any_mod_loaded:mod1,mod2,... — hide item when none of the listed mods are loaded
        if (!event.isCanceled()) {
            for (final String condition : event.getItemHolder().getConditions()) {
                if (!condition.startsWith("any_mod_loaded:")) continue;
                final String[] mods = condition.substring("any_mod_loaded:".length()).split(",");
                final boolean anyLoaded = java.util.Arrays.stream(mods)
                        .map(String::trim)
                        .anyMatch(ModList.get()::isLoaded);
                if (!anyLoaded) {
                    event.setCanceled(true);
                    break;
                }
            }
        }
    }

    @SubscribeEvent
    public void dynamicTitle(final OpenMenuEvent event) {
        if (event.isCanceled()) return;
        if (!event.getData().contains("island_id")) return;

        final ForgeIsland island = (ForgeIsland) IslandManager.getInstance().getIslandByUUID(event.getData().getUUID("island_id"));
        if (island == null) return;

        final Map<String, String> replacements = buildIslandReplacements(island, event.getData());

        event.setTitle(event.getTitle().stream()
                .map(component -> {
                    final String text = applyReplacements(component.getString(), replacements);
                    return new TextComponent(text).withStyle(component.getStyle());
                })
                .filter(c -> c instanceof TextComponent)
                .map(c -> (TextComponent) c)
                .collect(Collectors.toList()));
    }

    @SubscribeEvent
    public void dynamicText(final GuiDrawItemEvent event) {
        if (event.isCanceled()) return;
        if (!event.getHolder().getData().contains("island_id")) return;

        final ForgeIsland island = (ForgeIsland) IslandManager.getInstance().getIslandByUUID(event.getHolder().getData().getUUID("island_id"));
        if (island == null) return;

        // When the owner name finishes loading async, redraw the GUI once so %owner% shows correctly.
        island.onNameLoaded(event.getHolder()::scheduleUpdate);

        final Map<String, String> replacements = buildIslandReplacements(island, event.getHolder().getData());

        event.getItemStackHolder().setDisplayName(event.getItemStackHolder().getDisplayName().stream()
                .map(component -> new TextComponent(applyReplacements(component.getString(), replacements)).withStyle(component.getStyle()))
                .filter(c -> c instanceof TextComponent).map(c -> (TextComponent) c)
                .collect(Collectors.toList()));

        event.getItemStackHolder().setLore(event.getItemStackHolder().getLore().stream()
                .map(innerList -> innerList.stream()
                        .map(component -> new TextComponent(applyReplacements(component.getString(), replacements)).withStyle(component.getStyle()))
                        .filter(c -> c instanceof TextComponent).map(c -> (TextComponent) c)
                        .collect(Collectors.toList()))
                .collect(Collectors.toList()));
    }

    private Map<String, String> buildIslandReplacements(final ForgeIsland island, final net.minecraft.nbt.CompoundTag data) {
        final Map<String, String> replacements = new HashMap<>();
        replacements.put("%owner%", UsernameCache.get(island.getOwner()).getNow("..."));
        replacements.put("%x%", island.getSpawn().getX() + "");
        replacements.put("%y%", island.getSpawn().getY() + "");
        replacements.put("%z%", island.getSpawn().getZ() + "");
        replacements.put("%biome%", island.getBiome());
        replacements.put("%visibility%", island.isVisible()
                ? SkyBlockAddonLanguage.getLocalizedString("island.public")
                : SkyBlockAddonLanguage.getLocalizedString("island.private"));

        if (data.contains("group_id")) {
            final IslandGroup group = island.getGroup(data.getUUID("group_id"));
            replacements.put("%group_name%", group.getName());
            replacements.put("%group_id%", group.getId().toString());
            replacements.put("%group_member_count%", group.getMembers().size() + "");

            if (data.getCompound(GUILibraryRegistry.MOD_ID).contains("category_id")) {
                final String categoryId = data.getCompound(GUILibraryRegistry.MOD_ID).getString("category_id");
                // CN fork: the category used to render as the raw id with its first letter
                // upper-cased ("admin" -> "Admin"), so it stayed English even in an otherwise
                // fully localized GUI. Resolve it through the same Chinese names the
                // permissions.json buttons use; unknown ids keep the original behaviour so new
                // upstream categories still render.
                final String localized = CATEGORY_NAMES.get(categoryId);
                if (localized != null) {
                    replacements.put("%group_category%", localized);
                } else {
                    final String fallback = categoryId.replace("_", " ");
                    replacements.put("%group_category%", Character.toUpperCase(fallback.charAt(0)) + fallback.substring(1));
                }
            }
        }

        return replacements;
    }

    private String applyReplacements(final String text, final Map<String, String> replacements) {
        return replacements.entrySet().stream()
                .reduce(text, (t, entry) -> t.replace(entry.getKey(), entry.getValue()), (a, b) -> b);
    }
}
