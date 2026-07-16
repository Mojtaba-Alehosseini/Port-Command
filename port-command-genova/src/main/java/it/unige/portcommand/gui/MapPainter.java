package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.artifacts.PortStateUpdate.TugDelta;
import it.unige.portcommand.gui.events.VesselArrivedEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.gui.model.MapModel;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.ontology.Position;

/**
 * Pure static painter: draws a {@link MapModel} snapshot onto a {@link Graphics2D}. No panel/EDT
 * concern here — {@code MapPanel.paintComponent} is the only caller in production;
 * {@code MapPainterTest} calls this directly against a {@code BufferedImage}, which is what makes
 * it unit-testable headless. Simple shapes only (rectangles/circles + labels) — no sprites, per
 * this session's brief (MASTER_PLAN's own pre-agreed exit-ramp order cuts pixel-art polish first
 * under schedule pressure).
 *
 * <p><b>World → panel fit transform.</b> Landmarks live in a fixed world rectangle
 * ({@link #WORLD_MIN_X}/{@link #WORLD_MIN_Y}/{@link #WORLD_W}/{@link #WORLD_H} — {@code Position}
 * pixel coordinates, the same space {@code agents.TugMath}/{@code BerthPositions} already use).
 * {@link #fit(int, int)} scales that rectangle uniformly (aspect preserved, letter-boxed) to fit
 * the ACTUAL panel size on every paint, so nothing clips regardless of window size and the map
 * re-fits automatically on resize (there is no cached size to invalidate — every
 * {@code paintComponent} recomputes). World-space content (berths, tug dots, the tug-base zone) is
 * drawn through the transform; panel-relative overlays (title, weather chip, arrival chips) are
 * drawn in raw panel coordinates so they stay pinned to the corners and keep a fixed readable font
 * size rather than scaling with the map.
 */
final class MapPainter {

    // --- world bounds (Position pixel space) -----------------------------------------------------
    // Covers every landmark: tug-base zone (x 20-90, y 80-270), the 4 berth rectangles
    // (x 365-535, y 125-375) plus their labels, and tug dots as they transit toward the berths.
    static final double WORLD_MIN_X = 0.0;
    static final double WORLD_MIN_Y = 50.0;
    static final double WORLD_W = 600.0;
    static final double WORLD_H = 350.0;
    private static final int MARGIN = 8;

    private static final Color SEA_COLOUR = new Color(180, 210, 235);
    private static final Color HARBOUR_OUTLINE = new Color(90, 110, 130);
    private static final Color TUG_BASE_ZONE = new Color(210, 210, 200);

    private static final int BERTH_WIDTH = 70;
    private static final int BERTH_HEIGHT = 50;
    private static final int TUG_RADIUS = 7;

    // Approximate area near the tug home bases (TugBasePositions, x=50/y=100-250) — deliberately
    // NOT pixel-exact, a cosmetic landmark only. The idle tugs themselves ARE rendered here at boot:
    // MapModel seeds a dot at each TugBasePositions entry (status IDLE) so the fleet is visible
    // before any tug ever moves, and live TugDeltas overwrite those seeds as tugs transit.
    private static final Rectangle2D TUG_BASE_ZONE_WORLD = new Rectangle2D.Double(20, 80, 70, 190);

    private MapPainter() {
    }

    /**
     * A world→panel mapping: {@code scale} plus the letter-box offsets. Package-private so
     * {@code MapPainterTest} can sample the REAL painted panel coordinates (the layer the on-screen
     * clipping bug lived at) rather than raw world coordinates.
     */
    record Transform(double scale, double offsetX, double offsetY) {
        int px(double worldX) {
            return (int) Math.round(offsetX + (worldX - WORLD_MIN_X) * scale);
        }

        int py(double worldY) {
            return (int) Math.round(offsetY + (worldY - WORLD_MIN_Y) * scale);
        }

        int len(double worldLength) {
            return Math.max(1, (int) Math.round(worldLength * scale));
        }
    }

    /** Uniform fit of the world rectangle into {@code panelW x panelH}, aspect preserved, centred. */
    static Transform fit(int panelW, int panelH) {
        double availW = Math.max(1.0, panelW - 2.0 * MARGIN);
        double availH = Math.max(1.0, panelH - 2.0 * MARGIN);
        double scale = Math.min(availW / WORLD_W, availH / WORLD_H);
        if (!Double.isFinite(scale) || scale <= 0.0) {
            scale = 1.0;
        }
        double offsetX = MARGIN + (availW - WORLD_W * scale) / 2.0;
        double offsetY = MARGIN + (availH - WORLD_H * scale) / 2.0;
        return new Transform(scale, offsetX, offsetY);
    }

    static void paint(Graphics2D g, int width, int height, MapModel model) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Transform t = fit(width, height);

