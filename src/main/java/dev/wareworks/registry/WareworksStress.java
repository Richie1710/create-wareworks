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
 * {@code getImpact} call, so config changes apply to tooltips at once and to kinetic networks when they re-propagate.
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
