package dev.wareworks.core.address;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;

/**
 * Size and shape of one warehouse rail network: its straight {@link BranchGeometry branches} and the mast height they
 * share ({@code docs/warehouse-system.md} §1, ADR-033).
 * <p>
 * A network is what {@link AisleGeometry} is for a single aisle, and with exactly one branch it answers the same thing,
 * index for index: branch {@value RackPosition#FIRST_BRANCH} starts at the dock and runs along its facing, and
 * {@link RackPosition#ORDER} puts branch first, so a warehouse that never bends is described by the numbers it always
 * was.
 * <p>
 * Branch origins are dock-relative offsets, so this record holds no Minecraft type; the world mapping lives in
 * {@code content.controller.WarehouseLayout}. Where two perpendicular branches share an aisle block they form a
 * {@link BranchLink}, which is <b>derived</b> from the branch list and never saved.
 *
 * @param branches branches in discovery order, {@code branches.get(i).index() == i}, the first one at the dock
 * @param height   mast height (number of levels), {@value AisleGeometry#MIN_HEIGHT}..{@value AisleGeometry#MAX_HEIGHT}
 */
public record NetworkGeometry(List<BranchGeometry> branches, int height) implements RackSpace {
    /** Ints one branch takes in {@link #pack}: origin {@code dx}, origin {@code dz}, heading, length. */
    public static final int INTS_PER_BRANCH = 4;

    private static final Side[] SIDES = Side.values();
    private static final int QUARTER_TURNS = 4;

    public NetworkGeometry {
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        if (branches.isEmpty())
            throw new IllegalArgumentException("a network has at least the branch at its dock");
        if (branches.size() > StorageAddress.AISLE_COUNT)
            throw new IllegalArgumentException("a network has at most " + StorageAddress.AISLE_COUNT
                    + " branches: " + branches.size());
        for (int i = 0; i < branches.size(); i++) {
            BranchGeometry branch = branches.get(i);
            if (branch.index() != i)
                throw new IllegalArgumentException("branch " + i + " carries index " + branch.index());
        }
        BranchGeometry first = branches.get(0);
        if (first.originDx() != 0 || first.originDz() != 0)
            throw new IllegalArgumentException("the first branch starts at the dock: " + first);
        if (height < AisleGeometry.MIN_HEIGHT || height > AisleGeometry.MAX_HEIGHT)
            throw new IllegalArgumentException("height must be in " + AisleGeometry.MIN_HEIGHT + ".."
                    + AisleGeometry.MAX_HEIGHT + ": " + height);
    }

    /** A network of one branch, i.e. exactly what a warehouse was before M21. */
    public static NetworkGeometry single(Heading heading, int length, int height) {
        return new NetworkGeometry(List.of(BranchGeometry.first(heading, length)), height);
    }

    /** The one-branch network an {@link AisleGeometry} describes, running along {@code heading} from the dock. */
    public static NetworkGeometry of(AisleGeometry aisle, Heading heading) {
        Objects.requireNonNull(aisle, "aisle");
        return single(heading, aisle.length(), aisle.height());
    }

    public int branchCount() {
        return branches.size();
    }

    public BranchGeometry branch(int index) {
        return branches.get(Objects.checkIndex(index, branches.size()));
    }

    /** The branch at the dock: position 0 is the dock block, the heading is the dock's facing. */
    public BranchGeometry firstBranch() {
        return branches.get(RackPosition.FIRST_BRANCH);
    }

    /** The size of one branch on its own, in the shape a single aisle had before M21. */
    public AisleGeometry aisleGeometry(int index) {
        return new AisleGeometry(branch(index).length(), height);
    }

    public NetworkGeometry withHeight(int newHeight) {
        return newHeight == height ? this : new NetworkGeometry(branches, newHeight);
    }

    /**
     * This network cut back to a branch at the dock that is confirmed to be only {@code confirmedLength} rails long:
     * that branch truncated at its far end, and <b>nothing else</b>.
     * <p>
     * A network is a chain ({@link dev.wareworks.core.warehouse.RailGraph}), and every branch of a chain starts at the
     * far end of the one before it — the corner block belongs to both. So the block a shorter first branch gives up is
     * the very block the second branch begins at: the chain is cut there and everything beyond it is no longer this
     * warehouse. Dropping a <b>suffix</b> of the chain renumbers nothing, which is why this is allowed where adopting
     * a shorter scan would not be.
     * <p>
     * Truncating at the far end never renumbers either ({@link BranchGeometry#withLength}), and a length that is not
     * shorter answers {@code this}: this shrinks a warehouse, it never grows one.
     */
    public NetworkGeometry truncatedToFirstBranchLength(int confirmedLength) {
        BranchGeometry first = firstBranch();
        if (confirmedLength >= first.length())
            return this;
        return new NetworkGeometry(List.of(first.withLength(Math.max(0, confirmedLength))), height);
    }

