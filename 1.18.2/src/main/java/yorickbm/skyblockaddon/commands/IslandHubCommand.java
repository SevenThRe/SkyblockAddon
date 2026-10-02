package yorickbm.skyblockaddon.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import yorickbm.skyblockaddon.commands.interfaces.Cmds;
import yorickbm.skyblockaddon.commands.interfaces.OverWorldCommandStack;

public class IslandHubCommand extends OverWorldCommandStack {
    public IslandHubCommand(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Cmds.literal("hub")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> execute(context.getSource(), (ServerPlayer) context.getSource().getEntity()))
        );
    }

    @Override
    public int execute(final CommandSourceStack command, final ServerPlayer executor) {
        if(super.execute(command, executor) == 0) return Command.SINGLE_SUCCESS;

        // CN fork: the hub is now the generated Sky Vault Altar, not the world spawn that used to
        // double as the player-island template. Going through HubManager means the altar is built
        // on first use, and the player lands on the altar rather than on a copy of their own
        // starting island.
        final net.minecraft.core.BlockPos hub =
                yorickbm.skyblockaddon.islands.HubManager.fallbackSpawn();
        if (hub == null) {
            final var spawn = command.getLevel().getSharedSpawnPos();
            executor.teleportTo(spawn.getX(), spawn.getY(), spawn.getZ());
            return Command.SINGLE_SUCCESS;
        }

        executor.teleportTo(hub.getX(), hub.getY(), hub.getZ());

        return Command.SINGLE_SUCCESS;
    }
}
