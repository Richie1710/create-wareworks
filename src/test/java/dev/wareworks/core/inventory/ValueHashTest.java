package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link ValueHash}, the contract-safe hash of ADR-064 (issue #27): equal values hash equally whatever their own
 * {@code hashCode} does — structures built apart, a class shaped like Quark's seed pouch contents alone and nested, a
 * class whose equality is identity — a class whose signatures name a class missing on this side neither throws nor
 * guesses, and a structure that contains itself ends at the depth bound.
 */
class ValueHashTest {
    private static final ValueHash HASH = ValueHash.JDK;

    /**
     * Shaped like Quark 4.1's {@code ItemWrapperComponent}: equal by value, hashed by the identity of an inner object.
     * Two equal instances built apart break the {@code hashCode} contract.
     */
    private static final class QuarkShaped {
        private final StringBuilder inner;

        QuarkShaped(String contents) {
            this.inner = new StringBuilder(contents);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof QuarkShaped shaped && inner.toString().contentEquals(shaped.inner);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(inner);
        }
    }

    /** Keeps {@code Object#equals}: its equality is identity. */
    private static final class IdentityOnly {
    }

    /** A record: its {@code equals} is its own, so nothing here vouches for its {@code hashCode}. */
    private record Point(int x, int y) {
    }

    private enum Colour {
        RED, GREEN
    }

    /** Stands for a class missing on this side, as a client-only type is on a dedicated server ({@link Refusing}). */
    static final class MissingOnThisSide {
    }

    /** A record with a public method whose signature names {@link MissingOnThisSide}. */
    public record RecordNamingAMissingType(int x) {
        public static MissingOnThisSide clientOnly() {
            return null;
        }
    }

    /** A class that keeps {@code Object#equals}, with a public method whose signature names a missing class. */
    public static final class ClassNamingAMissingType {
        public MissingOnThisSide clientOnly() {
            return null;
        }
    }

    /**
     * Defines the named classes itself from the test's own class files and refuses one class outright, the way a
     * dedicated server has no client classes: the classes it defines load and run, but a signature naming the refused
     * class cannot be resolved.
     */
    private static final class Refusing extends ClassLoader {
        private final Set<String> own;
        private final String refused;

        Refusing(Set<Class<?>> own, Class<?> refused) {
            super(ValueHashTest.class.getClassLoader());
            this.own = own.stream().map(Class::getName).collect(Collectors.toSet());
            this.refused = refused.getName();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(refused))
                throw new ClassNotFoundException(name + " is missing on this side");
            if (!own.contains(name))
                return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null)
                    return loaded;
                try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null)
                        throw new ClassNotFoundException(name);
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }

    @Test
    void valueTypesHashWithTheirOwnHashCode() {
        assertEquals("pouch".hashCode(), HASH.hash("pouch"));
        assertEquals(Integer.hashCode(42), HASH.hash(42));
        assertEquals(Long.hashCode(1L << 40), HASH.hash(1L << 40));
        assertEquals(Boolean.hashCode(true), HASH.hash(true));
        assertEquals(Float.hashCode(1.5f), HASH.hash(1.5f));
        assertEquals(Double.hashCode(-2.25), HASH.hash(-2.25));
        assertEquals(Character.hashCode('w'), HASH.hash('w'));
        UUID id = UUID.fromString("380df991-f603-344c-a090-369bad2a924a");
        assertEquals(id.hashCode(), HASH.hash(UUID.fromString(id.toString())), "a UUID parsed apart");
        assertEquals(Colour.RED.hashCode(), HASH.hash(Colour.RED), "an enum: its identity, which is its equality");
        assertEquals(0, HASH.hash(null), "null");
    }

    @Test
    void equalStructuresBuiltApartHashEqually() {
        List<Object> arrayList = new ArrayList<>(List.of("a", 1, Colour.GREEN));
        assertEquals(HASH.hash(List.of("a", 1, Colour.GREEN)), HASH.hash(arrayList), "an ArrayList and List.of");

        Map<String, Integer> forwards = new LinkedHashMap<>();
        Map<String, Integer> backwards = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++)
            forwards.put("key" + i, i);
        for (int i = 19; i >= 0; i--)
            backwards.put("key" + i, i);
        assertEquals(forwards, backwards);
        assertEquals(HASH.hash(forwards), HASH.hash(backwards), "maps filled in opposite orders");
        assertEquals(HASH.hash(forwards), HASH.hash(new HashMap<>(backwards)), "a HashMap and a LinkedHashMap");
        assertEquals(HASH.hash(forwards), HASH.hash(new TreeMap<>(forwards)), "a TreeMap");

        Set<Object> set = new LinkedHashSet<>(List.of("x", "y", 3));
        Set<Object> reversed = new LinkedHashSet<>(List.of(3, "y", "x"));
        assertEquals(HASH.hash(set), HASH.hash(reversed), "sets filled in opposite orders");
        assertEquals(HASH.hash(set), HASH.hash(new HashSet<>(reversed)), "a HashSet");

        assertEquals(HASH.hash(Optional.of("seeds")), HASH.hash(Optional.of(new String("seeds"))), "optionals");
        assertEquals(0, HASH.hash(Optional.empty()), "an empty optional");
        assertNotEquals(HASH.hash(Optional.empty()), HASH.hash(Optional.of(0)), "present differs from empty");

        assertNotEquals(HASH.hash(List.of("a", "b")), HASH.hash(List.of("b", "a")), "a list is ordered");
    }

    @Test
    void nestingPastTheBoundStillHashesEqualStructuresEqually() {
        Object deep = "bottom";
        Object deepTwin = new String("bottom");
        for (int i = 0; i < ValueHash.MAX_DEPTH * 2; i++) {
            deep = List.of(deep);
            deepTwin = new ArrayList<>(List.of(deepTwin));
        }
        assertEquals(HASH.hash(deep), HASH.hash(deepTwin));

        Object shallow = "bottom";
        for (int i = 0; i < ValueHash.MAX_DEPTH; i++)
            shallow = List.of(shallow);
        Object other = "other";
        for (int i = 0; i < ValueHash.MAX_DEPTH; i++)
            other = List.of(other);
        assertNotEquals(HASH.hash(shallow), HASH.hash(other), "a value at the bound is still read");
    }

    @Test
    void aQuarkShapedValueHashesEquallyAloneAndNested() {
        QuarkShaped pouch = new QuarkShaped("16 wheat seeds");
        QuarkShaped twin = new QuarkShaped("16 wheat seeds");
        assertEquals(pouch, twin, "equal by value");
        assertEquals(HASH.hash(pouch), HASH.hash(twin), "and equal under the safe hash");
        assertEquals(ValueHash.OPAQUE, HASH.hash(pouch), "a class with an equals of its own counts by its place alone");

        assertEquals(HASH.hash(List.of("pouch", pouch)), HASH.hash(new ArrayList<>(List.of("pouch", twin))),
                "in a list");
        assertEquals(HASH.hash(Map.of("stored_item", pouch)), HASH.hash(new HashMap<>(Map.of("stored_item", twin))),
                "as a map value");
        assertEquals(HASH.hash(Set.of(pouch)), HASH.hash(Set.of(twin)), "in a set");
        assertEquals(HASH.hash(Optional.of(pouch)), HASH.hash(Optional.of(twin)), "in an optional");

        // The trap it reproduces: its own hashCode breaks the contract for equal values built apart.
        boolean broken = false;
        for (int i = 0; i < 100 && !broken; i++) {
            QuarkShaped a = new QuarkShaped("seeds");
            QuarkShaped b = new QuarkShaped("seeds");
            broken = a.equals(b) && a.hashCode() != b.hashCode();
        }
        assertTrue(broken, "the shape really hashes equal values apart, as Quark's does");
    }

    @Test
    void aRecordCountsByItsPlaceAlone() {
        assertEquals(ValueHash.OPAQUE, HASH.hash(new Point(1, 2)));
        assertEquals(HASH.hash(List.of(new Point(1, 2))), HASH.hash(List.of(new Point(3, 4))),
                "records nothing vouches for are told apart by equals alone");
    }

    @Test
    void aClassWithObjectEqualsHashesByIdentity() {
        IdentityOnly value = new IdentityOnly();
        assertEquals(System.identityHashCode(value), HASH.hash(value));
        assertEquals(HASH.hash(value), HASH.hash(value), "stable for one instance");
        int[] array = {1, 2, 3};
        assertEquals(System.identityHashCode(array), HASH.hash(array), "an array compares by identity too");
    }

    /**
     * A class whose public signatures name a class missing on this side loads and runs, but {@link Class#getMethod}
     * throws a {@link NoClassDefFoundError} for it. The hash must neither throw nor guess: such a value counts by its
     * place alone, a record and a class with {@code Object#equals} alike, and on every later call as well.
     */
    @Test
    void aClassWhoseSignaturesNameAMissingClassCountsByItsPlaceAlone() throws ReflectiveOperationException {
        ClassLoader loader = new Refusing(Set.of(RecordNamingAMissingType.class, ClassNamingAMissingType.class),
                MissingOnThisSide.class);
        Class<?> recordType = Class.forName(RecordNamingAMissingType.class.getName(), true, loader);
        Class<?> plainType = Class.forName(ClassNamingAMissingType.class.getName(), true, loader);
        assertNotEquals(RecordNamingAMissingType.class, recordType, "defined apart, by the refusing loader");
        assertTrue(recordType.isRecord(), "still a record");

        Constructor<?> recordConstructor = recordType.getDeclaredConstructor(int.class);
        recordConstructor.setAccessible(true);
        Object record = recordConstructor.newInstance(7);
        Constructor<?> plainConstructor = plainType.getDeclaredConstructor();
        plainConstructor.setAccessible(true);
        Object plain = plainConstructor.newInstance();

        // The trap is real: the classes run, but looking up their equals resolves every public signature.
        assertThrows(NoClassDefFoundError.class, () -> recordType.getMethod("equals", Object.class));
        assertThrows(NoClassDefFoundError.class, () -> plainType.getMethod("equals", Object.class));

        for (int call = 1; call <= 2; call++) {
            assertEquals(ValueHash.OPAQUE, assertDoesNotThrow(() -> HASH.hash(record)), "the record, call " + call);
            assertEquals(ValueHash.OPAQUE, assertDoesNotThrow(() -> HASH.hash(plain)), "the plain class, call " + call);
            assertEquals(HASH.hash(List.of(1, ValueHash.OPAQUE)), HASH.hash(List.of(1, plain)),
                    "nested as well, call " + call);
        }
    }

    @Test
    void aListThatHoldsItselfEndsAtTheBound() {
        List<Object> loop = new ArrayList<>();
        loop.add("start");
        loop.add(loop);
        long started = System.nanoTime();
        int first = HASH.hash(loop);
        assertEquals(first, HASH.hash(loop), "deterministic");
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "and finite");

        Map<String, Object> map = new HashMap<>();
        map.put("self", map);
        HASH.hash(map);
    }

    @Test
    void theHookAnswersFirstAndHandsTheRestOn() {
        ValueHash withPoints = new ValueHash() {
            @Override
            protected int hashValue(Object value, int depth) {
                if (value instanceof Point point)
                    return 31 * point.x() + point.y();
                return structural(value, depth);
            }
        };
        assertEquals(31 + 2, withPoints.hash(new Point(1, 2)), "a class the hook knows");
        assertEquals(withPoints.hash(List.of(new Point(1, 2), "a")), withPoints.hash(List.of(new Point(1, 2), "a")));
        assertNotEquals(withPoints.hash(List.of(new Point(1, 2))), withPoints.hash(List.of(new Point(2, 1))),
                "nested values go through the hook as well");
        assertEquals("a".hashCode(), withPoints.hash("a"), "everything else by the rules");
    }

    @Test
    void mixIsMurmurHash3sFinaliser() {
        assertEquals(0, ValueHash.mix(0));
        assertEquals(0x514E28B7, ValueHash.mix(1));
        assertEquals(0x087FCD5C, ValueHash.mix(42));
        assertEquals(ValueHash.mix(123_456_789), ValueHash.mix(123_456_789), "deterministic");
    }
}
