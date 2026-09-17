package dev.wareworks.registry;

import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.DeliveryStationArmPoint;
import dev.wareworks.content.station.StationArmPointType;
import dev.wareworks.content.station.WarehouseInputArmPoint;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Create mechanical arm interaction point types ({@code docs/warehouse-system.md} §3.2, M12): one per station block, so
 * a player's arm can put items into a warehouse input and take them out of a warehouse output, terminal or production
 * station.
 * <p>
 * <b>Registry.</b> The registry is Create's ({@code CreateRegistries.ARM_INTERACTION_POINT_TYPE}). Create builds it with
 * NeoForge's {@code RegistryBuilder} in {@code CreateBuiltInRegistries}, which a mixin loads at the end of
 * {@code BuiltInRegistries}' static initialiser, and adds it to {@code BuiltInRegistries.REGISTRY} right there. NeoForge
 * posts a {@code RegisterEvent} for every key of that registry of registries, so a {@link DeferredRegister} receives the
 * event like for a vanilla registry; the registry's bake callback then sorts all types by priority when it freezes
 * (GameTest {@code stationarmpointtypes}).
 * <p>
 * <b>Ids.</b> Each station has its own id, the block's name, even where two stations behave alike today: an arm saves the
 * type id with each point, so one station's arm behaviour can change later without breaking arms in existing worlds.
 * <p>
 * <b>Items never teleport.</b> An arm is the player's own Create machine; it moves items through the same capability views
 * funnels, chutes and hoppers use, and the crane stays the only thing that moves items inside the warehouse. There is
 * deliberately no arm point for the warehouse interface (it has no inventory of its own, and its chest is storage: the
 * Create way to reach a chest is a funnel on it), the controller, the stacker crane dock or the rail.
 * <p>
 * Initialised through {@link #register} after {@link WareworksMenuTypes#register()}.
 */
public final class WareworksArmInteractionPoints {
    private static final DeferredRegister<ArmInteractionPointType> REGISTER =
            DeferredRegister.create(CreateRegistries.ARM_INTERACTION_POINT_TYPE, Wareworks.ID);

    /** The warehouse input: deposit only. */
    public static final DeferredHolder<ArmInteractionPointType, StationArmPointType> WAREHOUSE_INPUT = REGISTER.register(
            "warehouse_input", () -> new StationArmPointType(WareworksBlocks.WAREHOUSE_INPUT, WarehouseInputArmPoint::new));

    /** The warehouse output: take only. */
    public static final DeferredHolder<ArmInteractionPointType, StationArmPointType> WAREHOUSE_OUTPUT = REGISTER.register(
            "warehouse_output", () -> new StationArmPointType(WareworksBlocks.WAREHOUSE_OUTPUT, DeliveryStationArmPoint::new));

    /** The warehouse terminal: take only. */
    public static final DeferredHolder<ArmInteractionPointType, StationArmPointType> WAREHOUSE_TERMINAL = REGISTER.register(
            "warehouse_terminal",
            () -> new StationArmPointType(WareworksBlocks.WAREHOUSE_TERMINAL, DeliveryStationArmPoint::new));

    /** The warehouse production station: take only. */
    public static final DeferredHolder<ArmInteractionPointType, StationArmPointType> WAREHOUSE_PRODUCTION =
            REGISTER.register("warehouse_production",
                    () -> new StationArmPointType(WareworksBlocks.WAREHOUSE_PRODUCTION, DeliveryStationArmPoint::new));

    private WareworksArmInteractionPoints() {
    }

    public static void register(IEventBus modEventBus) {
        REGISTER.register(modEventBus);
    }
}