    @Override
    public int rackPositionCount() {
        int positions = 0;
        for (BranchGeometry branch : branches)
            positions += branch.positionCount();
        return SIDES.length * positions * height;
    }

    @Override
    public boolean contains(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        if (rack.branch() >= branches.size() || rack.y() < 0 || rack.y() >= height)
            return false;
        return branches.get(rack.branch()).contains(rack.x());
    }

    @Override
    public int indexOf(RackPosition rack) {
        if (!contains(rack))
            return -1;
        int offset = 0;
        for (int i = 0; i < rack.branch(); i++)
            offset += branches.get(i).positionCount() * height * SIDES.length;
        return offset + (rack.x() * height + rack.y()) * SIDES.length + rack.side().ordinal();
    }

    @Override
    public RackPosition rackPosition(int index) {
        Objects.checkIndex(index, rackPositionCount());
        int remaining = index;
        for (BranchGeometry branch : branches) {
            int size = branch.positionCount() * height * SIDES.length;
            if (remaining >= size) {
                remaining -= size;
                continue;
            }
            int side = remaining % SIDES.length;
            int column = remaining / SIDES.length;
            return new RackPosition(branch.index(), column / height, column % height, SIDES[side]);
        }
        throw new IllegalStateException("index " + index + " outside " + this);
    }

    @Override
    public List<RackPosition> rackPositions() {
        return new RackPositionList(this);
    }

    /**
     * This network as a flat {@code int[]}, so a dock can save it and sync it to clients in one tag
     * ({@code docs/stacker-crane.md} §5, ADR-033): the height, then {@value #INTS_PER_BRANCH} ints per branch — the
     * origin's dock-relative {@code dx} and {@code dz}, the heading's {@link Heading#quarterTurns()} and the length.
     * <p>
     * Every branch of a network shares the dock's level, so no {@code dy} is written. At the address format's
     * {@value StorageAddress#AISLE_COUNT} branches this is 105 ints — well inside the crane's update tag budget, which
     * a list of compounds would not have been.
     */
    public int[] pack() {
        int[] packed = new int[1 + branches.size() * INTS_PER_BRANCH];
        packed[0] = height;
        int at = 1;
        for (BranchGeometry branch : branches) {
            packed[at++] = branch.originDx();
            packed[at++] = branch.originDz();
            packed[at++] = branch.heading().quarterTurns();
            packed[at++] = branch.length();
        }
        return packed;
    }

