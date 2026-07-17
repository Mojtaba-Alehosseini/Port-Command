package it.unige.portcommand.gui.model;

import java.util.Locale;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;

/**
 * Pure rendering model for {@code HUDPanel}. No Swing/AWT import.
 *
 * <p>Wallet and reputation went LIVE in task 20: {@code WalletChangedEvent} /
 * {@code ReputationChangedEvent} carry the post-mutation ABSOLUTE figure, which is what the
 * task-18 placeholders were waiting for — a delta alone was unusable because no starting figure
 * was published anywhere. This model therefore never accumulates deltas; it assigns the absolute,
 * so a missed event self-corrects on the next one.
 *
 * <p>Until the first money event of a run arrives there is genuinely nothing to show — the ledger
 * seeds are agent-side ({@code HarbourMasterInitArgs}) and unpublished — so the wallet/reputation
 * labels keep task 18's {@code "—"} placeholder rather than claiming a false €0/0.
 *
 * <p>Money is formatted with {@link Locale#ROOT} explicitly: the default locale would render the
 * thousands separator differently on Moji's box than in CI.
 */
public final class HUDModel {

    private static final String NO_DATA = "—";

    private int gameDay = 1;
    private int simHour;
    private int simMinute;
    private Double wallet;
    private Double reputation;

    public void onSimClockTick(SimClockTickEvent event) {
        this.gameDay = event.gameDay();
        this.simHour = event.simHour();
        this.simMinute = event.simMinute();
    }

    public void onWalletChanged(WalletChangedEvent event) {
        this.wallet = event.balance();
    }

    public void onReputationChanged(ReputationChangedEvent event) {
        this.reputation = event.score();
    }

    public String walletLabel() {
        return "Wallet: " + (wallet == null ? NO_DATA : String.format(Locale.ROOT, "€%,.0f", wallet));
    }

    public String reputationLabel() {
        return "Reputation: " + (reputation == null ? NO_DATA : String.valueOf(Math.round(reputation)));
    }

    /** The canonical €5,000 daily target ({@code Settings.dailyTargetEur}, task 20). */
    public String dailyTargetLabel() {
        return String.format(Locale.ROOT, "Daily Target: €%,.0f", Settings.load().dailyTargetEur());
    }

    public String dayLabel() {
        return "Day " + gameDay + " of " + Settings.load().maxGameDays();
    }

    public String timeLabel() {
        return String.format("%02d:%02d", simHour, simMinute);
    }

    public int gameDay() {
        return gameDay;
    }

    public int simHour() {
        return simHour;
    }

    public int simMinute() {
        return simMinute;
    }
}
