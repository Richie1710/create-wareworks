package dev.wareworks.content.requester;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.requester.RequesterLimits;
import dev.wareworks.core.requester.RequesterOrder;
import dev.wareworks.core.requester.RequesterStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What the goggle tooltip of a Schematicannon Requester shows, as synced to clients (issue #45, ADR-080 §11).
 * <p>
 * Built on the server only while somebody looks at the block through goggles ({@code GoggleObservers}), and written
 * under {@code GoggleSummary} in client packets only. Items travel as their registry id and nothing else — never a
 * stack, never a component — so a row says which item, not which variant, as every Wareworks goggle line does. At most
 * {@value RequesterLimits#MAX_ORDERS} rows; records compare by value, so the server syncs only visible changes; reading
 * never throws.
 *
 * @param status          what the requester does or waits for
 * @param cannon          what the cannon in front does
 * @param missing         the item the cannon misses, {@link Items#AIR} for none
 * @param missingCount    how many of it the cannon reports
 * @param cannonWord      Create's status word of a {@link CannonView.Phase#BLOCKED} cannon, empty otherwise
 * @param subject         the item a refusal is about (out of stock, reserved), {@link Items#AIR} for none
 * @param distance        how far the terminal is, for {@link RequesterStatus#LINK_OUT_OF_RANGE}
 * @param range           the remote's range, for {@link RequesterStatus#LINK_OUT_OF_RANGE}
 * @param ownerName       the name of the player who put the remote in, empty without a remote
 * @param rows            the open orders, oldest first
 * @param gaveUp          the order the dead-man switch gave up last, while it is still shown, else {@code null}
 * @param timeouts        how many orders the dead-man switch gave up since the remote went in
 * @param planRefusal     why the chain that would make the missing item cannot be planned, for
 *                        {@link RequesterStatus#CANNOT_MAKE} (ADR-081), else {@code null}
 * @param about           the item {@code planRefusal} is about (an ingredient, often), or for
 *                        {@link RequesterStatus#MAKING_STOPPED} the item the safety stop halted (the product or an
 *                        ingredient of its chain); {@link Items#AIR} for none
 */
public record RequesterSummary(RequesterStatus status, CannonView.Phase cannon, Item missing, int missingCount,
                               String cannonWord, Item subject, int distance, int range, String ownerName,
                               List<Row> rows, GaveUp gaveUp, int timeouts, @Nullable PlanRefusal planRefusal,
                               Item about) {
    public static final RequesterSummary NONE = new RequesterSummary(RequesterStatus.NO_REMOTE,
            CannonView.Phase.NO_CANNON, Items.AIR, 0, "", Items.AIR, 0, 0, "", List.of(), null, 0, null, Items.AIR);

    private static final String STATUS = "Status";
    private static final String CANNON = "Cannon";
    private static final String MISSING = "Missing";
    private static final String MISSING_COUNT = "MissingCount";
    private static final String CANNON_WORD = "CannonWord";
    private static final String SUBJECT = "Subject";
    private static final String DISTANCE = "Distance";
    private static final String RANGE = "Range";
    private static final String OWNER = "Owner";
    private static final String ROWS = "Rows";
    private static final String GAVE_UP = "GaveUp";
    private static final String TIMEOUTS = "Timeouts";
    private static final String ITEM = "Item";
    private static final String PHASE = "Phase";
    private static final String SHARE = "Share";
    private static final String DELIVERED = "Delivered";
    private static final String AGE = "Age";
    private static final String GRACE_LEFT = "GraceLeft";
    private static final String AMOUNT = "Amount";
    private static final String MADE = "Made";
    private static final String PLAN_REFUSAL = "PlanRefusal";
    private static final String ABOUT = "About";

    /**
     * One open order as the goggles show it.
     *
     * @param item       the item ordered
     * @param phase      where the order stands
     * @param share      its own share of the request
     * @param delivered  how much of the share has arrived
     * @param liveTicks  how long it has been running while the warehouse could serve it
     * @param graceLeft  seconds of the grace that are left ({@link RequesterOrder.Phase#GRACE} only)
     * @param made       how much of the share production still makes ({@link RequesterOrder#producing()}, ADR-081)
     */
    public record Row(Item item, RequesterOrder.Phase phase, int share, int delivered, int liveTicks, int graceLeft,
                      int made) {
        public Row {
            item = item == null ? Items.AIR : item;
            phase = phase == null ? RequesterOrder.Phase.RUNNING : phase;
            share = Math.max(0, share);
            delivered = Math.max(0, Math.min(delivered, share));
            liveTicks = Math.max(0, liveTicks);
            graceLeft = Math.max(0, graceLeft);
            made = Math.max(0, Math.min(made, share));
        }

        /** A row of which nothing is made. */
        public Row(Item item, RequesterOrder.Phase phase, int share, int delivered, int liveTicks, int graceLeft) {
            this(item, phase, share, delivered, liveTicks, graceLeft, 0);
        }
    }

    /**
     * The order the dead-man switch gave up last.
     *
     * @param item      the item
     * @param amount    the share that was still outstanding and was given back
     * @param liveTicks how long it had been running
     */
    public record GaveUp(Item item, int amount, int liveTicks) {
        public GaveUp {
            item = item == null ? Items.AIR : item;
            amount = Math.max(0, amount);
            liveTicks = Math.max(0, liveTicks);
        }
    }

    public RequesterSummary {
        status = status == null ? RequesterStatus.NO_REMOTE : status;
        cannon = cannon == null ? CannonView.Phase.NO_CANNON : cannon;
        missing = missing == null ? Items.AIR : missing;
        missingCount = Math.max(0, missingCount);
        cannonWord = cannonWord == null ? "" : cannonWord;
        subject = subject == null ? Items.AIR : subject;
        distance = Math.max(0, distance);
        range = Math.max(0, range);
        ownerName = ownerName == null ? "" : ownerName;
        rows = List.copyOf(rows == null ? List.of() : rows.subList(0, Math.min(rows.size(), RequesterLimits.MAX_ORDERS)));
        timeouts = Math.max(0, timeouts);
        about = about == null ? Items.AIR : about;
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putString(STATUS, status.serializedName());
        tag.putString(CANNON, cannon.serializedName());
        tag.putString(MISSING, id(missing));
        tag.putInt(MISSING_COUNT, missingCount);
        tag.putString(CANNON_WORD, cannonWord);
        tag.putString(SUBJECT, id(subject));
        tag.putInt(DISTANCE, distance);
        tag.putInt(RANGE, range);
        tag.putString(OWNER, ownerName);
        ListTag list = new ListTag();
        for (Row row : rows) {
            CompoundTag rowTag = new CompoundTag();
            rowTag.putString(ITEM, id(row.item()));
            rowTag.putString(PHASE, row.phase().serializedName());
            rowTag.putInt(SHARE, row.share());
            rowTag.putInt(DELIVERED, row.delivered());
            rowTag.putInt(AGE, row.liveTicks());
            rowTag.putInt(GRACE_LEFT, row.graceLeft());
            if (row.made() > 0)
                rowTag.putInt(MADE, row.made());
            list.add(rowTag);
        }
        tag.put(ROWS, list);
        if (gaveUp != null) {
            CompoundTag gaveUpTag = new CompoundTag();
            gaveUpTag.putString(ITEM, id(gaveUp.item()));
            gaveUpTag.putInt(AMOUNT, gaveUp.amount());
            gaveUpTag.putInt(AGE, gaveUp.liveTicks());
            tag.put(GAVE_UP, gaveUpTag);
        }
        tag.putInt(TIMEOUTS, timeouts);
        tag.putString(PLAN_REFUSAL, planRefusal == null ? "" : planRefusal.name());
        tag.putString(ABOUT, id(about));
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static RequesterSummary read(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        List<Row> rows = new ArrayList<>();
        ListTag list = tag.getList(ROWS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && rows.size() < RequesterLimits.MAX_ORDERS; i++) {
            CompoundTag row = list.getCompound(i);
            rows.add(new Row(item(row.getString(ITEM)),
                    RequesterOrder.Phase.byName(row.getString(PHASE)).orElse(RequesterOrder.Phase.RUNNING),
                    row.getInt(SHARE), row.getInt(DELIVERED), row.getInt(AGE), row.getInt(GRACE_LEFT),
                    row.getInt(MADE)));
        }
        GaveUp gaveUp = null;
        if (tag.contains(GAVE_UP, Tag.TAG_COMPOUND)) {
            CompoundTag gaveUpTag = tag.getCompound(GAVE_UP);
            gaveUp = new GaveUp(item(gaveUpTag.getString(ITEM)), gaveUpTag.getInt(AMOUNT), gaveUpTag.getInt(AGE));
        }
        return new RequesterSummary(RequesterStatus.byName(tag.getString(STATUS)).orElse(RequesterStatus.NO_REMOTE),
                CannonView.Phase.byName(tag.getString(CANNON)), item(tag.getString(MISSING)),
                tag.getInt(MISSING_COUNT), tag.getString(CANNON_WORD), item(tag.getString(SUBJECT)),
                tag.getInt(DISTANCE), tag.getInt(RANGE), tag.getString(OWNER), rows, gaveUp, tag.getInt(TIMEOUTS),
                planRefusal(tag.getString(PLAN_REFUSAL)), item(tag.getString(ABOUT)));
    }

    /** The plan refusal of a saved name, {@code null} for none or an unknown name. */
    @Nullable
    private static PlanRefusal planRefusal(String name) {
        if (name == null || name.isEmpty())
            return null;
        for (PlanRefusal refusal : PlanRefusal.values()) {
            if (refusal.name().equals(name))
                return refusal;
        }
        return null;
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? Items.AIR : BuiltInRegistries.ITEM.get(location);
    }
}
