package dev.wareworks.content.handtruck;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a loaded hand truck carries (issue #37, ADR-070): the value of the item component
 * {@code wareworks:hand_truck_load}.
 * <p>
 * Three things: the block state the container had when it was taken, its block entity type, and the block entity's
 * own save data, {@code BlockEntity#saveWithoutMetadata} — exactly what chunk saving writes for it minus the id and
 * the position, so the items, a custom name, a lock, an unopened loot table and every NeoForge attachment travel, and
 * {@code BlockEntity#loadWithComponents} puts it back the way chunk loading would.
 * <p>
 * <b>Our own component type, never vanilla's {@code BLOCK_ENTITY_DATA} or {@code CONTAINER}.</b> Those are read by
 * vanilla code — block item placement and {@code BaseContainerBlockEntity#applyImplicitComponents} — so a stack
 * carrying them would be a placeable full chest. Only Wareworks reads this one.
 * <p>
 * <b>Immutable.</b> The compound is never changed after construction; everything that hands it to a block entity
 * passes {@link #dataCopy()}. Item stacks share component values when they are copied, and container menus compare
 * every slot every tick ({@code ItemStack#matches}), so {@link #equals} answers an unchanged stack by identity first
 * and the hash is computed once.
 * <p>
 * Synced to clients in full: a creative client sends whole stacks back to the server, so a client copy without the
 * data would empty the truck the first time a creative player moved it.
 */
public final class HandTruckLoad {
    public static final Codec<HandTruckLoad> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockState.CODEC.fieldOf("state").forGetter(HandTruckLoad::state),
            ResourceLocation.CODEC.fieldOf("block_entity").forGetter(HandTruckLoad::blockEntityType),
            CompoundTag.CODEC.fieldOf("data").forGetter(load -> load.data)).apply(instance, HandTruckLoad::new));

    public static final StreamCodec<ByteBuf, HandTruckLoad> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), HandTruckLoad::state,
            ResourceLocation.STREAM_CODEC, HandTruckLoad::blockEntityType,
            ByteBufCodecs.COMPOUND_TAG, load -> load.data,
            HandTruckLoad::new);

    /** The list a vanilla container writes its items into ({@code ContainerHelper}). */
    static final String ITEMS_TAG = "Items";
    /** Where a randomizable container keeps a loot table it has not rolled yet. */
    static final String LOOT_TABLE_TAG = "LootTable";
    /** Where a nameable block entity keeps its custom name, as JSON text. */
    static final String CUSTOM_NAME_TAG = "CustomName";

    private final BlockState state;
    private final ResourceLocation blockEntityType;
    private final CompoundTag data;
    private int hash;
    private boolean hashed;
    /** The parsed custom name; {@code null} until asked, {@link #NO_NAME} when the data has none. */
    @Nullable
    private Component customName;
    private static final Component NO_NAME = Component.empty();

    public HandTruckLoad(BlockState state, ResourceLocation blockEntityType, CompoundTag data) {
        this.state = Objects.requireNonNull(state, "state");
        this.blockEntityType = Objects.requireNonNull(blockEntityType, "blockEntityType");
        this.data = Objects.requireNonNull(data, "data");
    }

    /** The block state the container had when it was taken. */
    public BlockState state() {
        return state;
    }

    /** The block entity type it had, checked again when it is set down. */
    public ResourceLocation blockEntityType() {
        return blockEntityType;
    }

    /** A copy of the carried save data, safe to hand to a block entity. */
    public CompoundTag dataCopy() {
        return data.copy();
    }

    /** The size of the carried save data, as NBT counts it ({@code NbtAccounter}). */
    public int sizeInBytes() {
        return data.sizeInBytes();
    }

    /** How many stacks the container holds, read from its item list without decoding a single item. */
    public int stackCount() {
        return data.getList(ITEMS_TAG, Tag.TAG_COMPOUND).size();
    }

    /** Whether the container still has a loot table it has never rolled (a dungeon chest nobody opened). */
    public boolean hasUnrolledLoot() {
        return data.contains(LOOT_TABLE_TAG, Tag.TAG_STRING);
    }

    /**
     * The container's custom name, or {@code null}. Parsed once, without registries: a name is text, and a name that
     * needs registries to read (an item hover event) falls back to the block's own name.
     */
    @Nullable
    public Component customName() {
        Component name = customName;
        if (name == null) {
            name = NO_NAME;
            if (data.contains(CUSTOM_NAME_TAG, Tag.TAG_STRING)) {
                Component parsed = BlockEntity.parseCustomNameSafe(data.getString(CUSTOM_NAME_TAG), RegistryAccess.EMPTY);
                if (parsed != null)
                    name = parsed;
            }
            customName = name;
        }
        return name == NO_NAME ? null : name;
    }

    /** The name a player reads for the carried container: its custom name, or else the block's. */
    public Component displayName() {
        Component name = customName();
        return name != null ? name : state.getBlock().getName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof HandTruckLoad other))
            return false;
        return state == other.state && blockEntityType.equals(other.blockEntityType) && data.equals(other.data);
    }

    @Override
    public int hashCode() {
        if (!hashed) {
            hash = Objects.hash(state, blockEntityType, data);
            hashed = true;
        }
        return hash;
    }

    @Override
    public String toString() {
        return "HandTruckLoad[" + state + ", " + blockEntityType + ", " + data.sizeInBytes() + " bytes]";
    }
}
