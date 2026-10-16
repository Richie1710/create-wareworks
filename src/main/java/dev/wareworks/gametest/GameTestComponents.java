package dev.wareworks.gametest;

import java.util.function.Supplier;

import dev.wareworks.Wareworks;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The data component types the GameTests need and no release has (ADR-064, issue #27): a component that hashes the
 * way Quark's seed pouch does, and one that holds NeoForge's {@link SimpleFluidContent}, the stock component a mod's
 * tank item stores its fluid in.
 * <p>
 * <b>Dev runs only.</b> {@code Wareworks} calls {@link #register} from a method of its own, and only while
 * {@code FMLLoader#isProduction} is false, so this class is resolved in dev runs alone (the pattern of ADR-014); the
 * release jar does not contain it at all ({@code build.gradle} leaves out {@code dev/wareworks/gametest}). Every dev
 * run registers both types — client, server, GameTest server, data and the visual runs — so {@code /test run} works in
 * {@code runClient} and {@code runServer} as well. The types are synced: a dev client joins a release server, but a
 * release client cannot join a dev server.
 */
public final class GameTestComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Wareworks.ID);

    /** A value with Quark 4.1's {@code equals} and identity {@code hashCode} ({@link IdentityHashedStack}). */
    public static final Supplier<DataComponentType<IdentityHashedStack>> IDENTITY_HASHED_STACK =
            COMPONENTS.registerComponentType("identity_hashed_stack", builder -> builder
                    .persistent(IdentityHashedStack.CODEC).networkSynchronized(IdentityHashedStack.STREAM_CODEC));

    /**
     * A fluid held by an item, as a modded tank's item holds it: NeoForge's {@link SimpleFluidContent}, whose own
     * {@code hashCode} hashes its stack with {@code FluidStack#hashFluidAndComponents}.
     */
    public static final Supplier<DataComponentType<SimpleFluidContent>> FLUID_CONTENT =
            COMPONENTS.registerComponentType("fluid_content", builder -> builder
                    .persistent(SimpleFluidContent.CODEC).networkSynchronized(SimpleFluidContent.STREAM_CODEC));

    private GameTestComponents() {
    }

    /** Adds the register to the mod bus. Called once, by {@code Wareworks}, outside production only. */
    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
