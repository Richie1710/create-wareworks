package dev.wareworks.content.selector;

import java.util.Objects;
import java.util.Optional;

import com.simibubi.create.content.logistics.box.PackageItem;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The recipe slip a production station in slip mode puts into every package its Packager packs (ADR-085, design §7.1):
 * which result the ingredients in the box are for, and which station sent it when.
 * <p>
 * <b>Data on the box, never an item in it</b> (I1): it lives in the box's vanilla {@code minecraft:custom_data} under
 * {@value #KEY}. Not a registered component on purpose: a box whose registered component a server no longer knows
 * fails to load as a whole, with its nine stacks, while {@code custom_data} is vanilla, saved and synced, and copied by
 * every transport that moves a box (bees, frogports, chains, trains, chests).
 * <ul>
 * <li>{@link #result()}: the result item's id, which the factory's Ender Recipe Selector sets the machine's filter to.</li>
 * <li>{@link #from()}: the station's address as the goggles show it.</li>
 * <li>{@link #origin()}: the station's dimension and position; an Ender selector serves one origin at a time.</li>
 * <li>{@link #seq()}: the game time of the station's last switch; a box of an older switch is refused.</li>
 * </ul>
 * This is the <b>only</b> class that reads or writes {@code custom_data} ({@code PackageOrderBoundaryTest}), and only
 * {@code WarehouseProductionBlock} stamps.
 */
public record RecipeSlip(ResourceLocation result, String from, GlobalPos origin, long seq) {
    /** The key of the slip inside the box's {@code minecraft:custom_data}. */
    public static final String KEY = "wareworks:recipe_slip";

    private static final String RESULT = "Result";
    private static final String FROM = "From";
    private static final String ORIGIN = "Origin";
    private static final String SEQ = "Seq";

    public RecipeSlip {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(origin, "origin");
    }

    /** A slip for {@code result}. */
    public static RecipeSlip of(Item result, String from, GlobalPos origin, long seq) {
        return new RecipeSlip(BuiltInRegistries.ITEM.getKey(result), from, origin, seq);
    }

    /** The result item, if this server knows its id (an unknown id is never guessed). */
    public Optional<Item> item() {
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(result);
        return item.filter(found -> found != Items.AIR);
    }

    /** The slip in {@code box}, if it is a package with a readable slip. Never throws. */
    public static Optional<RecipeSlip> read(ItemStack box) {
        if (box == null || box.isEmpty() || !PackageItem.isPackage(box))
            return Optional.empty();
        CustomData data = box.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(KEY))
            return Optional.empty();
        Tag tag = data.getUnsafe().get(KEY);
        return tag instanceof CompoundTag compound ? fromTag(compound) : Optional.empty();
    }

    /**
     * Writes {@code slip} into {@code box}, replacing any slip it had; everything else in its {@code custom_data}
     * stays. Only a package takes a slip.
     */
    public static void stamp(ItemStack box, RecipeSlip slip) {
        Objects.requireNonNull(slip, "slip");
        if (box == null || box.isEmpty() || !PackageItem.isPackage(box))
            return;
        CustomData.update(DataComponents.CUSTOM_DATA, box, tag -> tag.put(KEY, slip.toTag()));
    }

    /** The slip as NBT. */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(RESULT, result.toString());
        tag.putString(FROM, from);
        GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, origin).result().ifPresent(found -> tag.put(ORIGIN, found));
        tag.putLong(SEQ, seq);
        return tag;
    }

    /** A slip read back from NBT; empty for anything malformed. Never throws. */
    public static Optional<RecipeSlip> fromTag(CompoundTag tag) {
        if (tag == null || !tag.contains(RESULT, Tag.TAG_STRING) || !tag.contains(ORIGIN)
                || !tag.contains(SEQ, Tag.TAG_ANY_NUMERIC))
            return Optional.empty();
        ResourceLocation result = ResourceLocation.tryParse(tag.getString(RESULT));
        if (result == null)
            return Optional.empty();
        Optional<GlobalPos> origin = GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.get(ORIGIN)).result();
        if (origin.isEmpty())
            return Optional.empty();
        return Optional.of(new RecipeSlip(result, tag.getString(FROM), origin.get(), tag.getLong(SEQ)));
    }
}
