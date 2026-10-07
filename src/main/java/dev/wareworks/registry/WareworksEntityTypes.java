package dev.wareworks.registry;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.EntityEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.client.render.PalletRenderer;
import dev.wareworks.content.storage.PalletEntity;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.common.Tags;

/**
 * Entity type registrations ({@code REGISTRATE.entity(name, Factory::new, category)…register()}).
 * <p>
 * Initialised through {@link #register()} from the {@code Wareworks} constructor, beside the other registry classes and
 * never from a static field of {@code Wareworks} — the rule the whole registry package follows.
 * <p>
 * This mod has exactly one entity and wants no more: everything else it owns is a block or an item, because a warehouse
 * is built out of blocks. The pallet is the one thing that cannot be ({@code PalletEntity}): a brass bay holds 65 536
 * items, an {@code ItemStack} caps at 99, and the load of a broken bay therefore has to be carried by something that is
 * not a stack. There is deliberately <b>no spawn egg and no item form</b>, which is also what makes "a pallet cannot be
 * pocketed and cannot be placed back as a filled bay" true with nothing to maintain.
 */
public final class WareworksEntityTypes {
    private static final CreateRegistrate REGISTRATE = Wareworks.registrate();

    /** Width and height of a pallet's bounding box: one block wide and low, like the load of a rack bay on the floor. */
    private static final float PALLET_WIDTH = 1.0F;
    private static final float PALLET_HEIGHT = 0.6F;
    /**
     * How far a client is told about a pallet, in chunks. Short on purpose: its item and count are only readable from
     * close up anyway, and a rack wall that is broken down can leave a row of them.
     */
    private static final int PALLET_TRACKING_RANGE = 8;
    /**
     * Ticks between position updates. A pallet falls and can be shoved, so this is Create's own interval for a package
     * (3) rather than a resting entity's: at 20 a falling pallet would visibly jump. A pallet that does not move costs
     * nothing either way, because the tracker only sends a packet when the position really changed.
     */
    private static final int PALLET_UPDATE_INTERVAL = 3;

    /**
     * The load of a broken rack bay ({@code docs/warehouse-system.md} §3.8, M28, issue #20, ADR-046): a plain
     * {@code Entity} in {@link MobCategory#MISC}, carrying one item key and a count.
     * <p>
     * Only three builder calls carry behaviour, and each replaces an override that would otherwise have to be written
     * and kept right:
     * <ul>
     * <li>{@code .fireImmune()} is the whole of "survives fire and lava": {@code Entity#lavaHurt} has its entire body
     * inside {@code if (!fireImmune())}, and every other burning path goes through {@code Entity#hurt}, which has no
     * health to take from a plain entity;</li>
     * <li>{@code .sized(…)} gives it a box a player can click and a hopper can find under it;</li>
     * <li>the {@code c:teleporting_not_supported} tag, which Create's own contraptions carry, asks other mods not to
     * teleport it — the pallet's own {@code canChangeDimensions} refuses the rest.</li>
     * </ul>
     * Deliberately <b>absent</b>: {@code .attributes(…)} (not a {@code LivingEntity}), a spawn egg, an item form, and
     * {@code .noSave()} — which must never be called here, because surviving a save, a chunk unload and a reload with
     * its load intact is the entity's main requirement and is already the default for a non-passenger.
     * <p>
     * {@code .renderer(…)} is Registrate's one client call in a common class: it is guarded by {@code FMLEnvironment}
     * inside the builder and the supplier's body is only resolved when it runs, so a dedicated server never loads
     * {@link PalletRenderer} — the same documented exception {@link WareworksBlockEntityTypes} relies on.
     */
    public static final EntityEntry<PalletEntity> PALLET = REGISTRATE
            .entity("pallet", PalletEntity::new, MobCategory.MISC)
            .properties(builder -> builder.sized(PALLET_WIDTH, PALLET_HEIGHT)
                    .fireImmune()
                    .clientTrackingRange(PALLET_TRACKING_RANGE)
                    .updateInterval(PALLET_UPDATE_INTERVAL))
            .tag(Tags.EntityTypes.TELEPORTING_NOT_SUPPORTED)
            .defaultLang()
            .renderer(() -> PalletRenderer::new)
            .register();

    private WareworksEntityTypes() {
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} entity types", REGISTRATE.getModid());
    }
}
