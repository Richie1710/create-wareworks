package dev.wareworks.content.item;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.sandPaper.SandPaperItemComponent;
import com.simibubi.create.content.equipment.symmetryWand.mirror.SymmetryMirror;
import com.simibubi.create.content.equipment.toolbox.ToolboxInventory;
import com.simibubi.create.foundation.item.ItemSlots;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * What an item key compares instead of a component value's own {@code equals}, for the few component types whose
 * {@code equals} does not compare what they hold (ADR-066, issue #29, amending ADR-064).
 * <p>
 * A <b>meaning</b> maps a value to a list built only from values whose {@code equals} is by value and which
 * {@link ComponentHash} hashes consistently with it: JDK values, enum constants, lists, and two views of a stack —
 * {@link ExactStack} (item, components and count, as {@link ItemStack#matches}) and {@link SameKind} (item and
 * components, as {@link ItemStack#isSameItemSameComponents}). Two values of a registered type are the same when their
 * meanings are equal, and {@link ComponentHash#itemKey} hashes the entry by its meaning, so the equality and the hash
 * agree by construction. A meaning also brings a <b>detach</b>: a copy of a value that shares nothing mutable with it
 * and means the same, so that a key cannot be changed behind its cached hash by whoever holds the stack it was made
 * from.
 * <p>
 * Registered:
 * <ul>
 * <li>{@code create:toolbox_inventory} (Create's {@link ToolboxInventory}, whose {@code equals} compares its filter
 * stacks by identity and never its contents): {@code [its slots as ExactStack, its filters as SameKind, limitedMode]},
 * never {@code settling} and never the block entity. The filters and the mode are private to Create and read through
 * {@link MethodHandles#privateLookupIn} (Create's jar is an automatic module, which opens every package); the detach is
 * Create's own decoding ({@code deserialize}) of copies of the slots and filters. If one of the three members cannot be
 * resolved, toolboxes keep Create's own comparison and one warning is logged.</li>
 * <li>{@code create:sand_paper_polishing} ({@link SandPaperItemComponent}, whose {@code equals} never equals another
 * instance): {@code [the polished stack as ExactStack]}.</li>
 * <li>{@code create:symmetry_wand} ({@link SymmetryMirror}, whose final {@code equals} never compares the kind of
 * mirror, so an orthogonal cross-plane mirror and a triple-plane mirror at one position are equal, and whose value the
 * wand moves in place): {@code [the type name, the orientation index, the position as three doubles, enable, the
 * orientation]} — what Create's {@code equals} compares plus the type name, so it only ever tells apart what Create
 * calls equal. The detach is the component's own codec, an encode and a decode as a save and a load make them, put
 * into the stack as a decoded patch ({@link #detach(ItemStack)}).</li>
 * </ul>
 * Meanings apply to the entries of a stack's own patch, never inside another value: in {@link ItemKey}, and where the
 * warehouse decides that two stacks become one (a station buffer's insert and the crane's), which merges only stacks
 * of one key, because a container merges two Create toolboxes without filters whatever they hold.
 */
public final class ComponentMeanings {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A patch entry's place in {@link #patchText}: the component type's registered name, which is what its
     * {@code toString} prints ({@code Util#getRegisteredName}).
     */
    private static final Comparator<DataComponentType<?>> BY_TYPE_NAME =
            Comparator.comparing((DataComponentType<?> type) -> String.valueOf(type));

    /** Whether a value that its codec cannot copy faithfully has been logged. */
    private static volatile boolean decodeWarned;

    /** The registered meanings, looked up by the identity of their component type. */
    private static final List<Meaning<?>> MEANINGS = register();

    private ComponentMeanings() {
    }

    /**
     * How an item key reads one component type.
     *
     * @param type    the component type
     * @param meaning what a value holds, as a list of values that are equal and hashed by value
     * @param detach  a copy of a value sharing nothing mutable with it, with the same meaning, set into the stack in
     *                place of the value; never throws. {@code null}: the copy is what the type's own codec saves and
     *                loads, put into the stack as a decoded patch ({@link ComponentMeanings#detach(ItemStack)})
     */
    public record Meaning<T>(DataComponentType<T> type, Function<T, List<?>> meaning,
            @Nullable UnaryOperator<T> detach) {
        public Meaning {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(meaning, "meaning");
        }

        /** The meaning of {@code value}, which must be a value of {@link #type}. */
        @SuppressWarnings("unchecked")
        public List<?> meaningOf(Object value) {
            return meaning.apply((T) value);
        }

        /** Whether two values of {@link #type} mean the same. */
        public boolean equivalent(Object a, Object b) {
            return a == b || meaningOf(a).equals(meaningOf(b));
        }
    }

    /**
     * A stack with its count, equal as {@link ItemStack#matches} compares (every empty stack is one) and hashed like
     * {@link ComponentHash#stack}.
     */
    public record ExactStack(ItemStack stack) {
        public ExactStack {
            Objects.requireNonNull(stack, "stack");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ExactStack other && ItemStack.matches(stack, other.stack);
        }

        @Override
        public int hashCode() {
            return ComponentHash.stack(stack);
        }

        /** Count, item id and {@link #patchText}. */
        @Override
        public String toString() {
            if (stack.isEmpty())
                return "-";
            return stack.getCount() + " " + itemId(stack)
                    + (stack.isComponentsPatchEmpty() ? "" : patchText(stack.getComponentsPatch()));
        }
    }

    /**
     * A stack by its item and components, count ignored, equal as {@link ItemStack#isSameItemSameComponents} compares
     * (every empty stack is one) and hashed like {@link ComponentHash#kind}.
     */
    public record SameKind(ItemStack stack) {
        public SameKind {
            Objects.requireNonNull(stack, "stack");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SameKind other && ItemStack.isSameItemSameComponents(stack, other.stack);
        }

        @Override
        public int hashCode() {
            return ComponentHash.kind(stack);
        }

        /** Item id and {@link #patchText}. */
        @Override
        public String toString() {
            if (stack.isEmpty())
                return "-";
            return itemId(stack) + (stack.isComponentsPatchEmpty() ? "" : patchText(stack.getComponentsPatch()));
        }
    }

    /** The meaning registered for {@code type}, or {@code null}. */
    public static @Nullable Meaning<?> of(DataComponentType<?> type) {
        for (Meaning<?> meaning : MEANINGS) {
            if (meaning.type() == type)
                return meaning;
        }
        return null;
    }

    /** Whether the patch of {@code stack} holds a value of a registered type. */
    public static boolean present(ItemStack stack) {
        if (stack.isComponentsPatchEmpty())
            return false;
        for (Meaning<?> meaning : MEANINGS) {
            // has() reads the stack's map without marking it shared; the patch is asked only when it may hold one.
            if (!stack.has(meaning.type()))
                continue;
            Optional<?> value = stack.getComponentsPatch().get(meaning.type());
            if (value != null && value.isPresent())
                return true;
        }
        return false;
    }

    /**
     * {@link ItemStack#isSameItemSameComponents} with a registered type compared by its meaning: the same item, then,
     * when either patch holds a registered type, the same patch entry by entry — the same types and removals, a
     * registered type's value by its meaning and every other value by its own {@code equals}. One item has one
     * prototype, and a patch never holds a value equal to the prototype's, so comparing the patches is what
     * {@code PatchedDataComponentMap#equals} does for two stacks of one item. Without a registered type it is
     * {@link ItemStack#isSameItemSameComponents} itself.
     */
    public static boolean sameItemSameComponents(ItemStack a, ItemStack b) {
        if (!a.is(b.getItem()))
            return false;
        if (a.isEmpty() && b.isEmpty())
            return true;
        if (!present(a) && !present(b))
            return ItemStack.isSameItemSameComponents(a, b);
        DataComponentPatch patchA = a.getComponentsPatch();
        DataComponentPatch patchB = b.getComponentsPatch();
        if (patchA.size() != patchB.size())
            return false;
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patchA.entrySet()) {
            Optional<?> other = patchB.get(entry.getKey());
            if (other == null)
                return false;
            Optional<?> value = entry.getValue();
            if (value.isPresent() != other.isPresent())
                return false;
            if (value.isEmpty())
                continue;
            Meaning<?> meaning = of(entry.getKey());
            boolean same = meaning != null ? meaning.equivalent(value.get(), other.get())
                    : Objects.equals(value.get(), other.get());
            if (!same)
                return false;
        }
        return true;
    }

    /**
     * Replaces, <b>in place</b>, every value of a registered type in the patch of {@code stack} by a detached copy, so
     * the stack shares nothing mutable with the stack it was copied from. Only for a stack the caller owns (a fresh
     * copy). Returns {@code stack}.
     * <p>
     * A meaning's own copy is set with {@link ItemStack#set}. A meaning without one gets the copy a save and a load
     * make: the value encoded by the type's own codec into a one-entry patch, decoded and applied
     * ({@link ItemStack#applyComponents(DataComponentPatch)}), as loading a saved stack applies it. That is the
     * symmetry wand's: in a development run NeoForge checks every value {@code set} on a stack
     * ({@code CommonHooks#validateComponent}) by resolving its class's public methods, and on a dedicated server that
     * fails for Create's mirrors, whose {@code applyModelTransform} names the client's {@code PoseStack}; a decoded
     * patch is not checked, so the wand's copy takes the same path in every run. A copy that cannot be made, or that
     * would not mean the same, leaves the value shared, with one warning.
     */
    public static ItemStack detach(ItemStack stack) {
        if (stack.isComponentsPatchEmpty())
            return stack;
        for (Meaning<?> meaning : MEANINGS)
            detach(stack, meaning);
        return stack;
    }

    private static <T> void detach(ItemStack stack, Meaning<T> meaning) {
        if (!stack.has(meaning.type()))
            return;
        Optional<? extends T> value = stack.getComponentsPatch().get(meaning.type());
        if (value == null || value.isEmpty())
            return;
        if (meaning.detach() != null) {
            stack.set(meaning.type(), meaning.detach().apply(value.get()));
            return;
        }
        DataComponentPatch copy = decodedCopy(meaning, value.get());
        if (copy != null)
            stack.applyComponents(copy);
    }

    /**
     * {@code value} as a one-entry patch that the type's own codec encoded and decoded through {@link NbtOps} (no
     * registries), or {@code null}, with one warning, when that fails or the decoded value does not mean the same.
     */
    private static <T> @Nullable DataComponentPatch decodedCopy(Meaning<T> meaning, T value) {
        RuntimeException failure = null;
        try {
            Codec<T> codec = meaning.type().codec();
            ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(meaning.type());
            if (codec != null && id != null) {
                CompoundTag entry = new CompoundTag();
                entry.put(id.toString(), codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow());
                DataComponentPatch patch = DataComponentPatch.CODEC.parse(NbtOps.INSTANCE, entry).getOrThrow();
                Optional<? extends T> copy = patch.get(meaning.type());
                if (copy != null && copy.isPresent() && copy.get() != value
                        && meaning.meaningOf(copy.get()).equals(meaning.meaningOf(value)))
                    return patch;
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        if (!decodeWarned) {
            decodeWarned = true;
            LOGGER.warn("Cannot copy a {} value through its codec; the item key shares it", meaning.type(), failure);
        }
        return null;
    }

    /**
     * {@code patch} in {@code DataComponentPatch#toString}'s format ({@code {type=>value, !type}}), with two
     * differences that make it depend on the values alone:
     * <ul>
     * <li>the entries are sorted by the component type's registered name, not in the order the components were added,
     * which differs between equal stacks (a toolbox's loot drop sets its uuid first, the same toolbox loaded from a
     * save its inventory);</li>
     * <li>a registered type's value is written as its meaning, here and in every stack a meaning holds: a toolbox
     * inventory's own {@code toString} prints a hash built from identity hashes and a symmetry mirror's one that mixes
     * in an enum constant's identity hash, both of which change with every start, and a sand paper record's leaves out
     * the polished stack's components.</li>
     * </ul>
     * Every other value is written by its own {@code toString}.
     */
    public static String patchText(DataComponentPatch patch) {
        // The types alone are copied: an entry set may hand out one reused entry object.
        List<DataComponentType<?>> types = new ArrayList<>(patch.size());
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet())
            types.add(entry.getKey());
        types.sort(BY_TYPE_NAME);
        StringBuilder text = new StringBuilder("{");
        boolean first = true;
        for (DataComponentType<?> type : types) {
            if (!first)
                text.append(", ");
            first = false;
            Optional<?> value = patch.get(type);
            if (value == null || value.isEmpty()) {
                text.append('!').append(type);
                continue;
            }
            Meaning<?> meaning = of(type);
            text.append(type).append("=>").append(meaning != null ? meaning.meaningOf(value.get()) : value.get());
        }
        return text.append('}').toString();
    }

    private static String itemId(ItemStack stack) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    // --- the registered meanings ---------------------------------------------------------------------------------

    private static List<Meaning<?>> register() {
        List<Meaning<?>> meanings = new ArrayList<>();
        Meaning<ToolboxInventory> toolbox = ToolboxAccess.meaning();
        if (toolbox != null)
            meanings.add(toolbox);
        meanings.add(new Meaning<>(AllDataComponents.SAND_PAPER_POLISHING,
                polishing -> List.of(new ExactStack(polished(polishing))),
                polishing -> new SandPaperItemComponent(polished(polishing).copy())));
        meanings.add(new Meaning<>(AllDataComponents.SYMMETRY_WAND, ComponentMeanings::mirrorMeaning, null));
        return List.copyOf(meanings);
    }

    private static ItemStack polished(SandPaperItemComponent polishing) {
        ItemStack item = polishing.item();
        return item == null ? ItemStack.EMPTY : item;
    }

    /**
     * What {@code SymmetryMirror#equals} compares — the orientation index, enable, the position and the orientation —
     * plus the type name, which it leaves out: a {@code TriplePlaneMirror} keeps index 0 and answers
     * {@code CrossPlaneMirror.Align.Y}, exactly as an orthogonal cross-plane mirror does. The position goes in as three
     * {@code Double}s, which compare as {@code Vec3#equals} does and are hashed by value; a {@code Vec3} would count by
     * its type alone.
     */
    private static List<?> mirrorMeaning(SymmetryMirror mirror) {
        Vec3 position = mirror.getPosition();
        return Arrays.asList(mirror.typeName(), mirror.getOrientationIndex(),
                position == null ? null : List.of(position.x, position.y, position.z), mirror.enable,
                mirror.getOrientation());
    }

    /**
     * Create's private toolbox members, resolved once: the {@code filters} list, the {@code limitedMode} flag and the
     * static {@code deserialize}, which {@code CODEC} and {@code STREAM_CODEC} decode through (and with them the
     * component's persistent {@code BACKWARDS_COMPAT_CODEC}, except its fallback for a toolbox saved before Create
     * 6.0.6).
     */
    private static final class ToolboxAccess {
        private final VarHandle filters;
        private final VarHandle limitedMode;
        private final MethodHandle deserialize;
        private volatile boolean warned;

        private ToolboxAccess(VarHandle filters, VarHandle limitedMode, MethodHandle deserialize) {
            this.filters = filters;
            this.limitedMode = limitedMode;
            this.deserialize = deserialize;
        }

        /** The toolbox meaning, or {@code null} (with one warning) when a member cannot be resolved. */
        static @Nullable Meaning<ToolboxInventory> meaning() {
            String member = "ToolboxInventory";
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ToolboxInventory.class,
                        MethodHandles.lookup());
                member = "ToolboxInventory#filters";
                VarHandle filters = lookup.findVarHandle(ToolboxInventory.class, "filters", List.class);
                member = "ToolboxInventory#limitedMode";
                VarHandle limitedMode = lookup.findVarHandle(ToolboxInventory.class, "limitedMode", boolean.class);
                member = "ToolboxInventory#deserialize";
                MethodHandle deserialize = lookup.findStatic(ToolboxInventory.class, "deserialize",
                        MethodType.methodType(ToolboxInventory.class, ItemSlots.class, List.class));
                ToolboxAccess access = new ToolboxAccess(filters, limitedMode, deserialize);
                return new Meaning<>(AllDataComponents.TOOLBOX_INVENTORY, access::meaningOf, access::detach);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("Cannot read Create's {}; toolboxes in the warehouse compare as Create compares them, so a "
                        + "toolbox with a filter cannot be requested", member, e);
                return null;
            }
        }

        private List<?> meaningOf(ToolboxInventory inventory) {
            List<ExactStack> contents = new ArrayList<>(inventory.getSlots());
            for (int slot = 0; slot < inventory.getSlots(); slot++)
                contents.add(new ExactStack(inventory.getStackInSlot(slot)));
            List<?> raw = (List<?>) filters.get(inventory);
            List<SameKind> kinds = new ArrayList<>(raw == null ? 0 : raw.size());
            if (raw != null) {
                for (Object filter : raw)
                    kinds.add(new SameKind(filter instanceof ItemStack stack ? stack : ItemStack.EMPTY));
            }
            return List.of(contents, kinds, (boolean) limitedMode.get(inventory));
        }

        private ToolboxInventory detach(ToolboxInventory inventory) {
            try {
                List<?> raw = (List<?>) filters.get(inventory);
                if (raw == null)
                    return inventory; // never the case in Create 6.0.10: every constructor and decoder sets the list
                List<ItemStack> copies = new ArrayList<>(raw.size());
                for (Object filter : raw)
                    copies.add(filter instanceof ItemStack stack ? stack.copy() : ItemStack.EMPTY);
                ItemSlots slots = ItemSlots.fromHandler(inventory);
                // invokeExact: the call site's types are (ItemSlots, List)ToolboxInventory, the method's own.
                ToolboxInventory copy = (ToolboxInventory) deserialize.invokeExact(slots, copies);
                limitedMode.set(copy, (boolean) limitedMode.get(inventory));
                return copy;
            } catch (Throwable e) {
                if (!warned) {
                    warned = true;
                    LOGGER.warn("Cannot copy a Create toolbox inventory; the item key shares it", e);
                }
                return inventory;
            }
        }
    }
}
