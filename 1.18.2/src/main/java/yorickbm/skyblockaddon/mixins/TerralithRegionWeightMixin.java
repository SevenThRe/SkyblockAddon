package yorickbm.skyblockaddon.mixins;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yorickbm.skyblockaddon.core.SkyblockAddonCore;

/**
 * CN fork — see {@link SkyVaultLevelType} for the full rationale.
 *
 * <p>Redirects the same {@code Config.addNumber} call site that Terralith's
 * {@code MixinTerrablenderConfig} redirects, on the same target class
 * ({@code terrablender.config.TerraBlenderConfig}).
 *
 * <h3>Why everything here is a String</h3>
 * TerraBlender is not on this mod's compile classpath — only Wold's Vaults' server pack ships
 * it, and a mod must compile without its optional integrations present. So the target class and
 * the {@code addNumber} descriptor are given as literals (exactly as this project's other
 * optional-dependency mixins do) and the parameter is typed {@code Object}. The mixin config is
 * declared non-required with {@code defaultRequire: 0}, so on a pack without
 * TerraBlender/Terralith it is simply never applied.
 *
 * <h3>Interaction with Terralith's own redirect</h3>
 * Mixin does not allow two {@code @Redirect}s on one call site. Terralith ships first-class and
 * wins that slot; this mixin then simply fails to apply, which is harmless — Terralith's handler
 * passes every key <em>except</em> {@code vanilla_overworld_region_weight} straight through to
 * {@code addNumber}. {@link SkyVaultLevelType} therefore additionally pre-seeds the correct
 * weight into TerraBlender's own config file before Terralith reads it, which is the part that
 * actually holds regardless of who owns the call site.
 */
/**
 * Priority 1100 (above the default 1000) so this mixin is applied before Terralith's
 * {@code MixinTerrablenderConfig} and gets first refusal on the contested {@code addNumber}
 * call site. When two mixins target the same site Mixin keeps the higher-priority one, so this
 * is what actually decides the winner — not classpath or mod load order.
 */
@Mixin(targets = "terrablender.config.TerraBlenderConfig", remap = false, priority = 1100)
public abstract class TerralithRegionWeightMixin {

    private static final Logger LOGGER = LogManager.getLogger();

    @Redirect(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lterrablender/config/Config;addNumber(Ljava/lang/String;Ljava/lang/String;"
                            + "Ljava/lang/Number;Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;"
            )
    )
    private <T extends Number & Comparable<T>> T skyblockaddon$keepSkyVaultOverworldRegion(
            final terrablender.config.Config config,
            final String comment,
            final String key,
            final T defaultValue,
            final T min,
            final T max
    ) {
        if (SkyVaultLevelType.shouldPreserveVanillaOverworldRegion(key, defaultValue)) {
            @SuppressWarnings("unchecked")
            final T weight = (T) Integer.valueOf(SkyVaultLevelType.VANILLA_OVERWORLD_REGION_WEIGHT);
            LOGGER.info("[{}] 天空宝库世界：{} 由 0 恢复为 {}。",
                    SkyblockAddonCore.MOD_ID, key, SkyVaultLevelType.VANILLA_OVERWORLD_REGION_WEIGHT);
            return weight;
        }

        //Not a sky vault world (or a different key): call the real addNumber, exactly like stock.
        return config.addNumber(comment, key, defaultValue, min, max);
    }
}
