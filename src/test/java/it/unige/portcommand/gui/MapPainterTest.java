package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.artifacts.PortStateUpdate;
import it.unige.portcommand.gui.events.VesselArrivedEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.gui.model.MapModel;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.TugBasePositions;
import it.unige.portcommand.ontology.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot-style test: paints a seeded {@link MapModel} to a {@link BufferedImage} and samples
 * pixels at the REAL painted panel coordinates via {@link MapPainter#fit(int, int)} — NOT at raw
 * world coordinates. The on-screen berth-clipping bug lived precisely in the world→panel layer, so
 * a test that samples world coordinates directly (as the first version did) checks the wrong layer
 * and passes while the screen is wrong; these tests go through the same transform the painter does.
 */
class MapPainterTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 450;

    private static BufferedImage paint(MapModel model, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            MapPainter.paint(g, w, h, model);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Colour at a WORLD point, mapped through the same fit transform the painter used. */
    private static Color sample(BufferedImage image, MapPainter.Transform t, double worldX, double worldY) {
        return new Color(image.getRGB(t.px(worldX), t.py(worldY)));
    }

    /** A world point clear of every berth (x 365-535), the tug-base zone (x 20-90), and the tug
     * dots (x ~50) — plain harbour background regardless of panel size. */
    private static Color background(BufferedImage image, MapPainter.Transform t) {
        return sample(image, t, 300, 120);
    }

    @Test
    void allFourBerthsRenderFullyOnPanelAtARealisticSmallSize() {
        // 460x300 is narrower than the old 1:1 painter could fit -- berth_2/berth_4 (world x=500)
        // used to clip off the right edge here. Every berth rectangle must now map fully on-panel.
        int w = 460;
        int h = 300;
        MapModel model = new MapModel();
        BufferedImage image = paint(model, w, h);
        MapPainter.Transform t = MapPainter.fit(w, h);

        for (String berthId : BerthPositions.all().keySet()) {
            Position pos = BerthPositions.position(berthId);
            int leftPx = t.px(pos.x() - 35);   // BERTH_WIDTH/2
            int rightPx = t.px(pos.x() + 35);
            int topPx = t.py(pos.y() - 25);    // BERTH_HEIGHT/2
            int bottomPx = t.py(pos.y() + 25);
            assertTrue(leftPx >= 0 && rightPx < w,
                    berthId + " rectangle must fit horizontally on-panel (left=" + leftPx + " right=" + rightPx + " w=" + w + ")");
            assertTrue(topPx >= 0 && bottomPx < h,
                    berthId + " rectangle must fit vertically on-panel");
            assertNotEquals(background(image, t), sample(image, t, pos.x(), pos.y()),
                    berthId + " must render a visible rectangle at its centre");
        }
    }

    @Test
    void dockedBerthRendersADifferentColourThanFree() {
        MapModel model = new MapModel();
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);
        Position berth1 = BerthPositions.position("berth_1");
        Color freePixel = sample(paint(model, WIDTH, HEIGHT), t, berth1.x(), berth1.y());

        model.applyBerthDelta(new PortStateUpdate.BerthDelta("berth_1",
                new BerthOccupancy("berth_1", "V1", 0L, 1000L, 1, BerthOccupancy.Status.DOCKED)));
        Color dockedPixel = sample(paint(model, WIDTH, HEIGHT), t, berth1.x(), berth1.y());

        assertNotEquals(freePixel, dockedPixel, "DOCKED must render a visibly different colour than FREE");
    }

    @Test
    void idleTugsAreSeededAndRenderAtTheirBasesFromBoot() {
        MapModel model = new MapModel(); // no live deltas applied
        BufferedImage image = paint(model, WIDTH, HEIGHT);
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);

        // A base-zone pixel with no tug on it (world (80,90): inside the zone x20-90/y80-270, well
        // clear of the tug column at x=50) vs. tug_1's base centre (50,100). If the seed dot renders
        // on top of the zone, the two colours differ; if seeding were removed, both would be the
        // plain zone colour.
        Position base1 = TugBasePositions.position("tug_1");
        assertNotEquals(sample(image, t, 80, 90), sample(image, t, base1.x(), base1.y()),
                "a seeded IDLE tug dot must render on top of the base zone at boot");
    }

    @Test
    void noTugRendersAwayFromTheBasesUntilADeltaMovesOneThere() {
        MapModel model = new MapModel();
        BufferedImage image = paint(model, WIDTH, HEIGHT);
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);

        assertEquals(background(image, t), sample(image, t, 300, 300),
                "with only base seeds, an isolated spot away from the pier must be plain background");
    }

    @Test
    void aLiveTugDeltaMovesTheDotToItsNewPosition() {
        MapModel model = new MapModel();
        model.applyTugDelta(new PortStateUpdate.TugDelta("tug_1", new Position(300, 300, 0), TugStatus.ESCORTING));

        BufferedImage image = paint(model, WIDTH, HEIGHT);
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);

        assertNotEquals(background(image, t), sample(image, t, 300, 300),
                "a live tug delta must render the dot at its new position");
    }

    @Test
    void weatherChipRendersNearTheTopRightCorner() {
        MapModel model = new MapModel();
        model.applyWeather(new WeatherChangeEvent(40.0, "poor", 3.0, "stormy", true));

        BufferedImage image = paint(model, WIDTH, HEIGHT);
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);
        Color chipAreaPixel = new Color(image.getRGB(WIDTH - 15, 15));

        assertNotEquals(background(image, t), chipAreaPixel, "the weather chip must render near the top-right corner");
    }

    /**
     * Adversarial-review regression: a burst of concurrent arrivals used to grow the label stack
     * upward without limit ({@code y = height - 8 - (arrived.size()-1)*12}), eventually drawing
     * text above the visible canvas. Capped -- a large arrival count must not throw, and the most
     * recent arrival (always the last one rendered) must still land inside the canvas.
     */
    @Test
    void manyConcurrentArrivalsDoNotThrowAndTheMostRecentStaysOnCanvas() {
        MapModel model = new MapModel();
        for (int i = 0; i < 20; i++) {
            model.applyVesselArrived(new VesselArrivedEvent("V" + i, "cargo_vessel", "contracted"));
        }

        BufferedImage image = paint(model, WIDTH, HEIGHT); // must not throw despite 20 > the internal cap
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);
        Color mostRecentLabelArea = new Color(image.getRGB(8, HEIGHT - 8));

        assertNotEquals(background(image, t), mostRecentLabelArea,
                "the most recent arrival's chip must still render on-canvas near the bottom-left");
    }
}
