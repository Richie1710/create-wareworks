package dev.wareworks.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Right-clicking a block with a renamed item, as the aisle-naming gesture does (M25, issue #15), and reading back what
 * the player was told.
 * <p>
 * The player is a {@link Teller}: a mock player like {@code GameTestHelper#makeMockPlayer}'s that <b>keeps</b> the
 * action bar lines sent to it. The base {@code Player#displayClientMessage} is an empty method — only
 * {@code ServerPlayer} overrides it — so a test with the helper's own mock player could not tell "the aisle was named
 * and the player was told" from "the aisle was named silently", and the four sentences of this gesture are half of
 * what it does.
 * <p>
 * Messages are compared by their <b>translation key</b>, never by their text: the key is what the code chose, while
 * the text is whatever language the test server happens to have loaded, and a raw-key regression would still read as a
 * plausible string.
 */
final class NamingClick {
    private NamingClick() {
    }

    /** One item of {@code item} with {@code name} as its custom name, exactly as an anvil leaves it. */
    static ItemStack renamed(Item item, String name) {
        return renamed(item, name, 1);
    }

    /** {@code count} items of {@code item} with {@code name} as their custom name. */
    static ItemStack renamed(Item item, String name, int count) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    /** A name tag nobody has written on: the item that clears a name. */
    static ItemStack blankNameTag() {
        return new ItemStack(Items.NAME_TAG);
    }

    /** A mock creative player that keeps what it is told. */
    static Teller player(GameTestHelper helper) {
        return new Teller(helper.getLevel());
    }

    /**
     * Uses {@code stack} on a face of the test-relative block at {@code pos}, like a player who is not sneaking and
     * does not hit the value box in the middle of the face.
     * <p>
     * The hit point is deliberately off centre, in the corner of the face, which is where a player has to click for
     * the gesture to reach the block at all: Create cancels a right-click that hits the value-box sphere before the
     * block state is asked. {@code GameTestHelper#useBlock} calls {@code useItemOn} itself, so the value box is not
     * involved either way — the offset is here so that the test describes the real gesture.
     */
    static void use(GameTestHelper helper, Teller player, BlockPos pos, Direction face, ItemStack stack) {
        player.messages.clear();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos absolute = helper.absolutePos(pos);
        Vec3 hit = Vec3.atCenterOf(absolute)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5)
                .add(face.getAxis() == Direction.Axis.X ? 0 : 0.3, face.getAxis() == Direction.Axis.Y ? 0 : -0.3,
                        face.getAxis() == Direction.Axis.Z ? 0 : 0.3);
        helper.useBlock(pos, player, new BlockHitResult(hit, face, absolute, false));
    }

    /**
     * Fails unless the player was told exactly these messages, in this order, by their relative lang keys
     * ({@code WareworksLang#key}).
     */
    static void assertTold(GameTestHelper helper, Teller player, String what, String... relativeKeys) {
        List<String> expected = new ArrayList<>();
        for (String key : relativeKeys)
            expected.add("wareworks." + key);
        helper.assertValueEqual(player.keys(), List.copyOf(expected), "action bar after " + what);
    }

    /** A mock player that records the action bar lines it is sent. */
    static final class Teller extends Player {
        private final List<Component> messages = new ArrayList<>();

        private Teller(Level level) {
            super(level, BlockPos.ZERO, 0f, new GameProfile(UUID.randomUUID(), "wareworks-naming-test"));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return true;
        }

        @Override
        public boolean isLocalPlayer() {
            return true;
        }

        @Override
        public void displayClientMessage(Component message, boolean actionBar) {
            messages.add(message);
        }

        /** The translation keys of what this player was told since the last click, in order. */
        List<String> keys() {
            List<String> keys = new ArrayList<>(messages.size());
            for (Component message : messages)
                keys.add(message.getContents() instanceof TranslatableContents translatable
                        ? translatable.getKey() : "<literal> " + message.getString());
            return keys;
        }

        /** The arguments of the one message at {@code index}, as plain strings. */
        List<String> argsOf(int index) {
            if (index >= messages.size()
                    || !(messages.get(index).getContents() instanceof TranslatableContents translatable))
                return List.of();
            List<String> args = new ArrayList<>();
            for (Object arg : translatable.getArgs())
                args.add(arg instanceof Component component ? component.getString() : String.valueOf(arg));
            return args;
        }
    }
}