    /**
     * The network {@link #pack} wrote, or empty for anything this version cannot read as one — a wrong length, a
     * height or a branch outside the address limits, a first branch that does not start at the dock. <b>Never
     * throws</b>: the array may come from a save or from a packet, and a crane must rather have no network than a
     * broken one.
     */
    public static Optional<NetworkGeometry> unpack(int[] packed) {
        if (packed == null || packed.length < 1 + INTS_PER_BRANCH
                || (packed.length - 1) % INTS_PER_BRANCH != 0)
            return Optional.empty();
        int count = (packed.length - 1) / INTS_PER_BRANCH;
        if (count > StorageAddress.AISLE_COUNT)
            return Optional.empty();
        List<BranchGeometry> read = new ArrayList<>(count);
        int at = 1;
        for (int index = 0; index < count; index++) {
            int originDx = packed[at++];
            int originDz = packed[at++];
            int quarterTurns = packed[at++];
            int length = packed[at++];
            if (quarterTurns < 0 || quarterTurns >= QUARTER_TURNS || length < 0 || length > AisleGeometry.MAX_LENGTH)
                return Optional.empty();
            read.add(new BranchGeometry(index, originDx, originDz, Heading.fromQuarterTurns(quarterTurns), length));
        }
        try {
            return Optional.of(new NetworkGeometry(read, packed[0]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The aisle blocks two perpendicular branches share, in ascending branch order. Derived on every call; a network
     * has at most {@value StorageAddress#AISLE_COUNT} branches, so this is a handful of integer comparisons.
     */
    public List<BranchLink> links() {
        List<BranchLink> links = new ArrayList<>();
        for (int a = 0; a < branches.size(); a++) {
            BranchGeometry branchA = branches.get(a);
            for (int b = a + 1; b < branches.size(); b++) {
                BranchGeometry branchB = branches.get(b);
                if (!branchA.heading().isPerpendicularTo(branchB.heading()))
                    continue; // two collinear touching rails are one branch, so parallel branches never share a block
                sharedBlock(branchA, branchB).ifPresent(links::add);
            }
        }
        return List.copyOf(links);
    }

    /** Whether the aisle block at the dock-relative offset {@code (dx, dz)} belongs to any branch. */
    public boolean isAisleBlock(int dx, int dz) {
        for (BranchGeometry branch : branches) {
            if (branch.positionAt(dx, dz).isPresent())
                return true;
        }
        return false;
    }

    /**
     * Every way the world column at the dock-relative offset {@code (dx, dz)} could be a rack position of this network
     * — one per horizontal neighbour that is an aisle block of a perpendicular branch, so at most four
     * ({@link RackCandidate}).
     * <p>
     * The list is empty for an aisle block itself and for anything further than one block from every branch. Which
     * candidate owns the column is decided by the member standing there, never here.
     */
    public List<RackCandidate> candidates(int dx, int dz) {
        // An aisle block of this network is never a rack position of it, not even of a branch running past it: at
        // every corner the rail before the turn is laterally beside the perpendicular branch, so without this line
        // that rail - and the whole column above it, which is exactly where a crane's mast travels - was offered as a
        // storage location of the other aisle. At a turn in position 1 even the dock block was (M21 review fix).
        if (isAisleBlock(dx, dz))
            return List.of();
        List<RackCandidate> candidates = new ArrayList<>(1);
        for (BranchGeometry branch : branches) {
            Heading heading = branch.heading();
            int relX = dx - branch.originDx();
            int relZ = dz - branch.originDz();
            Optional<Side> side = Side.fromLateralOffset(heading.lateral(relX, relZ));
            if (side.isEmpty())
                continue;
            int along = heading.along(relX, relZ);
            if (!branch.contains(along))
                continue;
            Heading away = side.get() == Side.RIGHT ? heading.right() : heading.left();
            candidates.add(new RackCandidate(branch.index(), along, side.get(), away));
        }
        return candidates;
    }

    /** Whether {@code (dx, dz)} is laterally beside at least one branch, i.e. a rack column of this network. */
    public boolean isRackColumn(int dx, int dz) {
        return !candidates(dx, dz).isEmpty();
    }

    private static Optional<BranchLink> sharedBlock(BranchGeometry a, BranchGeometry b) {
        // Perpendicular branches: one runs along X, the other along Z, so the crossing block is fixed by their lines.
        BranchGeometry alongX = a.heading().axis() == Heading.Axis.X ? a : b;
        BranchGeometry alongZ = alongX == a ? b : a;
        int dx = alongZ.originDx();
        int dz = alongX.originDz();
        Optional<Integer> onX = alongX.positionAt(dx, dz);
        Optional<Integer> onZ = alongZ.positionAt(dx, dz);
        if (onX.isEmpty() || onZ.isEmpty())
            return Optional.empty();
        BranchGeometry lower = a.index() < b.index() ? a : b;
        BranchGeometry higher = lower == a ? b : a;
        int lowerX = lower == alongX ? onX.get() : onZ.get();
        int higherX = higher == alongX ? onX.get() : onZ.get();
        return Optional.of(new BranchLink(lower.index(), lowerX, higher.index(), higherX));
    }

    private static final class RackPositionList extends AbstractList<RackPosition> implements RandomAccess {
        private final NetworkGeometry network;

        RackPositionList(NetworkGeometry network) {
            this.network = network;
        }

        @Override
        public RackPosition get(int index) {
            return network.rackPosition(index);
        }

        @Override
        public int size() {
            return network.rackPositionCount();
        }

        @Override
        public int indexOf(Object o) {
            return o instanceof RackPosition rack ? network.indexOf(rack) : -1;
        }

        @Override
        public int lastIndexOf(Object o) {
            return indexOf(o);
        }

        @Override
        public boolean contains(Object o) {
            return indexOf(o) >= 0;
        }
    }
}
