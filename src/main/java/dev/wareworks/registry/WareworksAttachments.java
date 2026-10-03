package dev.wareworks.registry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.TerminalPreferences;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * The mod's data attachment types: per-player state that belongs to the <b>player</b> rather than to any block
 * (M24, issue #17, ADR-037).
 * <p>
 * Today there is exactly one, the warehouse terminal's preferences. A data attachment was chosen over the two
 * alternatives because of where the data belongs: a {@code SavedData} of the level would have to key everything by
 * player UUID and clean up after players who never return, and a block entity field would make a player's own order
 * a property of whichever terminal they happened to stand at. NeoForge saves an entity's serializable attachments
 * inside that entity's NBT ({@code AttachmentHolder.ATTACHMENTS_NBT_KEY} in {@code Entity#saveWithoutId}, which is
 * what {@code PlayerDataStorage} writes to {@code <world>/playerdata/<uuid>.dat}), so the lifetime is exactly a
 * player's own and nothing has to be swept.
 *
 * <h2>Why these choices</h2>
 * <ul>
 * <li><b>An NBT serializer, not a {@link com.mojang.serialization.Codec}.</b> The codec-based builder throws on a
 * parse error ({@code AttachmentType.Builder#serialize(Codec)} calls {@code getOrThrow}), and one entry whose mod was
 * removed would fail the whole attachment. {@link TerminalPreferences#save} and {@link TerminalPreferences#load} never
 * throw and skip what they cannot read, which is the same contract the mod's block entities already have
 * ({@code ItemKey#saveTo}/{@code #loadFrom}).</li>
 * <li><b>{@code null} means "do not save".</b> A player who has never touched a terminal writes no tag at all, so an
 * ordinary world pays nothing for this.</li>
 * <li><b>{@code copyOnDeath}.</b> Entity attachments are not copied onto the respawned player by default. Losing the
 * order a player chose and what their terminal had learned would make dying a punishment this feature has no business
 * handing out.</li>
 * <li><b>An explicit {@code copyHandler}.</b> A type that is given none gets
 * {@code AttachmentType.defaultCopyHandler(serializer)}, which copies by <b>writing the attachment and reading it
 * back</b> — the only thing {@code AttachmentInternals.copyAttachments} ever calls. That would encode and decode every
 * item key through a codec on every respawn and on every return from the end, and would silently drop an entry whose
 * key does not encode ({@link TerminalPreferences#save} leaves such a key out on purpose, which is right for a save
 * and wrong for a copy). {@link TerminalPreferences#copy()} copies the numbers, so it is handed over explicitly
 * instead of being a method nothing calls (M24 review fix).</li>
 * <li><b>No {@code sync}.</b> The attachment is never synced by NeoForge: it would reach every client that can see the
 * player, and the numbers are nobody else's business. The one player who has a terminal screen open is sent what their
 * screen needs, in {@code TerminalUsagePayload}, and only while it is open.</li>
 * </ul>
 * Registered on the <b>mod</b> bus from the {@code Wareworks} constructor, like every other registry here.
 */
public final class WareworksAttachments {
    private static final DeferredRegister<AttachmentType<?>> REGISTER =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Wareworks.ID);

    /**
     * A player's warehouse terminal preferences: the order they chose and how often they have asked for each item type
     * ({@link TerminalPreferences}).
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<TerminalPreferences>> TERMINAL_PREFERENCES =
            // A zero-argument lambda, not TerminalPreferences::new: a constructor reference fits both builder(Supplier)
            // and builder(Function<IAttachmentHolder, …>) as far as overload resolution can see, and is ambiguous.
            REGISTER.register("terminal_preferences", () -> AttachmentType.builder(() -> new TerminalPreferences())
                    .serialize(new PreferencesSerializer())
                    .copyHandler((preferences, holder, provider) -> preferences.copy())
                    .copyOnDeath()
                    .build());

    private WareworksAttachments() {
    }

    /** Mod-bus registration, called from the {@code Wareworks} constructor. */
    public static void register(IEventBus modEventBus) {
        REGISTER.register(modEventBus);
    }

    /**
     * Reads and writes {@link TerminalPreferences} as the compound its own methods define, so the saved shape is
     * documented and tested in one place and nothing here can throw while a player is being saved or loaded.
     */
    private static final class PreferencesSerializer
            implements IAttachmentSerializer<CompoundTag, TerminalPreferences> {
        @Override
        public TerminalPreferences read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            TerminalPreferences preferences = new TerminalPreferences();
            preferences.load(provider, tag);
            return preferences;
        }

        @Nullable
        @Override
        public CompoundTag write(TerminalPreferences attachment, HolderLookup.Provider provider) {
            return attachment.save(provider);
        }
    }
}
