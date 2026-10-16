package dev.wareworks.core.inventory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * A hash of a value that is <b>contract-safe whatever class the value has</b>: two values that are {@code equals} get
 * the same hash, also when their own {@code hashCode} breaks that contract (ADR-064, issue #27). It reads only what the
 * value's {@code equals} is known to compare, by the first rule that applies:
 * <ol>
 * <li>a value nested deeper than {@value #MAX_DEPTH} levels: {@code 0};</li>
 * <li>a class a subclass knows ({@link #hashValue}, the hook): whatever it says, built from these rules;</li>
 * <li>{@code String}, the boxed primitives, {@code UUID} and every enum: its own {@code hashCode} (an enum's is
 * {@code Enum}'s final identity hash, and its equality is identity);</li>
 * <li>{@code Optional}, {@code List}, {@code Set} and {@code Map}: recursively, as their interfaces define equality — a
 * list in order, a set and a map as an unordered sum, so a {@code HashMap} and a {@code LinkedHashMap} filled in
 * different orders hash alike;</li>
 * <li>a class that keeps {@code Object#equals}, whose equality is identity: {@link System#identityHashCode};</li>
 * <li>anything else — every record, every class with an {@code equals} of its own, whose {@code hashCode} nothing
 * here can vouch for, and every class whose {@code equals} cannot be looked up on this side: {@value #OPAQUE}, so the
 * value counts by where it sits alone.</li>
 * </ol>
 * Rule 6 is what makes the hash safe for code nobody here has read: Quark 4.1's seed pouch keeps its contents in a
 * record whose {@code equals} compares by value and whose {@code hashCode} is the identity hash of an inner stack, and
 * a hash that trusted it would give two equal pouches two hash codes. The price is that values rule 6 covers do not
 * spread: they are told apart by {@code equals} alone.
 * <p>
 * The hash is a function of equality only within one run: identity hashes (rules 3 and 5) change with every start,
 * so nothing may persist it or order by it. Pure Java, so it is JUnit-tested ({@code ValueHashTest}); the Minecraft
 * classes of rule 2 live in {@code content.item.ComponentHash}.
 */
public class ValueHash {
    /** The rules without a hook: what a value of JDK types alone hashes to. */
    public static final ValueHash JDK = new ValueHash();
    /** Values nested deeper than this hash as {@code 0}; it also ends a structure that contains itself. */
    public static final int MAX_DEPTH = 16;
    /** The hash of a value whose {@code equals} nothing here can vouch for (rule 6). */
    public static final int OPAQUE = 0;

    /**
     * Whether a class keeps {@code Object#equals}, i.e. its equality is identity (rule 5). Read once per class, and the
     * answer never throws. A record always declares an {@code equals} of its own (JLS 8.10.3), so it is answered
     * without reflection. For any other class, {@link Class#getMethod} builds every public method of the class and of
     * its supertypes up to the one that declares {@code equals}, resolving each signature: a signature that names a
     * class missing on this side — a client-only type on a dedicated server, a type of an absent optional dependency —
     * throws a {@link LinkageError} there, although the class itself loads and runs. A class whose method cannot be
     * looked up, for that or any other reason, counts as having an {@code equals} of its own (rule 6), which is the
     * safe answer, and {@link ClassValue} keeps it, so the lookup is not tried again.
     */
    private static final ClassValue<Boolean> IDENTITY_EQUALS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (type.isRecord())
                return false;
            try {
                return type.getMethod("equals", Object.class).getDeclaringClass() == Object.class;
            } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
                return false;
            }
        }
    };

    protected ValueHash() {
    }

    /**
     * MurmurHash3's 32-bit finaliser: every input bit reaches every output bit, so sums of mixed terms do not cancel
     * the way sums of raw hash codes do.
     */
    public static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    /** The contract-safe hash of {@code value}; {@code 0} for {@code null}. */
    public final int hash(@Nullable Object value) {
        return hash(value, 0);
    }

    /** The hash of {@code value} nested {@code depth} levels deep (rule 1, then {@link #hashValue}). */
    protected final int hash(@Nullable Object value, int depth) {
        if (value == null || depth > MAX_DEPTH)
            return 0;
        return hashValue(value, depth);
    }

    /**
     * Rule 2, the hook: a subclass answers the classes it knows — built only from what their {@code equals} compares,
     * with {@link #hash(Object, int)} at {@code depth + 1} for every nested value — and hands everything else to
     * {@link #structural}.
     */
    protected int hashValue(Object value, int depth) {
        return structural(value, depth);
    }

    /** Rules 3 to 6. */
    protected final int structural(Object value, int depth) {
        Class<?> type = value.getClass();
        if (type == String.class || type == Integer.class || type == Long.class || type == Short.class
                || type == Byte.class || type == Character.class || type == Boolean.class || type == Float.class
                || type == Double.class || type == UUID.class)
            return value.hashCode();
        if (value instanceof Enum<?>)
            return value.hashCode();
        if (value instanceof Optional<?> optional)
            return optional.isPresent() ? 31 + hash(optional.get(), depth + 1) : 0;
        if (value instanceof List<?> list) {
            int h = 1;
            for (Object element : list)
                h = 31 * h + hash(element, depth + 1);
            return h;
        }
        if (value instanceof Set<?> set) {
            int h = 0;
            for (Object element : set)
                h += mix(hash(element, depth + 1));
            return h;
        }
        if (value instanceof Map<?, ?> map) {
            int h = 0;
            for (Map.Entry<?, ?> entry : map.entrySet())
                h += mix(31 * hash(entry.getKey(), depth + 1) + hash(entry.getValue(), depth + 1));
            return h;
        }
        return IDENTITY_EQUALS.get(type) ? System.identityHashCode(value) : OPAQUE;
    }
}
