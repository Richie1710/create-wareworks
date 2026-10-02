package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;

import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.stock.StockLevels;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRules;

/**
 * What a player's request at a warehouse terminal would cost across the two boundaries they set themselves, i.e. the
 * question the terminal asks before it carries the request out ({@code docs/warehouse-system.md} §3.6.6, M15 part 2,
 * issue #3).
 * <p>
 * <b>A reserve does not stop a player</b> ({@code core.stock.StockAccess}) — that is the user's decision for M15, and
 * it is exactly why the click is worth a question: nothing refuses it, so the only thing that can keep a player from
 * emptying a reserve they set last week is being told, in numbers, that they are about to. The same holds for the
 * maximum in the other direction: a request for something the aisle has to <b>make</b> brings the result into the
 * racks, and while the order runs the warehouse really does hold more of the item than its own cap says it wants.
 * <p>
 * Three things are worth asking about, and a question may name several of them at once:
 * <ul>
 * <li>{@link #fromReserve()} — items of the requested key that come out of its own reserve;</li>
 * <li>{@link #ingredients()} — items of <b>another</b> key that a production order this request starts would spend out
 * of <i>that</i> key's reserve. A screen can never work this out: it knows neither the aisle's patterns nor what their
 * ingredients are promised to, which is the whole reason the question is computed on the server;</li>
 * <li>{@link #pastMaximum()} — result items that would be left above the governing maximum once the request has been
 * served.</li>
 * </ul>
 * <b>Pure.</b> The numbers a decision needs are measured by the caller (what is available, which pattern would be
 * used, how many runs it would take) and the arithmetic is this class's; every number it produces comes out of
 * {@link StockRule}, so the question and the enforcement can never disagree about what a reserve is.
 *
 * @param key         the item the player asked for
 * @param amount      what the request would really take, i.e. the click's amount bounded by what the aisle can serve
 * @param fromReserve items of {@link #key()} that would come out of its reserve, 0 when the request stays above it
 * @param reserved    the whole reserve those items come out of, for the "{@code 10} of {@code 64}" the panel names
 * @param pastMaximum result items that would be left in the racks above the governing maximum <b>after</b> the request
 *                    has been served, 0 when none would be. It is the whole-run surplus a pattern makes and nobody asked
 *                    for, not the level the warehouse passes through while the order runs: those items are promised to
 *                    this very request and leave again ({@code ProductionOrder#promisedToRequest}), and asking about
 *                    them spent the credibility of a question the reserve depends on (M15 review fix)
 * @param made        result items the production order would make in total, i.e. whole runs of the pattern; 0 when
 *                    nothing would be produced. It is the "of these {@code n}" the panel names
 * @param maximum     that maximum, or {@link StockRule#UNSET} when no rule caps the item
 * @param ingredients the ingredients a production order would take out of a reserve of their own, in pattern order and
 *                    only the ones it really would; at most {@value ProductionPattern#MAX_INGREDIENTS} entries
 * @param <K>         item key type
 */
