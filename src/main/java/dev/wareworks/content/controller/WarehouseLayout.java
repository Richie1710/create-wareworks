package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackCandidate;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CraneNetwork;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.core.warehouse.RouteModel;
import dev.wareworks.core.warehouse.RouteTable;
import dev.wareworks.util.Headings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * World mapping of a whole warehouse: its dock, its {@link NetworkGeometry} and one {@link BranchLayout} per straight
 * branch ({@code docs/warehouse-system.md} §1, §4, ADR-033).
 * <p>
 * A warehouse of one aisle is a network of one branch, so everything here answers exactly what the single
 * {@code BranchLayout} answered before M21 — the same rack positions, the same addresses, the same containment.
 * <p>
 * <b>The ownership rule lives here.</b> A block laterally beside a corner is beside a <i>straight</i> rail of two
 * perpendicular branches at once, so {@link #candidates(BlockPos)} answers with up to four positions, one per
 * horizontal neighbour that is an aisle block. Which of them owns the block is decided by the member standing there:
 * a storage interface faces away from its branch, a station faces towards it, and because each candidate has a
 * distinct aisle block beside it, each requires a <b>distinct facing</b>. Exactly zero or one can therefore be
 * satisfied — the rule is total, and there are no dead corners.
 */
public record WarehouseLayout(BlockPos dock, Direction dockFacing, NetworkGeometry network,
                              List<BranchLayout> branches) {
    public WarehouseLayout {
        dock = Objects.requireNonNull(dock, "dock").immutable();
        Objects.requireNonNull(dockFacing, "dockFacing");
        Objects.requireNonNull(network, "network");
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        if (branches.size() != network.branchCount())
            throw new IllegalArgumentException("one layout per branch: " + branches.size() + " for "
                    + network.branchCount());
        for (int i = 0; i < branches.size(); i++) {
            if (branches.get(i).branch() != i)
                throw new IllegalArgumentException("branch " + i + " carries index " + branches.get(i).branch());
        }
        if (!branches.getFirst().dock().equals(dock))
            throw new IllegalArgumentException("the first branch starts at the dock: " + branches.getFirst().dock());
    }

    /** The warehouse a single aisle is: one branch, at the dock, running along the dock's facing. */
    public static WarehouseLayout single(BranchLayout aisle) {
        Objects.requireNonNull(aisle, "aisle");
        if (aisle.branch() != RackPosition.FIRST_BRANCH)
            throw new IllegalArgumentException("a single-aisle warehouse is its first branch: " + aisle.branch());
        NetworkGeometry geometry = NetworkGeometry.of(aisle.geometry(), Headings.of(aisle.facing()));
        return new WarehouseLayout(aisle.dock(), aisle.facing(), geometry, List.of(aisle));
    }

    /**
     * The warehouse a discovered {@link NetworkGeometry} describes around {@code dock}, with the aisle letter of the
     * first branch taken from {@code letter}. Every branch origin is the dock plus the branch's own offset.
     */
    public static WarehouseLayout of(BlockPos dock, Direction dockFacing, NetworkGeometry network,
                                     Optional<Character> letter) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(letter, "letter");
        List<BranchLayout> branches = new ArrayList<>(network.branchCount());
        for (BranchGeometry branch : network.branches()) {
            BlockPos origin = dock.offset(branch.originDx(), 0, branch.originDz());
            AisleGeometry geometry = new AisleGeometry(branch.length(), network.height());
            branches.add(new BranchLayout(origin, Headings.direction(branch.heading()), geometry,
                    branch.index() == RackPosition.FIRST_BRANCH ? letter : Optional.empty(), branch.index()));
        }
        return new WarehouseLayout(dock, dockFacing, network, branches);
    }

    public int branchCount() {
        return branches.size();
    }

    /** The dock's facing, i.e. the heading of the first branch. Named as {@link BranchLayout#facing()} is. */
    public Direction facing() {
        return dockFacing;
    }

    /** The mast height every branch shares. */
    public int height() {
        return network.height();
    }

    /**
     * The size of the branch at the dock — the whole warehouse of every build that never bends, and the number the
     * controller's status and goggle lines have always shown.
     */
    public AisleGeometry geometry() {
        return firstBranch().geometry();
    }

    /** The aisle letter of the branch at the dock, if it has one. */
    public Optional<Character> letter() {
        return firstBranch().letter();
    }

    /** The mapping of one branch, by index. */
    public BranchLayout branch(int index) {
        return branches.get(Objects.checkIndex(index, branches.size()));
    }

    /** The same warehouse with another aisle letter on the branch at the dock; the other branches keep theirs. */
    public WarehouseLayout withLetter(char aisleLetter) {
        if (firstBranch().letter().equals(Optional.of(aisleLetter)))
            return this;
        List<BranchLayout> relettered = new ArrayList<>(branches);
        relettered.set(RackPosition.FIRST_BRANCH, firstBranch().withLetter(aisleLetter));
        return new WarehouseLayout(dock, dockFacing, network, relettered);
    }

    /** The same warehouse with the letters of the branches beyond the first one replaced, in branch order. */
    public WarehouseLayout withBranchLetters(List<Optional<Character>> letters) {
        Objects.requireNonNull(letters, "letters");
        List<BranchLayout> lettered = new ArrayList<>(branches.size());
        for (int i = 0; i < branches.size(); i++) {
            BranchLayout branch = branches.get(i);
            Optional<Character> letter = i < letters.size() ? letters.get(i) : Optional.empty();
            lettered.add(i == RackPosition.FIRST_BRANCH || letter.isEmpty() ? branch : branch.withLetter(letter.get()));
        }
        return new WarehouseLayout(dock, dockFacing, network, lettered);
    }

    /**
     * The aisle letters of this warehouse as one character per branch, in branch order, for the goggle data of a crane
     * that drives all of them ({@link dev.wareworks.content.crane.CraneGoggleInfo}): a branch without a letter is
     * {@value dev.wareworks.content.crane.CraneGoggleInfo#NO_LETTER}, so the letters behind it still name their own
     * branch.
     */
    public String branchLetters() {
        StringBuilder letters = new StringBuilder(branches.size());
        for (BranchLayout branch : branches)
            letters.append(branch.letter().orElse(CraneGoggleInfo.NO_LETTER));
        return letters.toString();
    }

    /** The branch at the dock — the whole warehouse of every build that never bends. */
    public BranchLayout firstBranch() {
        return branches.get(RackPosition.FIRST_BRANCH);
    }

    /** The branch a rack position belongs to, or empty if the warehouse has no such branch. */
    public Optional<BranchLayout> branchOf(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        return branchAt(rack.branch());
    }

    /** The branch with this index, or empty if the warehouse has no such branch (a label of an aisle that is gone). */
    public Optional<BranchLayout> branchAt(int index) {
        return index >= 0 && index < branches.size() ? Optional.of(branches.get(index)) : Optional.empty();
    }

    /**
     * World direction of a rack side on one branch. A branch this warehouse does not have answers the side on the
     * branch at the dock, which is where a crane whose aisle vanished is put back anyway.
     */
    public Direction sideDirection(int branch, Side side) {
        return branchAt(branch).orElseGet(this::firstBranch).sideDirection(side);
    }

    /**
     * World position of a rack position of this warehouse, or empty when the warehouse has no such branch at all — a
     * record saved while the network still had that aisle, a crane job planned then, or a stock rule kept across a
     * rebuild. Such a label stands for <b>no block</b>, and a caller that acts on the world has to know that.
     */
    public Optional<BlockPos> worldPosOf(RackPosition rack) {
        return branchOf(rack).map(branch -> branch.rackPos(rack));
    }

    /**
     * World position of a rack position, for the callers that only ask the world a question about it.
     * <p>
     * A position naming a branch this warehouse does not have answers the <b>dock</b> block. That block is an aisle
     * block and therefore never a rack position of any warehouse, so every question asked about it gets the harmless
     * answer: no member stands there, no request is addressed to it, no keeper and no production station is at it.
     * It deliberately does <b>not</b> answer the first branch's block at the same position any more: that is a real
     * rack of another aisle, holding another player's chest, and reading a vanished aisle's label as that block
     * cancelled the requests of a perfectly good output station (M21 review fix). A caller that must tell "no block"
     * apart from a block asks {@link #worldPosOf}.
     */
    public BlockPos rackPos(RackPosition rack) {
        return worldPosOf(rack).orElse(dock);
    }

    /** Position {@code x} on the aisle line of {@code branch}. */
    public BlockPos aislePos(int branch, int x) {
        return branch(branch).aislePos(x);
    }

    /** Whether {@code rack} is a rack position of this warehouse. */
    public boolean contains(RackPosition rack) {
        return network.contains(rack);
    }

    /**
     * Every rack position of this warehouse the block at {@code pos} could occupy — one per horizontal neighbour that
     * is an aisle block of a perpendicular branch, so at most four, in ascending branch order. Empty for an aisle block
     * itself and for anything further than one block from every branch.
     * <p>
     * Which candidate owns the block is decided by the member there ({@link WarehouseMember#isAlignedWith} against
     * {@link #branch(int)} of the candidate), never here.
     */
    public List<RackPosition> candidates(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        int dy = pos.getY() - dock.getY();
        if (dy < 0 || dy >= network.height())
            return List.of();
        List<RackCandidate> candidates = network.candidates(pos.getX() - dock.getX(), pos.getZ() - dock.getZ());
        if (candidates.isEmpty())
            return List.of();
        List<RackPosition> positions = new ArrayList<>(candidates.size());
        for (RackCandidate candidate : candidates)
            positions.add(candidate.at(dy));
        return positions;
    }

    /** Whether {@code pos} is a rack position of this warehouse on at least one of its branches. */
    public boolean isRackPosition(BlockPos pos) {
        return !candidates(pos).isEmpty();
    }

    /**
     * The one rack position of {@code pos}, when the warehouse leaves no choice: a block laterally beside exactly one
     * branch. A block beside a corner has two candidates and is only decided by the member standing there
     * ({@link #candidates}), so this answers empty for it rather than guessing.
     * <p>
     * Every warehouse of one aisle — every warehouse up to 0.5.0 — has at most one candidate per block, so this is
     * literally what {@link BranchLayout#worldToLocal} answered there.
     */
    public Optional<RackPosition> worldToLocal(BlockPos pos) {
        List<RackPosition> candidates = candidates(pos);
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    /**
     * World direction of a rack side on the branch the position belongs to. A position whose branch this warehouse
     * does not have answers the dock's facing, which is along an aisle and therefore never the direction of a rack
     * side — the same "this label names nothing" answer {@link #rackPos} gives, for the same reason.
     */
    public Direction sideDirection(RackPosition rack) {
        return branchOf(rack).map(branch -> branch.sideDirection(rack.side())).orElse(dockFacing);
    }

    /** The address of an unambiguous world position, if its branch has a letter ({@link #worldToLocal}). */
    public Optional<StorageAddress> addressOf(BlockPos pos) {
        return worldToLocal(pos).flatMap(this::address);
    }

    /**
     * Whether the rails join two aisles at all ({@link RouteModel#reachable}). Since the crane turns corners (M21,
     * ADR-033), every aisle of one connected warehouse answers true; a branch the warehouse no longer has answers
     * false.
     * <p>
     * This is the <b>weaker</b> of the two questions and is about branches only. Whether a crane can really get
     * somewhere depends on where it stands, not on which branch it is named on — a branch that a broken rail made
     * shorter than the crane's own position still exists and still meets its neighbours — so everything that decides
     * whether a job may be planned or kept asks {@link #canDriveTo} instead (M21 review fix).
     */
    public boolean reachable(int fromBranch, int toBranch) {
        return RouteModel.reachable(network, fromBranch, toBranch);
    }

    /**
     * The route a crane at {@code (fromBranch, fromX)} drives to {@code (toBranch, toX)}, or empty when the rails do
     * not join them. Derived from the live network on every call and never stored, so a crane cannot hold a route to
     * rails a player has taken away ({@link RouteModel}).
     */
    public Optional<CraneRoute> route(int fromBranch, double fromX, int toBranch, double toX) {
        return RouteModel.route(network, fromBranch, fromX, toBranch, toX);
    }

    /**
     * Whether a crane standing at {@code (fromBranch, fromX)} can really drive to {@code rack} — the question the
     * machine itself answers, asked from the crane's own <b>point</b> and not from its branch alone
     * ({@link RouteTable#canDrive}).
     */
    public boolean canDriveTo(int fromBranch, double fromX, RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        return routes().canDrive(fromBranch, fromX, rack.branch(), rack.x());
    }

    /**
     * This warehouse's rails with their corner blocks already derived ({@link RouteTable}). A caller that asks one
     * route question can use {@link #route} and {@link #canDriveTo}; a caller that ranks a whole warehouse of
     * candidates builds this once and asks it, which is what keeps a planning pass from deriving the same handful of
     * corners once per rack.
     */
    public RouteTable routes() {
        return RouteTable.of(network);
    }

    /** The rails this warehouse's crane drives on, with {@code turnPenaltyBlocks} as the price of a quarter turn. */
    public CraneNetwork craneNetwork(double turnPenaltyBlocks) {
        return CraneNetwork.of(network, turnPenaltyBlocks);
    }

    /**
     * The machine put back onto the aisle at the dock, at the nearest position that aisle has and facing the way it
     * runs: where a crane goes whose own aisle this warehouse no longer has (M21 review fix, ADR-033).
     * <p>
     * It is not a jump. {@link #railOffset} has been drawing the machine on the aisle at the dock ever since its own
     * aisle left the network — a label of a branch that is gone names no line of blocks at all — so this only makes
     * the crane's state say what a player has been looking at, and it is what gives the machine a route again:
     * without it the crane stands on rails the warehouse does not contain, every route from it is empty, and the
     * warehouse plans and aborts the same job for ever.
     */
    public CranePose parkedAtDock(CranePose pose) {
        Objects.requireNonNull(pose, "pose");
        BranchLayout first = firstBranch();
        double x = Math.min(Math.max(pose.x(), 0.0), first.geometry().length());
        double y = Math.min(Math.max(pose.y(), 0.0), height() - 1.0);
        return pose.handedOver(RackPosition.FIRST_BRANCH, x).withXY(x, y)
                .withYaw(CranePose.yawOf(Headings.of(first.heading())));
    }

    /**
     * The same machine, named the way <b>this</b> warehouse names it, after the warehouse it stood in was rebuilt into
     * this one: the world block the pose stood for is looked up on these branches, and the pose is renamed onto the
     * branch that runs through it (M21 review fix, ADR-033).
     * <p>
     * A rebuild can renumber a branch — the same index counted from the other end, or a different line of blocks under
     * the same index — and a machine that kept its old number would be drawn somewhere it never was and would drive
     * its route from a starting point it is not at. Nothing about the machine moves here: only its name changes, which
     * is the same rename a hand-over at a corner is ({@link CranePose#handedOver}).
     * <p>
     * The <b>yaw is kept</b>: a rebuild does not turn the machine. A branch that was rebuilt from its other end now
     * runs the other way, so an idle crane is squared up with it by the dock's own resting alignment, and a crane with
     * a job turns towards the leg it drives, as it does at every corner.
     * <p>
     * Empty when the block is no longer on any aisle of this warehouse, or when the two warehouses do not share a dock
     * — then the pose stands for nothing here and the caller decides ({@link #parkedAtDock}).
     */
    public Optional<CranePose> renamedFrom(WarehouseLayout previous, CranePose pose) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(pose, "pose");
        if (!previous.dock.equals(dock))
            return Optional.empty();
        Vec3 offset = previous.railOffset(pose.branch(), pose.x());
        double worldX = dock.getX() + offset.x;
        double worldZ = dock.getZ() + offset.z;
        Direction.Axis was = previous.branchAt(pose.branch()).map(branch -> branch.heading().getAxis()).orElse(null);
        Optional<CranePose> anywhere = Optional.empty();
        for (BranchLayout branch : branches) {
            Direction heading = branch.heading();
            double dx = worldX - branch.origin().getX();
            double dz = worldZ - branch.origin().getZ();
            double along;
            if (heading.getAxis() == Direction.Axis.X) {
                if (dz != 0.0)
                    continue; // another line of blocks entirely
                along = dx * heading.getStepX();
            } else {
                if (dx != 0.0)
                    continue;
                along = dz * heading.getStepZ();
            }
            if (along < 0.0 || along > branch.geometry().length())
                continue;
            CranePose renamed = pose.handedOver(branch.branch(), along);
            // A corner block lies on two branches at once. The machine keeps the line it was driving on, so the
            // branch of the same axis wins and the other one is only the fallback.
            if (heading.getAxis() == was)
                return Optional.of(renamed);
            if (anywhere.isEmpty())
                anywhere = Optional.of(renamed);
        }
        return anywhere;
    }

    /**
     * Where the crane really stands when it is at position {@code x} of {@code branch}: the offset from the dock block
     * in world axes, as a continuous vector, so the renderer and the sounds follow the machine round a corner instead
     * of along the dock's own aisle.
     * <p>
     * A branch this warehouse does not have answers the dock's own aisle, which is where a crane with no network at
     * all stands — the pre-M21 answer, and the one every warehouse of one aisle gives anyway.
     */
    public Vec3 railOffset(int branch, double x) {
        BranchLayout line = branchAt(branch).orElseGet(this::firstBranch);
        Direction heading = line.heading();
        BlockPos origin = line.origin();
        return new Vec3(origin.getX() - dock.getX() + heading.getStepX() * x, origin.getY() - dock.getY(),
                origin.getZ() - dock.getZ() + heading.getStepZ() * x);
    }

    /**
     * Where the machine stands {@code partialTicks} of the way from the pose it had last tick to the one it has now:
     * the two offsets interpolated in world space (M21, ADR-033).
     * <p>
     * This is the only frame in which the tick of a <b>hand-over</b> can be interpolated at all. At a corner the crane
     * is renamed onto the next aisle on the block the two share, so {@code x} counts along one line before the rename
     * and along another after it; the two numbers say nothing about each other, and {@link CranePose#lerp} therefore
     * leaves them alone. Their world offsets do line up — the rename moves the machine by nothing — so this walks the
     * rails it really drove: up to the corner in the tick it hands over, away from it in the next.
     */
    public Vec3 railOffset(CranePose previous, CranePose current, double partialTicks) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Vec3 to = railOffset(current.branch(), current.x());
        // NaN and every value at or beyond the tick end: the pose the tick arrived at.
        if (!(partialTicks < 1.0))
            return to;
        double t = Math.max(0.0, partialTicks);
        Vec3 from = railOffset(previous.branch(), previous.x());
        return new Vec3(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t);
    }

    /**
     * Whether {@code other} names every branch this warehouse and it have in common at the same world blocks — the
     * question the controller asks before it decides whether saved records still mean what they meant.
     * <p>
     * A branch whose origin or heading moved <b>renumbers</b> every position on it, so its records refer to other
     * blocks than they did and have to be remapped. A branch that only became longer or shorter renumbers nothing,
     * because a branch is numbered from its origin outwards, and a branch that disappeared takes its positions out of
     * the rack space, where the membership list removes them normally.
     */
    public boolean namesTheSameBlocks(WarehouseLayout other) {
        Objects.requireNonNull(other, "other");
        if (!dock.equals(other.dock) || dockFacing != other.dockFacing)
            return false;
        int shared = Math.min(branches.size(), other.branches.size());
        for (int i = 0; i < shared; i++) {
            BranchLayout mine = branches.get(i);
            BranchLayout theirs = other.branches.get(i);
            if (!mine.origin().equals(theirs.origin()) || mine.heading() != theirs.heading())
                return false;
        }
        return true;
    }

    /** The address of a rack position, if its branch has a letter. */
    public Optional<StorageAddress> address(RackPosition rack) {
        return branchOf(rack).flatMap(branch -> branch.address(rack));
    }

    /**
     * Full-block bounds of every rack position of the warehouse: the union of its branches' bounds, which for one
     * branch is literally what that branch answers.
     */
    public AABB bounds() {
        AABB bounds = firstBranch().bounds();
        for (int i = 1; i < branches.size(); i++)
            bounds = bounds.minmax(branches.get(i).bounds());
        return bounds;
    }
}
