package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.List;
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
     * question names the very ingredients that would be spent ({@code WarehouseControllerBlockEntity#startProductionOrder}
     * picks them, and the same pair goes into both).
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
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(levels, "levels");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(ingredientAvailability, "ingredientAvailability");
        long available = levels.available();
        // No request can take more than the racks hold plus what the order would yield, and a pattern makes whole runs,
        // so this bound is the caller's own number for a caller that measured properly and a real bound for one that
        // did not: a question must never name an amount nothing could have granted.
        long serveable = pattern.isEmpty() || runs <= 0L ? available
                : available + (long) pattern.get().resultFor((int) Math.min(Integer.MAX_VALUE, runs));
        long asked = Math.min(Math.max(0L, wanted), serveable);
        // What comes out of the racks, and what would therefore have to be made. Both halves matter: the reserve of
        // the requested item bounds the first, the maximum and the ingredients' own reserves the second.
        long fromStock = Math.min(asked, available);
        long produced = asked - fromStock;
        long maximum = rules.ruleFor(key).filter(StockRule::hasMaximum).map(StockRule::maximum)
                .orElse(StockRule.UNSET);
        // What the order would really make: whole runs, which is regularly more than this request asked for.
        long made = pattern.isEmpty() || runs <= 0L ? 0L
                : pattern.get().resultFor((int) Math.min(Integer.MAX_VALUE, runs));
        // Result items that would be left above the cap once the request has been served, which is the part a player
        // can act on. The produced items are promised to this very request and leave the warehouse again, so measuring
        // the level the racks pass through while the order runs asked about nothing (M15 review fix): what stays is the
        // whole-run surplus nobody asked for.
        long surplus = Math.max(0L, made - produced);
        long settled = Math.max(0L, levels.stocked() + levels.inbound() + surplus - fromStock);
        long pastMaximum = made <= 0L || maximum == StockRule.UNSET ? 0L : Math.max(0L, settled - maximum);
        return new RequestConfirmation<>(key, asked, rules.fromReserve(key, available, fromStock),
                rules.heldBack(key, available), pastMaximum, made, maximum,
                reservedIngredients(rules, produced, pattern, runs, ingredientAvailability));
    }

    /** The ingredients of {@code runs} runs that a reserve of their own holds back from automation. */
    private static <K> List<ReservedIngredient<K>> reservedIngredients(StockRules<K> rules, long produced,
            Optional<? extends ProductionPattern<K>> pattern, long runs,
            ToLongFunction<? super K> ingredientAvailability) {
        if (produced <= 0L || runs <= 0L || pattern.isEmpty())
            return List.of();
        List<ReservedIngredient<K>> reserved = new ArrayList<>(ProductionPattern.MAX_INGREDIENTS);
        for (ProductionEntry<K> ingredient : pattern.get().ingredients()) {
            long needed = (long) ingredient.count() * runs;
            long available = Math.max(0L, ingredientAvailability.applyAsLong(ingredient.key()));
            long fromReserve = rules.fromReserve(ingredient.key(), available, needed);
            if (fromReserve > 0L)
                reserved.add(new ReservedIngredient<>(ingredient.key(), fromReserve,
                        rules.heldBack(ingredient.key(), available)));
        }
        return reserved;
    }

    /**
     * Whether this request crosses anything at all, i.e. whether the terminal has to ask before making it. A warehouse
     * without stock keepers never does.
     */
    public boolean required() {
        return fromReserve > 0L || pastMaximum > 0L || !ingredients.isEmpty();
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
        return new RequestAcknowledgement(false, fromReserve, pastMaximum, fromIngredientReserve());
    }
}