public record RequestConfirmation<K>(K key, long amount, long fromReserve, long reserved, long pastMaximum,
                                    long made, long maximum, List<ReservedIngredient<K>> ingredients) {
    public RequestConfirmation {
        Objects.requireNonNull(key, "key");
        amount = Math.max(0L, amount);
        reserved = Math.max(0L, reserved);
        made = Math.max(0L, made);
        // Never more than the reserve it comes out of, and never more than the request asks for: what is not there is
        // not "taken from the reserve", it is simply missing (StockRule#fromReserve).
        fromReserve = Math.max(0L, Math.min(fromReserve, Math.min(amount, reserved)));
        maximum = maximum >= 0L ? Math.min(maximum, StockRule.MAX_AMOUNT) : StockRule.UNSET;
        // Bounded by what the order really makes, because that is where the surplus comes from: it is not bounded by
        // the amount the player asked for, which is precisely the part that leaves again.
        pastMaximum = maximum == StockRule.UNSET ? 0L : Math.max(0L, Math.min(pastMaximum, made));
        ingredients = ingredients == null ? List.of()
                : List.copyOf(ingredients.size() <= ProductionPattern.MAX_INGREDIENTS ? ingredients
                        : ingredients.subList(0, ProductionPattern.MAX_INGREDIENTS));
    }

    /** A request that crosses nothing: the answer for every item of a warehouse without stock keepers. */
    public static <K> RequestConfirmation<K> none(K key, long amount) {
        return new RequestConfirmation<>(key, amount, 0L, 0L, 0L, 0L, StockRule.UNSET, List.of());
    }

    /**
     * One ingredient a production order would take out of a reserve.
     *
     * @param key         the ingredient
     * @param fromReserve how many of it the order would spend out of its reserve
     * @param reserved    the whole reserve that comes out of
     * @param <K>         item key type
     */
    public record ReservedIngredient<K>(K key, long fromReserve, long reserved) {
        public ReservedIngredient {
            Objects.requireNonNull(key, "key");
            reserved = Math.max(0L, reserved);
            fromReserve = Math.max(0L, Math.min(fromReserve, reserved));
        }
    }

    /**
     * The question a request for {@code wanted} items of {@code key} raises, measured against what the warehouse holds
     * right now.
     * <p>
     * The caller measures, this method decides. {@code pattern} and {@code runs} are deliberately handed in rather
     * than chosen here: they must be the pattern and the run count the <b>order itself</b> would use, so that the
     * question names the very ingredients that would be spent ({@code WarehouseControllerBlockEntity#startProductionPlan}
     * creates them, and the same numbers go into both).
     * <p>
     * This is the single-level form and the whole answer before M20: one pattern, one run count. A chain is measured
     * with {@link #ofPlan} instead, over every item the whole plan really takes out of the racks.
     *
     * @param rules                  the aisle's rules; only a governing rule is ever consulted
     * @param key                    the requested item
     * @param wanted                 what the click asked for; it is bounded here by what the aisle can really serve —
     *                               what is available plus what {@code runs} runs of {@code pattern} would yield — so
     *                               that a question can never name a number no request could reach
     * @param levels                 what the warehouse knows about {@code key}: {@link StockLevels#available()} is what
     *                               the request can fetch, the rest is what the maximum is judged against
     * @param pattern                the pattern a production order would use, empty when nothing would be produced
     * @param runs                   how many runs of it that order would make
     * @param ingredientAvailability what an ingredient is worth to this request, i.e. the same availability the order
     *                               would be started against (a player's, so <b>without</b> any reserve taken off —
     *                               which is what makes the reserved part of it worth naming)
     */
    public static <K> RequestConfirmation<K> of(StockRules<K> rules, K key, long wanted, StockLevels levels,
            Optional<? extends ProductionPattern<K>> pattern, long runs,
            ToLongFunction<? super K> ingredientAvailability) {
        Objects.requireNonNull(pattern, "pattern");
        long made = madeBy(pattern, runs);
        return ofPlan(rules, key, wanted, levels, made, demandOf(pattern, runs), ingredientAvailability);
    }

    /**
     * The question a request raises when the part that is not in stock is made by a whole <b>production plan</b> (M20,
     * issue #4, ADR-032) — the same two boundaries, measured over the chain instead of over one pattern.
     * <p>
     * Only two numbers of the plan matter here, and both come from the plan the request would really create, so what a
     * player is asked about and what then happens cannot disagree:
     * <ul>
     * <li>{@code made} is what the plan's <b>root</b> step yields, because that is the item that ends up in the racks
     * and the only one the requested key's own maximum is judged against. Every intermediate of the chain is checked
     * against <i>its</i> own maximum at plan time and refuses the plan outright ({@code PlanRefusal#NO_ROOM}), which is
     * a harder answer than a question;</li>
     * <li>{@code ingredientDemand} is what the plan takes <b>out of the racks</b>, per item
     * ({@code ProductionPlan#leafDemand()}) — the leaves of the chain, which may sit two steps away from what was
     * clicked. This is what makes "this spends 6 of the 32 Oak Logs held in reserve" sayable about a click on a chest.
     * An intermediate a step of the plan makes is deliberately <b>not</b> in there: the racks do not hold it, so no
     * reserve of it can be spent.</li>
     * </ul>
     *
     * @param made             result items the plan's root step would make in total, 0 when nothing would be produced
     * @param ingredientDemand items the plan would take out of the racks, per key, in the order it spent them
     */
    public static <K> RequestConfirmation<K> ofPlan(StockRules<K> rules, K key, long wanted, StockLevels levels,
            long made, Map<K, Long> ingredientDemand, ToLongFunction<? super K> ingredientAvailability) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(levels, "levels");
        Objects.requireNonNull(ingredientDemand, "ingredientDemand");
        Objects.requireNonNull(ingredientAvailability, "ingredientAvailability");
        made = Math.max(0L, made);
        long available = levels.available();
        // No request can take more than the racks hold plus what the order would yield, and a pattern makes whole runs,
        // so this bound is the caller's own number for a caller that measured properly and a real bound for one that
        // did not: a question must never name an amount nothing could have granted.
        long serveable = available + made < 0L ? Long.MAX_VALUE : available + made;
        long asked = Math.min(Math.max(0L, wanted), serveable);
        // What comes out of the racks, and what would therefore have to be made. Both halves matter: the reserve of
        // the requested item bounds the first, the maximum and the ingredients' own reserves the second.
        long fromStock = Math.min(asked, available);
        long produced = asked - fromStock;
        long maximum = rules.ruleFor(key).filter(StockRule::hasMaximum).map(StockRule::maximum)
                .orElse(StockRule.UNSET);
        // Result items that would be left above the cap once the request has been served, which is the part a player
        // can act on. The produced items are promised to this very request and leave the warehouse again, so measuring
        // the level the racks pass through while the order runs asked about nothing (M15 review fix): what stays is the
        // whole-run surplus nobody asked for.
        long surplus = Math.max(0L, made - produced);
        long settled = Math.max(0L, levels.stocked() + levels.inbound() + surplus - fromStock);
        long pastMaximum = made <= 0L || maximum == StockRule.UNSET ? 0L : Math.max(0L, settled - maximum);
        return new RequestConfirmation<>(key, asked, rules.fromReserve(key, available, fromStock),
                rules.heldBack(key, available), pastMaximum, made, maximum,
                reservedIngredients(rules, produced, ingredientDemand, ingredientAvailability));
    }

    /** Result items {@code runs} runs of {@code pattern} yield; 0 when nothing would be produced. */
    private static <K> long madeBy(Optional<? extends ProductionPattern<K>> pattern, long runs) {
        return pattern.isEmpty() || runs <= 0L ? 0L
                : pattern.get().resultFor((int) Math.min(Integer.MAX_VALUE, runs));
    }

    /** What {@code runs} runs of one pattern take out of the racks, per ingredient, in pattern order. */
    private static <K> Map<K, Long> demandOf(Optional<? extends ProductionPattern<K>> pattern, long runs) {
        if (pattern.isEmpty() || runs <= 0L)
            return Map.of();
        Map<K, Long> demand = new LinkedHashMap<>();
        for (ProductionEntry<K> ingredient : pattern.get().ingredients())
            demand.merge(ingredient.key(), (long) ingredient.count() * runs, Long::sum);
        return demand;
    }

    /**
     * The items of {@code demand} that a reserve of their own holds back from automation, in the order they were
     * spent.
     * <p>
     * Bounded at {@value ProductionPattern#MAX_INGREDIENTS} entries by the canonical constructor, because that is what
     * one confirmation payload carries. A chain whose leaves reach into <b>more</b> than that many different reserves
     * therefore names the first of them and the total is measured over exactly the named ones, so what the player
     * accepts and what the server re-checks stay the same number ({@code RequestAcknowledgement#covers}).
     */
    private static <K> List<ReservedIngredient<K>> reservedIngredients(StockRules<K> rules, long produced,
            Map<K, Long> demand, ToLongFunction<? super K> ingredientAvailability) {
        if (produced <= 0L || demand.isEmpty())
            return List.of();
        List<ReservedIngredient<K>> reserved = new ArrayList<>(Math.min(demand.size(),
                ProductionPattern.MAX_INGREDIENTS));
        for (Map.Entry<K, Long> ingredient : demand.entrySet()) {
            long needed = Math.max(0L, ingredient.getValue());
            long available = Math.max(0L, ingredientAvailability.applyAsLong(ingredient.getKey()));
            long fromReserve = rules.fromReserve(ingredient.getKey(), available, needed);
            if (fromReserve > 0L)
                reserved.add(new ReservedIngredient<>(ingredient.getKey(), fromReserve,
                        rules.heldBack(ingredient.getKey(), available)));
        }
        return reserved;
    }

    /**
     * Whether this request crosses anything at all, i.e. whether the terminal has to ask before making it. A warehouse
     * without stock keepers never does.
     * <p>
     * This is the question a <b>click</b> raises and the whole answer before M23; {@link #required(RequestScope)} is
     * the same decision for one portion of a list order.
     */
    public boolean required() {
        return required(RequestScope.CLICK);
    }

    /**
     * Whether a request of {@code scope} has to be asked about before it is made.
     * <p>
     * {@link RequestScope#CLICK} is {@link #required()}: the two boundaries a stock keeper set. A
     * {@link RequestScope#LIST} portion adds one thing to it — a request that would have items <b>made</b>
     * ({@link #made()}) is asked about as well, because a list order starts production while nobody is at the terminal
     * (M23, issue #19, "producible items ask too"). A warehouse that produces nothing for this request answers the
     * same for both scopes, so an aisle without production patterns never sees an extra dialog.
     */
    public boolean required(RequestScope scope) {
        if (fromReserve > 0L || pastMaximum > 0L || !ingredients.isEmpty())
            return true;
        return scope == RequestScope.LIST && made > 0L;
    }

    /** Items of other keys the request would spend out of their reserves, over all {@link #ingredients()}. */
    public long fromIngredientReserve() {
        long total = 0L;
        for (ReservedIngredient<K> ingredient : ingredients)
            total += ingredient.fromReserve();
        return total;
    }

    /**
     * Whether a request for this item would leave the warehouse above its own cap once it has been served, which is the
     * only lasting form of "past the maximum" a player can do anything about.
     */
    public boolean overflows() {
        return pastMaximum > 0L;
    }

    /** The answer that carries exactly this question out — what a client sends back when the player confirms. */
    public RequestAcknowledgement acknowledgement() {
        return acknowledgement(RequestScope.CLICK);
    }

    /**
     * The answer that carries exactly this question out for {@code scope}. A {@link RequestScope#LIST} answer also
     * names what would be made ({@link RequestAcknowledgement#produced()}), which is the number a list order's budget
     * is then spent down by (M23, ADR-036); a {@link RequestScope#CLICK} answer is the M15 one, byte for byte.
     */
    public RequestAcknowledgement acknowledgement(RequestScope scope) {
        return new RequestAcknowledgement(false, fromReserve, pastMaximum, fromIngredientReserve(),
                scope == RequestScope.LIST ? made : 0L);
    }
}
