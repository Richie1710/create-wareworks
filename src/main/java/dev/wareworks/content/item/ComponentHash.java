package dev.wareworks.content.item;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.redstone.displayLink.ClickToLinkBlockItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.TrackPlacement;

import dev.wareworks.core.inventory.ValueHash;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.LockCode;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.item.component.MapItemColor;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.SeededContainerLoot;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.SimpleFluidContent;

/**
 * The hash of {@link ItemKey} and {@code FluidKey}: the item (or fluid) and a contract-safe hash of its component patch
 * (ADR-064, issue #27). Equal keys hash equally whatever mod defined their components, which
 * {@link ItemStack#hashItemAndComponents} cannot promise: it multiplies in every value's own {@code hashCode}, and a
 * value whose {@code hashCode} breaks its {@code equals} — Quark's seed pouch contents, but also NeoForge's
 * {@code FoodProperties}, a name's colour or hover item and every vanilla stack container holding one of these — gives
 * two equal keys two hash codes, and no hash map finds the second.
 * <p>
 * <b>The key:</b> {@code mix(31 * identityHashCode(item) + patch)}, where {@code patch} is the sum over the patch's
 * entries of {@code mix(identityHashCode(type) * 0x9E3779B9 + v)} and {@code v} is the value's hash, or a fixed
 * constant for a removed prototype component. The sum does not depend on the patch map's insertion order, the
 * prototype is left out (one item has one), and the count is not part of a key. An entry of the key's own patch whose
 * type has a {@link ComponentMeanings meaning} — Create's toolbox inventory, a sand paper's polished item and a
 * symmetry wand's mirror — hashes its meaning instead of the value (ADR-066, issue #29), because that is what the key
 * compares; nested patches never do, and neither do {@link #fluidKey} and {@link #stack}.
 * <p>
 * <b>A value</b> is hashed by {@link ValueHash}'s rules, with this class as rule 2: the Minecraft, NeoForge and Create
 * classes verified in the decompiled sources, each hashed only from what its {@code equals} compares —
 * <ul>
 * <li>trusted with their own {@code hashCode} (exact class only, {@link #TRUSTED}): records of value types, of holders
 * and of identity classes, a player head's {@link ResolvableProfile} among them, and Create's records of plain values
 * (sequenced assembly progress, a display link's or a track's selection, schematicannon options);</li>
 * <li>by projection: NBT and {@link CustomData} (a compound as an unordered sum, a list in order); a holder by its key,
 * a direct holder by its value; {@link MutableComponent} by its contents and siblings, never its style or translation
 * arguments; {@link ItemContainerContents}, {@link BundleContents} and {@link ChargedProjectiles} by their stacks in
 * order, each with its count; {@link SimpleFluidContent} by its fluid, amount and patch; lore and written books;
 * enchantments; armour trims; jukebox songs by key; {@link FoodProperties} by nutrition, {@code can_always_eat}, the
 * number of effects and {@code using_converts_to}; bee occupants; {@link Vec3i} by its coordinates; Create's
 * {@link ClipboardContent} with its {@link ClipboardEntry entries} (tick, text and icon, never the amount beside the
 * icon) and a package's {@link PackageItem.PackageOrderData} by its numbers;</li>
 * <li>the two stack views a meaning is built from: {@link ComponentMeanings.ExactStack} like {@link #stack} (with its
 * count) and {@link ComponentMeanings.SameKind} like {@link #kind} (item and components).</li>
 * </ul>
 * Everything else — every mod record and every class with an {@code equals} of its own — counts by its component type
 * alone ({@link ValueHash#OPAQUE}). Within one run only: identity hashes change with every start.
 */