        paintBackground(g, width, height);
        paintTugBaseZone(g, t);
        paintBerths(g, t, model.berths());
        paintTugs(g, t, model.tugs());
        paintWeatherChip(g, width, model.weather().orElse(null));
        paintArrivedVessels(g, height, model.arrivedVessels());
    }

    private static void paintBackground(Graphics2D g, int width, int height) {
        g.setColor(SEA_COLOUR);
        g.fillRect(0, 0, width, height);
        g.setColor(HARBOUR_OUTLINE);
        g.drawRect(2, 2, width - 5, height - 5);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
        g.drawString("Genova Harbour", 10, 18);
    }

    private static void paintTugBaseZone(Graphics2D g, Transform t) {
        int x = t.px(TUG_BASE_ZONE_WORLD.getX());
        int y = t.py(TUG_BASE_ZONE_WORLD.getY());
        int w = t.len(TUG_BASE_ZONE_WORLD.getWidth());
        int h = t.len(TUG_BASE_ZONE_WORLD.getHeight());
        g.setColor(TUG_BASE_ZONE);
        g.fillRect(x, y, w, h);
        g.setColor(HARBOUR_OUTLINE);
        g.drawRect(x, y, w, h);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        g.drawString("Tug Base", x - 2, y + h + 12);
    }

    private static void paintBerths(Graphics2D g, Transform t, Map<String, BerthOccupancy> berths) {
        for (Map.Entry<String, BerthOccupancy> entry : berths.entrySet()) {
            String berthId = entry.getKey();
            BerthOccupancy occupancy = entry.getValue();
            Position pos = BerthPositions.position(berthId);
            if (pos == null) {
                continue; // no coordinate known for this berth id -- nothing to draw
            }
            int x = t.px(pos.x() - BERTH_WIDTH / 2.0);
            int y = t.py(pos.y() - BERTH_HEIGHT / 2.0);
            int w = t.len(BERTH_WIDTH);
            int h = t.len(BERTH_HEIGHT);
            g.setColor(berthColour(occupancy.status()));
            g.fillRect(x, y, w, h);
            g.setColor(HARBOUR_OUTLINE);
            g.drawRect(x, y, w, h);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
            String label = occupancy.vesselId() != null ? berthId + " (" + occupancy.vesselId() + ")" : berthId;
            g.drawString(label, x + 3, y + 14);
        }
    }

    private static Color berthColour(BerthOccupancy.Status status) {
        return switch (status) {
            case FREE -> new Color(150, 220, 150);
            case PROVISIONAL -> new Color(240, 210, 90);
            case DOCKED -> new Color(90, 130, 200);
        };
    }

    private static void paintTugs(Graphics2D g, Transform t, Map<String, TugDelta> tugs) {
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        for (Map.Entry<String, TugDelta> entry : tugs.entrySet()) {
            TugDelta delta = entry.getValue();
            Position pos = delta.position();
            int cx = t.px(pos.x());
            int cy = t.py(pos.y());
            Ellipse2D dot = new Ellipse2D.Double(cx - TUG_RADIUS, cy - TUG_RADIUS, TUG_RADIUS * 2.0, TUG_RADIUS * 2.0);
            g.setColor(tugColour(delta.status()));
            g.fill(dot);
            g.setColor(Color.BLACK);
            g.draw(dot);
            g.drawString(entry.getKey(), cx + TUG_RADIUS + 2, cy + 4);
        }
    }

    private static Color tugColour(TugStatus status) {
        return switch (status) {
            case IDLE -> new Color(150, 150, 150);
            case BIDDING -> new Color(230, 200, 60);
            case EN_ROUTE_TO_VESSEL -> new Color(70, 120, 220);
            case ESCORTING -> new Color(60, 170, 90);
            case RETURNING -> new Color(230, 140, 40);
            case REFUELING -> new Color(160, 90, 190);
        };
    }

    private static void paintWeatherChip(Graphics2D g, int width, WeatherChangeEvent weather) {
        String text = weather == null
                ? "Weather: —"
                : String.format("Wind %.0fkn · %s · %s", weather.windKnots(), weather.state(),
                        weather.visibility());
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        int textWidth = g.getFontMetrics().stringWidth(text);
        int chipX = Math.max(10, width - textWidth - 20);
        g.setColor(new Color(255, 255, 255, 200));
        g.fillRoundRect(chipX - 6, 6, textWidth + 12, 18, 8, 8);
        g.setColor(HARBOUR_OUTLINE);
        g.drawRoundRect(chipX - 6, 6, textWidth + 12, 18, 8, 8);
        g.setColor(Color.BLACK);
        g.drawString(text, chipX, 19);
    }

    /** Caps how many chips stack up so a burst of concurrent arrivals can't run the label stack
     * off the top edge of the panel. */
    private static final int MAX_ARRIVED_VESSEL_CHIPS = 6;

    private static void paintArrivedVessels(Graphics2D g, int height, List<VesselArrivedEvent> arrived) {
        if (arrived.isEmpty()) {
            return;
        }
        List<VesselArrivedEvent> visible = arrived.size() > MAX_ARRIVED_VESSEL_CHIPS
                ? arrived.subList(arrived.size() - MAX_ARRIVED_VESSEL_CHIPS, arrived.size())
                : arrived;
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        int y = height - 8 - (visible.size() - 1) * 12;
        for (VesselArrivedEvent event : visible) {
            String text = event.vesselId() + " (" + event.vesselType() + ", " + event.channel() + ")";
            g.setColor(new Color(255, 255, 255, 200));
            int textWidth = g.getFontMetrics().stringWidth(text);
            g.fillRect(6, y - 10, textWidth + 6, 12);
            g.setColor(Color.BLACK);
            g.drawString(text, 8, y);
            y += 12;
        }
    }
}
