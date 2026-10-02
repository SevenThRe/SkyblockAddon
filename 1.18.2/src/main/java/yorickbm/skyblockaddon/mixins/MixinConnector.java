package yorickbm.skyblockaddon.mixins;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

public class MixinConnector implements IMixinConnector {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void connect() {
        Mixins.addConfigurations("/mixins/skyblockaddon.mixin.json");
        Mixins.addConfiguration("/mixins/quark.mixin.json");
        Mixins.addConfiguration("/mixins/buildinggadgets.mixin.json");
        Mixins.addConfiguration("/mixins/the_vault.mixin.json");
        Mixins.addConfiguration("/mixins/buildscape.mixin.json");
        Mixins.addConfiguration("/mixins/easy_villagers.mixin.json");
        Mixins.addConfiguration("/mixins/easy_piglins.mixin.json");
        Mixins.addConfiguration("/mixins/elevatorid.mixin.json");
        // CN fork: restores the vanilla overworld region weight that Terralith zeroes out, so a
        // sky vault level-type can generate terrain instead of bare void. Declared non-required
        // with defaultRequire 0, so a pack without TerraBlender/Terralith simply skips it.
        Mixins.addConfiguration("/mixins/terralith_region.mixin.json");
    }
}