public final class ComponentHash extends ValueHash {
    private static final ComponentHash INSTANCE = new ComponentHash();
    /** Spreads the component type's identity hash before the value is added (the 32-bit golden ratio). */
    private static final int TYPE_SPREAD = 0x9E3779B9;
    /** What a removed prototype component adds in place of a value. */
    private static final int REMOVED = 0x5BD1E995;
    /**
     * Classes trusted with their own {@code hashCode}, exact class only: every part they can reach was checked to hash
     * exactly what their {@code equals} compares (ADR-064).
     */
    private static final Set<Class<?>> TRUSTED = Set.of(ResourceLocation.class, GlobalPos.class, Unbreakable.class,
            CustomModelData.class, DyedItemColor.class, MapItemColor.class, MapId.class, MapDecorations.class,
            LodestoneTracker.class, FireworkExplosion.class, Fireworks.class, BannerPatternLayers.class,
            PotDecorations.class, BlockItemStateProperties.class, LockCode.class, SeededContainerLoot.class,
            ItemAttributeModifiers.class, WritableBookContent.class, SuspiciousStewEffects.class, PotionContents.class,
            Instrument.class, ResolvableProfile.class,
            // Create 6.0.10: records of plain values whose equals and hashCode are the compiler's own.
            SequencedAssemblyRecipe.SequencedAssembly.class, ClickToLinkBlockItem.ClickToLinkData.class,
            BezierTrackPointLocation.class, TrackPlacement.ConnectingFrom.class,
            SchematicannonBlockEntity.SchematicannonOptions.class);

    private ComponentHash() {
    }

    /**
     * The hash of an item key: its item and its component patch (count ignored). An entry of the key's own patch whose
     * type has a {@link ComponentMeanings meaning} (ADR-066) is hashed by its meaning, which is what the key compares;
     * values nested inside other values never are. The stack must not be empty.
     */
    public static int itemKey(ItemStack stack) {
        return mix(31 * System.identityHashCode(stack.getItem())
                + (stack.isComponentsPatchEmpty() ? 0 : INSTANCE.patch(stack.getComponentsPatch(), 0, true)));
    }

    /** The hash of a fluid key: its fluid and its component patch (amount ignored). The stack must not be empty. */
    public static int fluidKey(FluidStack stack) {
        return mix(31 * System.identityHashCode(stack.getFluid())
                + (stack.isComponentsPatchEmpty() ? 0 : INSTANCE.patch(stack.getComponentsPatch(), 0, false)));
    }

    /**
     * The hash of a stack <b>with</b> its count, consistent with {@link ItemStack#matches}; {@code 0} for an empty
     * stack.
     */
    public static int stack(ItemStack stack) {
        return INSTANCE.stackHash(stack, 0);
    }

    /**
     * The hash of a stack by its item and components, count ignored, consistent with
     * {@link ItemStack#isSameItemSameComponents}; {@code 0} for an empty stack.
     */
    public static int kind(ItemStack stack) {
        return INSTANCE.kindHash(stack, 0);
    }

    private int stackHash(ItemStack stack, int depth) {
        if (stack.isEmpty())
            return 0;
        return mix(31 * (31 * System.identityHashCode(stack.getItem()) + stack.getCount()) + patchOf(stack, depth));
    }

