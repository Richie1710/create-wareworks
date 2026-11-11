package dev.wareworks.content.selector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonParseException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * What the goggles of a Recipe Selector show (ADR-085, design §12): its status (with the names a refusal mentions), a
 * warning about the Packager it sits on, the station it serves, the machine and its filter now, and for the Ender tier
 * the last slip, the packages let through and the refusals per reason.
 * <p>
 * Built on the server when a player looks at the selector through goggles ({@code GoggleObservers}) and synced in the
 * selector's update tag at most once a second. Bounded: two detail names, item and block ids instead of stacks, and at
 * most one counter per status.
 *
 * @param ender         whether this is the Ender Recipe Selector
 * @param status        what the selector last did, or why it does nothing
 * @param detail        the names the status text takes (a refused box's station, the unpack target, ...), at most two
 * @param warning       {@link RecipeSelectorStatus#PORT_BYPASS} or {@link RecipeSelectorStatus#NO_VETO}, checked on
 *                      observation (Ender tier only)
 * @param station       the station it serves: the local tier's linked station, the Ender tier's latched origin
 * @param machine       where the machine it sets stands
 * @param kind          the machine's kind
 * @param filter        the machine's filter now: the item id, empty for none (only while the machine is loaded)
 * @param filterKnown   whether the machine was loaded, so {@code filter} says something
 * @param lastSlip      Ender: the result id of the last slip read
 * @param lastSlipFrom  Ender: the station address that slip came from
 * @param lastSlipTick  Ender: the game time it was read at
 * @param slipsRead     Ender: packages let through with a slip
 * @param withoutSlip   Ender: packages let through without one
 * @param refused       Ender: refusals per reason
 * @param setBacks      local: how often a hand change of the filter was set back
 */
public record RecipeSelectorGoggleSummary(boolean ender, RecipeSelectorStatus status, List<Component> detail,
                                          Optional<RecipeSelectorStatus> warning, Optional<String> station,
                                          Optional<BlockPos> machine, Optional<SelectorMachine.Kind> kind,
                                          Optional<ResourceLocation> filter, boolean filterKnown,
                                          Optional<ResourceLocation> lastSlip, String lastSlipFrom, long lastSlipTick,
                                          int slipsRead, int withoutSlip, Map<RecipeSelectorStatus, Integer> refused,
                                          int setBacks) {
    public static final RecipeSelectorGoggleSummary NONE = new RecipeSelectorGoggleSummary(false,
            RecipeSelectorStatus.NO_LINK, List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), false, Optional.empty(), "", 0L, 0, 0, Map.of(), 0);

    /** Names a status text takes at most. */
    public static final int MAX_DETAIL = 2;
    /** Longest station address or detail text kept. */
    public static final int MAX_TEXT = 64;

    private static final String ENDER = "Ender";
    private static final String STATUS = "Status";
    private static final String DETAIL = "Detail";
    private static final String WARNING = "Warning";
    private static final String STATION = "Station";
    private static final String MACHINE = "Machine";
    private static final String KIND = "Kind";
    private static final String FILTER = "Filter";
    private static final String FILTER_KNOWN = "FilterKnown";
    private static final String LAST_SLIP = "LastSlip";
    private static final String LAST_SLIP_FROM = "LastSlipFrom";
    private static final String LAST_SLIP_TICK = "LastSlipTick";
    private static final String SLIPS_READ = "SlipsRead";
    private static final String WITHOUT_SLIP = "WithoutSlip";
    private static final String REFUSED = "Refused";
    private static final String SET_BACKS = "SetBacks";

    public RecipeSelectorGoggleSummary {
        Objects.requireNonNull(status, "status");
        detail = List.copyOf(Objects.requireNonNull(detail, "detail"));
        if (detail.size() > MAX_DETAIL)
            detail = detail.subList(0, MAX_DETAIL);
        Objects.requireNonNull(warning, "warning");
        station = Objects.requireNonNull(station, "station").map(RecipeSelectorGoggleSummary::bounded);
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(filter, "filter");
        if (!filterKnown)
            filter = Optional.empty();
        Objects.requireNonNull(lastSlip, "lastSlip");
        lastSlipFrom = bounded(lastSlipFrom == null ? "" : lastSlipFrom);
        slipsRead = Math.max(0, slipsRead);
        withoutSlip = Math.max(0, withoutSlip);
        EnumMap<RecipeSelectorStatus, Integer> counts = new EnumMap<>(RecipeSelectorStatus.class);
        Objects.requireNonNull(refused, "refused").forEach((reason, count) -> {
            if (reason != null && count != null && count > 0)
                counts.put(reason, count);
        });
        refused = Map.copyOf(counts);
        setBacks = Math.max(0, setBacks);
    }

    private static String bounded(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    /**
     * The refusal reasons, the most frequent first (ties in the order of {@link RecipeSelectorStatus}), at most
     * {@code limit}; empty without a refusal. ({@code new EnumMap<>(map)} throws for an empty map that is no
     * {@code EnumMap}, which crashed the client's goggle overlay on every selector that never refused a box.)
     */
    public List<Map.Entry<RecipeSelectorStatus, Integer>> topRefusals(int limit) {
        List<Map.Entry<RecipeSelectorStatus, Integer>> entries = new ArrayList<>(refused.entrySet());
        entries.sort(Map.Entry.<RecipeSelectorStatus, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        return entries.size() <= limit ? entries : entries.subList(0, limit);
    }

    /** The status line as the goggles show it, with its names. */
    public Component statusText() {
        return dev.wareworks.util.WareworksLang.translateDirect(status.langKey(), detail.toArray());
    }

    /** Writes this summary into {@code tag}; never throws. */
    public void write(CompoundTag tag, HolderLookup.Provider registries) {
        if (ender)
            tag.putBoolean(ENDER, true);
        tag.putString(STATUS, status.name());
        if (!detail.isEmpty()) {
            ListTag list = new ListTag();
            for (Component component : detail)
                list.add(StringTag.valueOf(Component.Serializer.toJson(component, registries)));
            tag.put(DETAIL, list);
        }
        warning.ifPresent(found -> tag.putString(WARNING, found.name()));
        station.ifPresent(label -> tag.putString(STATION, label));
        machine.ifPresent(pos -> tag.putLong(MACHINE, pos.asLong()));
        kind.ifPresent(found -> tag.putString(KIND, found.name()));
        if (filterKnown) {
            tag.putBoolean(FILTER_KNOWN, true);
            filter.ifPresent(id -> tag.putString(FILTER, id.toString()));
        }
        lastSlip.ifPresent(id -> {
            tag.putString(LAST_SLIP, id.toString());
            tag.putString(LAST_SLIP_FROM, lastSlipFrom);
            tag.putLong(LAST_SLIP_TICK, lastSlipTick);
        });
        if (slipsRead > 0)
            tag.putInt(SLIPS_READ, slipsRead);
        if (withoutSlip > 0)
            tag.putInt(WITHOUT_SLIP, withoutSlip);
        if (!refused.isEmpty()) {
            CompoundTag counts = new CompoundTag();
            refused.forEach((reason, count) -> counts.putInt(reason.name(), count));
            tag.put(REFUSED, counts);
        }
        if (setBacks > 0)
            tag.putInt(SET_BACKS, setBacks);
    }

    /** Reads a summary written by {@link #write}; never throws, missing or invalid data reads as empty values. */
    public static RecipeSelectorGoggleSummary read(CompoundTag tag, HolderLookup.Provider registries) {
        List<Component> detail = new ArrayList<>(MAX_DETAIL);
        ListTag list = tag.getList(DETAIL, Tag.TAG_STRING);
        for (int i = 0; i < list.size() && detail.size() < MAX_DETAIL; i++) {
            try {
                Component component = Component.Serializer.fromJson(list.getString(i), registries);
                detail.add(component == null ? Component.empty() : component);
            } catch (JsonParseException | IllegalArgumentException | IllegalStateException e) {
                detail.add(Component.empty());
            }
        }
        Map<RecipeSelectorStatus, Integer> refused = new EnumMap<>(RecipeSelectorStatus.class);
        CompoundTag counts = tag.getCompound(REFUSED);
        for (String name : counts.getAllKeys()) {
            RecipeSelectorStatus reason = RecipeSelectorStatus.byName(name);
            if (reason.name().equals(name))
                refused.put(reason, counts.getInt(name));
        }
        Optional<RecipeSelectorStatus> warning = tag.contains(WARNING, Tag.TAG_STRING)
                ? Optional.of(RecipeSelectorStatus.byName(tag.getString(WARNING))) : Optional.empty();
        return new RecipeSelectorGoggleSummary(tag.getBoolean(ENDER), RecipeSelectorStatus.byName(tag.getString(STATUS)),
                detail, warning,
                tag.contains(STATION, Tag.TAG_STRING) ? Optional.of(tag.getString(STATION)) : Optional.empty(),
                tag.contains(MACHINE, Tag.TAG_LONG) ? Optional.of(BlockPos.of(tag.getLong(MACHINE))) : Optional.empty(),
                SelectorMachine.Kind.byName(tag.getString(KIND)), id(tag, FILTER), tag.getBoolean(FILTER_KNOWN),
                id(tag, LAST_SLIP), tag.getString(LAST_SLIP_FROM), tag.getLong(LAST_SLIP_TICK),
                tag.getInt(SLIPS_READ), tag.getInt(WITHOUT_SLIP), refused, tag.getInt(SET_BACKS));
    }

    private static Optional<ResourceLocation> id(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_STRING) ? Optional.ofNullable(ResourceLocation.tryParse(tag.getString(key)))
                : Optional.empty();
    }
}
