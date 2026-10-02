package yorickbm.skyblockaddon.configs;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.HashMap;

public class SkyblockAddonConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    private static final HashMap<String, ForgeConfigSpec.ConfigValue<String>> values = new HashMap<>();

    static {

        BUILDER.push("config");

        register("island.spawn.height", "110", "Define bottom height of your island.NBT to spawn at.");
        register("island.particles.border", "TRUE", "Enable/Disable the particle border that is generated at island buildable edge.");
        register("island.create.cooldown", "5", "Cooldown before /island create may be executed again. (0 for no cooldown).");

        //CN fork：主城（悬浮宝库祭坛）高度。刻意与 island.spawn.height 错开，
        //这样主城与玩家空岛在视觉与坐标上都不会被误认成同一个地方。
        register("hub.spawn.height", "128", "[CN] 主城「悬浮宝库祭坛」的地面高度。刻意与 island.spawn.height 不同，避免与玩家空岛混淆。");

        register("permissions.debug", "FALSE", "Print debug messages on island permission events into console. This helps verifying and adding new permissions into your configuration.");

        register("purge.batch_time", "15", "Amount of ticks (20 ticks +- 1 second) in between filling batch buffer.");
        register("purge.concurrent", "1", "Amount of chunks allowed to be processed concurrent.");
        register("purge.blocks", "800", "Amount of blocks to remove per execution.");


        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private static void register(final String key, final String value, final String comment) {
        values.put(
                key,
                BUILDER.comment(comment)
                        .define(key, value)
        );
    }

    public static String getForKey(final String key) {
        if (!values.containsKey(key))
            return key; //Return lookup value if not found
        return values.get(key).get(); //Return value from config
    }

    public static boolean setValueForKey(final String key, final String value) {
        if (!values.containsKey(key)) return false;
        values.get(key).set(value);
        return true;
    }

}