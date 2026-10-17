package dev.wareworks.registry;

import java.util.function.DoubleSupplier;

import com.simibubi.create.api.stress.BlockStressValues;
import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.util.nullness.NonNullUnaryOperator;

import dev.wareworks.config.WareworksConfig;
import net.minecraft.world.level.block.Block;

/**
 * Stress registration for Wareworks kinetic blocks, the addon replacement for Create's {@code CStress.setImpact}
 * (which throws for non-Create registrates).
 * <p>
 * Impacts are registered in {@code BlockStressValues.IMPACTS} as suppliers. Create queries the supplier on every
 * {@code getImpact} call, so tooltips and goggles always show the value this side's config holds (on a client connected
 * to a dedicated server, the copy NeoForge synced while it joined, so a reloaded server file reaches them only after a
 * rejoin). A kinetic network, however, reads the impact only when a block entity joins it ({@code KineticNetwork#add}
 * and {@code #addSilently} call {@code calculateStressApplied}) and keeps that number per member; RPM changes are
 * applied on top of it, a changed impact is not.
 * <p>
 * A running network therefore counts a new value only once the crane joins it again through {@code #add}, which also
 * sets {@code networkDirty}, so the next tick's {@code updateNetwork} recomputes the total: the dock's shaft (or any
 * kinetic block between it and the source) disconnected and connected again, or a speed change at the source or at a
 * Rotation Speed Controller on the way ({@code GeneratingKineticBlockEntity#applyNewSpeed},
 * {@code SpeedControllerBlockEntity#updateTargetRotation}), which detaches the tree below it and attaches it again. A
 * load is no such join: {@code #addSilently} stores the new impact per member, but neither it nor {@code initFromTE},
 * which starts the total from the saved one, recomputes the total from the new impacts. {@code currentStress} and every
 * member's {@code stress} and {@code overStressed} stay as saved until the network's next
 * {@code updateStress}/{@code updateNetwork}, for example a generator's {@code updateGeneratedRotation} or a member
 * joining or leaving; a creative motor runs the former in its {@code initialize}, which sees the dock's new impact only
 * if the dock has initialised first.
 */
public final class WareworksStress {
    private WareworksStress() {
    }

    /**
     * Registers the block's stress impact at 1 RPM. Usage: {@code .transform(WareworksStress.impact(() -> 2.0))}.
     * The supplier must not throw; it is called on server and client.
     */
    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> impact(DoubleSupplier impactAtOneRpm) {
        return builder -> builder.onRegister(block -> BlockStressValues.IMPACTS.register(block, impactAtOneRpm));
    }

    /**
     * Registers the stacker crane impact from the server config ({@code crane.stressImpact}), falling back to the
     * default while the config is not loaded.
     */
    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> configuredImpact() {
        return impact(WareworksConfig::stressImpact);
    }
}
