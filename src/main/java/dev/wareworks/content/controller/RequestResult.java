package dev.wareworks.content.controller;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.job.RetrievalRequest;
import net.minecraft.core.BlockPos;

/**
 * Result of {@link WarehouseControllerBlockEntity#request}: the accepted retrieval request or why it was refused. Exactly
 * one of both is present.
 * <p>
 * A repeated request for an item a station already has an open request for is <b>merged</b> into that request
 * ({@code docs/warehouse-system.md} §7.2, ADR-020). {@link #request()} is then the merged request with its grown totals,
 * {@link #granted()} is what this call added and {@link #pending()} what the request waits for now — which is what lets a
 * terminal screen say "Requested Andesite x1" and still name the pending total.
 *
 * @param request   the accepted request (amount already clamped to the available stock)
 * @param rejection the reason for a refusal
 * @param granted   items this call added to the queue (0 for a refusal)
 * @param merged    whether the items grew an open request instead of queueing a new one
 * @param producing items of {@link #granted()} that are not in stock but are being produced for this request by a
 *                  production order ({@code docs/warehouse-system.md} §3.5); 0 when everything came from stock
 */
public record RequestResult(Optional<RetrievalRequest<ItemKey, BlockPos>> request,
                            Optional<RequestRejection> rejection, int granted, boolean merged, int producing) {
    public RequestResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(rejection, "rejection");
        if (request.isPresent() == rejection.isPresent())
            throw new IllegalArgumentException("exactly one of request and rejection must be present");
        if (request.isPresent() ? granted < 1 : granted != 0)
            throw new IllegalArgumentException("an accepted request grants at least 1 item, a refusal none: " + granted);
        if (merged && request.isEmpty())
            throw new IllegalArgumentException("a refusal never merges");
        producing = Math.max(0, producing);
        if (producing > 0 && request.isEmpty())
            throw new IllegalArgumentException("a refused request produces nothing");
    }

    public static RequestResult accepted(RetrievalRequest<ItemKey, BlockPos> request, int granted, boolean merged) {
        return accepted(request, granted, merged, 0);
    }

    /**
     * An accepted request of which {@code producing} items are not in stock yet but are being <b>made</b>: a production
     * order was started for them ({@code docs/warehouse-system.md} §3.5, ADR-024). The request itself is an ordinary
     * one and is served by ordinary {@code RETRIEVE} jobs as the result arrives in the warehouse.
     */
    public static RequestResult accepted(RetrievalRequest<ItemKey, BlockPos> request, int granted, boolean merged,
            int producing) {
        return new RequestResult(Optional.of(Objects.requireNonNull(request, "request")), Optional.empty(), granted,
                merged, producing);
    }

    public static RequestResult rejected(RequestRejection rejection) {
        return new RequestResult(Optional.empty(), Optional.of(Objects.requireNonNull(rejection, "rejection")), 0,
                false, 0);
    }

    public boolean isAccepted() {
        return request.isPresent();
    }

    /** Items the accepted request still waits for after this call, merged total included; 0 for a refusal. */
    public int pending() {
        return request.map(RetrievalRequest::remaining).orElse(0);
    }
}
