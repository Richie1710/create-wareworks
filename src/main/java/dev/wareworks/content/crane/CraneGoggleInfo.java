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
 * phase, pause reason, the current job, the held items by item <b>type</b> and the aisle letters of the warehouse the
 * linked controller runs.
 * <p>
 * <b>One letter per branch</b> (M21, ADR-033). A warehouse is a rail network of straight branches, each with its own
 * aisle letter, and a crane drives all of them — so a job "from the input on aisle A to a rack on aisle B" has to say
 * so. Before M21 there was a single letter here, which was right because there was a single aisle; with a warehouse
 * that bends it would name every rack of every aisle with the letter of the one at the dock.
 * <p>
 * The synced size is bounded: a few enum names, at most {@value #MAX_HELD_ENTRIES} item ids, one job summary and at
 * most {@link StorageAddress#AISLE_COUNT} letters (a warehouse can never have more branches than the address format
 * has letters); no item components ever leave the server. Reading never throws.
 *
 * @param phase        state machine phase
 * @param pauseReason  why the crane is paused, {@link CranePauseReason#NONE} if it is not
 * @param job          the current job
 * @param held         held item types with amounts, at most {@value #MAX_HELD_ENTRIES}
 * @param aisleLetters the aisle letter of each branch of the linked warehouse, branch {@value
 *                     RackPosition#FIRST_BRANCH} first; {@value #NO_LETTER} for a branch without one, and an empty
 *                     string without a controller (for addresses)
 */
public record CraneGoggleInfo(CranePhase phase, CranePauseReason pauseReason, Optional<CraneJobSummary> job,
                              List<KeyCount<Item>> held, String aisleLetters) {
    /** Most held entries synced. */
    public static final int MAX_HELD_ENTRIES = 4;
    /** Stands for a branch that carries no aisle letter, so the letters of the branches behind it stay readable. */
    public static final char NO_LETTER = '?';
    public static final CraneGoggleInfo NONE = new CraneGoggleInfo(CranePhase.IDLE, CranePauseReason.NONE,
            Optional.empty(), List.of(), "");

    private static final String PHASE = "Phase";
    private static final String PAUSE = "Pause";
    private static final String JOB = "Job";
    private static final String HELD = "Held";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";
    /**
     * Save and packet key of the aisle letters. Up to 0.5.0 this held the single letter of the single aisle, which is
     * exactly what the first character means now, so a crane saved then reads back with the same address.
     */
    private static final String LETTER = "Letter";

    public CraneGoggleInfo {
        if (phase == null)
            phase = CranePhase.IDLE;
        if (pauseReason == null)
            pauseReason = CranePauseReason.NONE;
        if (job == null)
            job = Optional.empty();
        held = held == null ? List.of() : List.copyOf(held.subList(0, Math.min(held.size(), MAX_HELD_ENTRIES)));
        aisleLetters = sanitizeLetters(aisleLetters);
    }

    /**
     * The letters as they are synced: at most {@link StorageAddress#AISLE_COUNT} of them, anything that is not an aisle
     * letter as {@value #NO_LETTER}, and no trailing placeholders — so a warehouse of one lettered aisle syncs the one
     * character it always did, and a broken packet can never make an address out of a stray byte.
     */
    private static String sanitizeLetters(String letters) {
        if (letters == null || letters.isEmpty())
            return "";
        int length = Math.min(letters.length(), StorageAddress.AISLE_COUNT);
        while (length > 0 && !StorageAddress.isValidAisle(letters.charAt(length - 1)))
            length--;
        StringBuilder sanitized = new StringBuilder(length);
        for (int branch = 0; branch < length; branch++) {
            char letter = letters.charAt(branch);
            sanitized.append(StorageAddress.isValidAisle(letter) ? letter : NO_LETTER);
        }
        return sanitized.toString();
    }

    /** The aisle letter of one branch, empty when that branch has none (or the warehouse has no such branch). */
    public Optional<Character> letterOf(int branch) {
        if (branch < 0 || branch >= aisleLetters.length())
            return Optional.empty();
        char letter = aisleLetters.charAt(branch);
        return StorageAddress.isValidAisle(letter) ? Optional.of(letter) : Optional.empty();
    }

    /** The aisle letter of the branch at the dock — the whole warehouse of every build that never bends. */
    public Optional<Character> aisleLetter() {
        return letterOf(RackPosition.FIRST_BRANCH);
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
     * The address text of a rack position: {@code A-03-07R} with the letter of the branch the position is <b>on</b>,
     * otherwise the letter-less {@code 03-07R}.
     */
    public String address(RackPosition rack) {
        Optional<Character> letter = letterOf(rack.branch());
        if (letter.isPresent() && rack.y() < StorageAddress.MAX_LEVEL)
            return StorageAddress.of(letter.get(), rack).format();
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
            WareworksLang.craneJob(summary.type(), summary.targetKind(), summary.item(), summary.amount())
                    .forGoggles(tooltip, indent);
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
        if (!aisleLetters.isEmpty())
            tag.putString(LETTER, aisleLetters);
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
        return new CraneGoggleInfo(CranePhase.byName(tag.getString(PHASE)).orElse(CranePhase.IDLE),
                CranePauseReason.byName(tag.getString(PAUSE)),
                tag.contains(JOB, Tag.TAG_COMPOUND) ? CraneJobSummary.read(tag.getCompound(JOB)) : Optional.empty(), held,
                tag.getString(LETTER));
    }
}