    private int kindHash(ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty())
            return 0;
        return mix(31 * System.identityHashCode(stack.getItem()) + patchOf(stack, depth));
    }

    private int patchOf(ItemStack stack, int depth) {
        return stack.isComponentsPatchEmpty() ? 0 : patch(stack.getComponentsPatch(), depth, false);
    }

    /**
     * The unordered sum over the patch's entries. With {@code keyLevel} — the patch of an item key itself — an entry
     * whose type has a meaning hashes the meaning (ADR-066); a nested patch hashes every value by the rules.
     */
    private int patch(DataComponentPatch patch, int depth, boolean keyLevel) {
        int h = 0;
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            Optional<?> value = entry.getValue();
            int v;
            if (value.isEmpty()) {
                v = REMOVED;
            } else {
                ComponentMeanings.Meaning<?> meaning = keyLevel ? ComponentMeanings.of(entry.getKey()) : null;
                v = hash(meaning != null ? meaning.meaningOf(value.get()) : value.get(), depth + 1);
            }
            h += mix(System.identityHashCode(entry.getKey()) * TYPE_SPREAD + v);
        }
        return h;
    }

    @Override
    protected int hashValue(Object value, int depth) {
        if (value instanceof Tag tag)
            return tag(tag, depth);
        if (value instanceof CustomData data)
            return tag(data.getUnsafe(), depth + 1);
        if (value instanceof Holder<?> holder)
            return holder(holder, depth);
        if (value instanceof MutableComponent component)
            return component(component, depth);
        if (value instanceof ItemContainerContents contents)
            return stacks(contents.nonEmptyItems(), depth);
        if (value instanceof BundleContents bundle)
            return stacks(bundle.items(), depth);
        if (value instanceof ChargedProjectiles projectiles)
            return stacks(projectiles.getItems(), depth);
        if (value instanceof SimpleFluidContent fluid)
            return fluidContent(fluid, depth);
        if (value instanceof ItemLore lore) {
            int h = 1;
            for (Component line : lore.lines())
                h = 31 * h + hash(line, depth + 1);
            return h;
        }
        if (value instanceof WrittenBookContent book)
            return writtenBook(book, depth);
        if (value instanceof ItemEnchantments enchantments) {
            int h = 0;
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet())
                h += mix(31 * hash(entry.getKey(), depth + 1) + entry.getIntValue());
            return h;
        }
        if (value instanceof ArmorTrim trim)
            return 31 * hash(trim.material(), depth + 1) + hash(trim.pattern(), depth + 1);
        if (value instanceof JukeboxPlayable playable)
            return 31 * Objects.hashCode(playable.song().key()) + Boolean.hashCode(playable.showInTooltip());
        if (value instanceof FoodProperties food)
            return 31 * (31 * (31 * food.nutrition() + Boolean.hashCode(food.canAlwaysEat())) + food.effects().size())
                    + food.usingConvertsTo().map(stack -> stackHash(stack, depth + 1)).orElse(0);
        if (value instanceof BeehiveBlockEntity.Occupant occupant)
            return 31 * (31 * tag(occupant.entityData().getUnsafe(), depth + 1) + occupant.ticksInHive())
                    + occupant.minTicksInHive();
        if (value instanceof Vec3i pos)
            return (pos.getY() + pos.getZ() * 31) * 31 + pos.getX();
        if (value instanceof ClipboardContent clipboard)
            return clipboard(clipboard, depth);
        if (value instanceof ClipboardEntry entry)
            return clipboardEntry(entry, depth);
        if (value instanceof PackageItem.PackageOrderData order)
            return 31 * (31 * (31 * (31 * order.orderId() + order.linkIndex()) + Boolean.hashCode(order.isFinalLink()))
                    + order.fragmentIndex()) + Boolean.hashCode(order.isFinal());
        if (value instanceof ComponentMeanings.ExactStack exact)
            return stackHash(exact.stack(), depth);
        if (value instanceof ComponentMeanings.SameKind kind)
            return kindHash(kind.stack(), depth);
        if (TRUSTED.contains(value.getClass()))
            return value.hashCode();
        return structural(value, depth);
    }

    /**
     * A reference holder by its key — NeoForge's {@code Holder.Reference} and {@code DeferredHolder} equal every
     * reference holder with the same interned key — and a direct holder by its value.
     */
    private int holder(Holder<?> holder, int depth) {
        if (holder.kind() == Holder.Kind.REFERENCE)
            return Objects.hashCode(holder.getKey());
        return holder instanceof Holder.Direct<?> direct ? hash(direct.value(), depth + 1) : OPAQUE;
    }

    /** Stacks in order, each with its count: what {@code ItemStack#listMatches} compares. */
    private int stacks(Iterable<ItemStack> stacks, int depth) {
        int h = 1;
        for (ItemStack stack : stacks)
            h = 31 * h + stackHash(stack, depth + 1);
        return h;
    }

    /** Fluid, amount and patch: what {@code FluidStack#matches} compares. */
    private int fluidContent(SimpleFluidContent content, int depth) {
        if (content.isEmpty())
            return 0;
        FluidStack stack = content.copy();
        return mix(31 * (31 * System.identityHashCode(stack.getFluid()) + stack.getAmount())
                + (stack.isComponentsPatchEmpty() ? 0 : patch(stack.getComponentsPatch(), depth + 1, false)));
    }

    /**
     * Everything Create's clipboard record compares — its type, pages, read-only flag, last opened page and copied
     * values — with each entry through {@link #clipboardEntry}. Its own {@code hashCode} reaches
     * {@code ClipboardEntry#hashCode}, which hashes the text's style and every component value of the icon.
     */
    private int clipboard(ClipboardContent clipboard, int depth) {
        int h = 31 * (31 * hash(clipboard.type(), depth + 1) + Boolean.hashCode(clipboard.readOnly()))
                + clipboard.previouslyOpenedPage();
        h = 31 * h + hash(clipboard.copiedValues(), depth + 1);
        return 31 * h + hash(clipboard.pages(), depth + 1);
    }

    /**
     * What {@code ClipboardEntry#equals} compares, which is final: the tick, the text, and the icon by item and
     * components ({@code ItemStack#isSameItemSameComponents}, so every empty icon is the same one). The amount shown
     * beside the icon is left out, as {@code equals} leaves it out.
     */
    private int clipboardEntry(ClipboardEntry entry, int depth) {
        return 31 * (31 * Boolean.hashCode(entry.checked) + hash(entry.text, depth + 1)) + kindHash(entry.icon, depth);
    }

    private int writtenBook(WrittenBookContent book, int depth) {
        int h = 31 * (31 * (31 * book.title().hashCode() + book.author().hashCode()) + book.generation())
                + Boolean.hashCode(book.resolved());
        for (Filterable<Component> page : book.pages())
            h = 31 * h + 31 * hash(page.raw(), depth + 1)
                    + page.filtered().map(filtered -> hash(filtered, depth + 1)).orElse(0);
        return h;
    }

    /**
     * The contents — literal text, translation key and fallback, keybind — and the siblings. The style (which may hold
     * a hover item) and the translation arguments are never read.
     */
    private int component(MutableComponent component, int depth) {
        if (depth > MAX_DEPTH)
            return 0;
        ComponentContents contents = component.getContents();
        int h;
        if (contents instanceof PlainTextContents.LiteralContents literal)
            h = literal.text().hashCode();
        else if (contents instanceof TranslatableContents translatable)
            h = 31 * Objects.hashCode(translatable.getKey()) + Objects.hashCode(translatable.getFallback());
        else if (contents instanceof KeybindContents keybind)
            h = keybind.getName().hashCode();
        else
            h = 0;
        for (Component sibling : component.getSiblings())
            h = 31 * h + hash(sibling, depth + 1);
        return h;
    }

    /**
     * NBT by value: a compound as an unordered sum, a list in order. A float or double tag hashes its bits: a tag is
     * made only through {@code FloatTag#valueOf} or {@code DoubleTag#valueOf}, which fold {@code -0.0} into the zero
     * tag, so equal tags have equal bits.
     */
    private int tag(Tag tag, int depth) {
        if (tag == null || depth > MAX_DEPTH)
            return 0;
        if (tag instanceof CompoundTag compound) {
            int h = 0;
            for (String key : compound.getAllKeys())
                h += mix(31 * key.hashCode() + tag(compound.get(key), depth + 1));
            return h;
        }
        if (tag instanceof ListTag list) {
            int h = 1;
            for (int i = 0; i < list.size(); i++)
                h = 31 * h + tag(list.get(i), depth + 1);
            return h;
        }
        if (tag instanceof ByteArrayTag bytes)
            return Arrays.hashCode(bytes.getAsByteArray());
        if (tag instanceof IntArrayTag ints)
            return Arrays.hashCode(ints.getAsIntArray());
        if (tag instanceof LongArrayTag longs)
            return Arrays.hashCode(longs.getAsLongArray());
        if (tag instanceof FloatTag floatTag)
            return Float.floatToIntBits(floatTag.getAsFloat());
        if (tag instanceof DoubleTag doubleTag)
            return Double.hashCode(doubleTag.getAsDouble());
        if (tag instanceof ByteTag byteTag)
            return byteTag.getAsInt();
        if (tag instanceof ShortTag shortTag)
            return shortTag.getAsInt();
        if (tag instanceof IntTag intTag)
            return intTag.getAsInt();
        if (tag instanceof LongTag longTag)
            return Long.hashCode(longTag.getAsLong());
        if (tag instanceof StringTag string)
            return string.getAsString().hashCode();
        if (tag instanceof EndTag)
            return 0;
        return structural(tag, depth);
    }
}
