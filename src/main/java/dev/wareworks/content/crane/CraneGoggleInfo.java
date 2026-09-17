package dev.wareworks.content.crane;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * What the goggle tooltip of a stacker crane shows and what M4 rendering needs besides the pose, as synced to clients:
 * phase, pause reason, the current job, the held items by item <b>type</b> and the aisle letter of the linked controller.
 * <p>
 * The synced size is bounded: a few enum names, at most {@value #MAX_HELD_ENTRIES} item ids and one job summary; no item
 * components ever leave the server. Reading never throws.
 *
 * @param phase       state machine phase
 * @param pauseReason why the crane is paused, {@link CranePauseReason#NONE} if it is not
 * @param job         the current job
 * @param held        held item types with amounts, at most {@value #MAX_HELD_ENTRIES}
 * @param aisleLetter the linked controller's aisle letter (for addresses)
 */
public record CraneGoggleInfo(CranePhase phase, CranePauseReason pauseReason, Optional<CraneJobSummary> job,
                              List<KeyCount<Item>> held, Optional<Character> aisleLetter) {
    /** Most held entries synced. */
    public static final int MAX_HELD_ENTRIES = 4;
    public static final CraneGoggleInfo NONE = new CraneGoggleInfo(CranePhase.IDLE, CranePauseReason.NONE,
            Optional.empty(), List.of(), Optional.empty());

    private static final String PHASE = "Phase";
    private static final String PAUSE = "Pause";
    private static final String JOB = "Job";
    private static final String HELD = "Held";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";
    private static final String LETTER = "Letter";

    public CraneGoggleInfo {
        if (phase == null)
            phase = CranePhase.IDLE;
        if (pauseReason == null)
            pauseReason = CranePauseReason.NONE;
        if (job == null)
            job = Optional.empty();
        held = held == null ? List.of() : List.copyOf(held.subList(0, Math.min(held.size(), MAX_HELD_ENTRIES)));
        if (aisleLetter == null || aisleLetter.filter(StorageAddress::isValidAisle).isEmpty())
            aisleLetter = Optional.empty();
    }

    /** Held items by item type (in pick order), capped at {@value #MAX_HELD_ENTRIES} entries. */
    public static List<KeyCount<Item>> heldByType(HeldItems items) {
        List<KeyCount<Item>> result = new ArrayList<>();
        for (HeldItems.Entry entry : items.entries()) {
            if (result.size() >= MAX_HELD_ENTRIES)
                break;
            result.add(new KeyCount<>(entry.key().getItem(), entry.count()));
        }
        return result;
    }

    /** Total held amount over the synced entries. */
    public long heldCount() {
        long total = 0;
        for (KeyCount<Item> entry : held)
            total += entry.count();
        return total;
    }

    /**
     * The address text of a rack position: {@code A-03-07R} with the controller's letter, otherwise the letter-less
     * {@code 03-07R}.
     */
    public String address(RackPosition rack) {
        if (aisleLetter.isPresent() && rack.y() < StorageAddress.MAX_LEVEL)
            return StorageAddress.of(aisleLetter.get(), rack).format();
        return String.format(Locale.ROOT, "%02d-%02d%c", rack.y() + 1, rack.x(), rack.side().letter());
    }

    /**
     * Adds the crane lines for goggles (client only): status, pause reason, current job with its route and, if
     * {@code withHeld}, the held items or "Grabber empty".
     */
    public void addGoggleLines(List<Component> tooltip, int indent, boolean withHeld) {
        WareworksLang.craneStatus(phase).forGoggles(tooltip, indent);
        if (pauseReason != CranePauseReason.NONE)
            WareworksLang.cranePaused(pauseReason.langKey()).forGoggles(tooltip, indent);
        job.ifPresent(summary -> {
            WareworksLang.craneJob(summary.type(), summary.item(), summary.amount()).forGoggles(tooltip, indent);
            WareworksLang.craneRoute(address(summary.source()), address(summary.target())).forGoggles(tooltip,
                    indent + 1);
        });
        if (!withHeld)
            return;
        if (held.isEmpty()) {
            WareworksLang.translate(WareworksLang.GOGGLES_CRANE_HEAD_EMPTY).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, indent);
            return;
        }
        WareworksLang.translate(WareworksLang.GOGGLES_CRANE_HOLDING).style(ChatFormatting.GRAY).forGoggles(tooltip,
                indent);
        for (KeyCount<Item> entry : held)
            WareworksLang.itemCount(entry.key(), entry.count()).forGoggles(tooltip, indent + 1);
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putString(PHASE, phase.name());
        tag.putString(PAUSE, pauseReason.name());
        job.ifPresent(summary -> {
            CompoundTag jobTag = new CompoundTag();
            summary.write(jobTag);
            tag.put(JOB, jobTag);
        });
        ListTag heldTag = new ListTag();
        for (KeyCount<Item> entry : held) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString(ITEM, ItemTypeSummaries.itemId(entry.key()));
            entryTag.putLong(COUNT, entry.count());
            heldTag.add(entryTag);
        }
        tag.put(HELD, heldTag);
        aisleLetter.ifPresent(letter -> tag.putString(LETTER, String.valueOf(letter)));
    }

    /** Reads a summary written by {@link #write}. Never throws; invalid data reads as empty values. */
    public static CraneGoggleInfo read(CompoundTag tag) {
        List<KeyCount<Item>> held = new ArrayList<>();
        ListTag heldTag = tag.getList(HELD, Tag.TAG_COMPOUND);
        for (int i = 0; i < heldTag.size() && held.size() < MAX_HELD_ENTRIES; i++) {
            CompoundTag entryTag = heldTag.getCompound(i);
            long count = entryTag.getLong(COUNT);
            if (count > 0)
                ItemTypeSummaries.itemById(entryTag.getString(ITEM)).ifPresent(item -> held.add(new KeyCount<>(item, count)));
        }
        String letter = tag.getString(LETTER);
        return new CraneGoggleInfo(CranePhase.byName(tag.getString(PHASE)).orElse(CranePhase.IDLE),
                CranePauseReason.byName(tag.getString(PAUSE)),
                tag.contains(JOB, Tag.TAG_COMPOUND) ? CraneJobSummary.read(tag.getCompound(JOB)) : Optional.empty(), held,
                letter.length() == 1 ? Optional.of(letter.charAt(0)) : Optional.empty());
    }
}
