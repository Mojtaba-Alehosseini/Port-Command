package it.unige.portcommand.artifacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import it.unige.portcommand.ontology.Deal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe append-only store of negotiation outcomes. Written by walk-in vessel
 * agents (task 07) on each closed/withdrawn deal; queried by the Assistant (task 10)
 * for recent price statistics; persisted/restored by task 22. One instance, created
 * by {@code JadeBootstrap} and constructor-injected into the walk-in vessels and the
 * Assistant — never a static singleton.
 *
 * <p><b>Query window.</b> For a {@code d}-hour query the records are first filtered to
 * the vessel type within the inclusive ±20% duration band {@code [ceil(0.8d), floor(1.2d)]},
 * then narrowed to the LARGER of {the 50 most recent} and {all within the last 7 game-days}.
 * Those two sets are nested (the most-recent-50 and within-7-days both order by sim time),
 * so the larger is their union. "Now" is the latest recorded sim time (the market is the
 * deals; no clock is injected).
 */
public final class MarketHistoryArtifact {

    private static final Logger log = LoggerFactory.getLogger(MarketHistoryArtifact.class);
    private static final long SEVEN_DAYS_SIM_MILLIS = 7L * 86_400_000L;
    private static final int MAX_RECENT = 50;
    private static final int MIN_CONFIDENT = 10;

    private final List<DealRecord> deals = new ArrayList<>();
    private final List<Consumer<DealRecord>> subscribers = new ArrayList<>();

    /** Appends a deal outcome and notifies subscribers. */
    public synchronized void record(DealRecord deal) {
        deals.add(deal);
        for (Consumer<DealRecord> subscriber : subscribers) {
            try {
                subscriber.accept(deal);
            } catch (RuntimeException e) {
                log.warn("MarketHistory subscriber threw on record", e);
            }
        }
    }

    public synchronized void subscribe(Consumer<DealRecord> subscriber) {
        subscribers.add(subscriber);
    }

    /** The {@code n} most-recently recorded deals, in insertion order (task 22 persists last 200). */
    public synchronized List<DealRecord> lastN(int n) {
        int from = Math.max(0, deals.size() - n);
        return List.copyOf(deals.subList(from, deals.size()));
    }

    /** Replaces the store from a persisted list (task 22 load). */
    public synchronized void restore(List<DealRecord> records) {
        deals.clear();
        deals.addAll(records);
    }

    /** Aggregate stats for {@code vesselType} at {@code durationHours} over the query window. */
    public synchronized MarketStats queryStats(String vesselType, int durationHours) {
        int lo = (int) Math.ceil(0.8 * durationHours);
        int hi = (int) Math.floor(1.2 * durationHours);

        List<DealRecord> banded = deals.stream()
                .filter(d -> d.vesselType().equals(vesselType)
                        && d.durationHours() >= lo && d.durationHours() <= hi)
                .sorted(Comparator.comparingLong(DealRecord::simTime).reversed())
                .toList();
        if (banded.isEmpty()) {
            return MarketStats.empty();
        }

        long now = deals.stream().mapToLong(DealRecord::simTime).max().orElse(0L);
        List<DealRecord> mostRecent50 = banded.stream().limit(MAX_RECENT).toList();
        List<DealRecord> within7Days = banded.stream()
                .filter(d -> d.simTime() >= now - SEVEN_DAYS_SIM_MILLIS)
                .toList();
        List<DealRecord> window = within7Days.size() >= mostRecent50.size() ? within7Days : mostRecent50;

        return statsOf(window);
    }

    private static MarketStats statsOf(List<DealRecord> window) {
        int n = window.size();
        double sum = 0.0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int dealCount = 0;
        for (DealRecord d : window) {
            sum += d.price();
            min = Math.min(min, d.price());
            max = Math.max(max, d.price());
            if (d.outcome() == Deal.Outcome.DEAL) {
                dealCount++;
            }
        }
        double mean = sum / n;
        double varianceSum = 0.0;
        for (DealRecord d : window) {
            double delta = d.price() - mean;
            varianceSum += delta * delta;
        }
        double stddev = Math.sqrt(varianceSum / n); // population stddev
        double dealRate = (double) dealCount / n;
        return new MarketStats(mean, stddev, n, min, max, dealRate, n < MIN_CONFIDENT);
    }
}
