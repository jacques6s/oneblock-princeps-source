/*
 * This file is part of the Princeps project.
 */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Der Zielpunkt einer orientierten Platzierung muss seine Blickachse mit ABSTAND gewinnen, nicht knapp.
 *
 * <p><b>Warum dieser Test existiert.</b> Die Orientierung eines Kolbens, Beobachters oder Spenders entsteht in
 * vanilla aus der dominanten Achse des Blickvektors -- einer Grenze ohne jede Toleranz. Ein Zielpunkt, der
 * unmittelbar auf dieser Grenze liegt, macht die Platzierung zum Münzwurf: dann entscheidet nicht mehr die
 * Geometrie, sondern in welcher Reihenfolge zwei Implementierungen dieselben drei Zahlen addieren.
 *
 * <p><b>Der gemessene Schadensfall</b>, basalt-Lauf {@code 20260807-165024}, die eine Zelle von 1446, an der der
 * Lauf endete: {@code 81,-59,121}, gewollt {@code sticky_piston[facing=up]}, bekommen {@code facing=west}. Client
 * und Server hatten NACHWEISLICH dieselbe Rotation -- yaw -50,404 / pitch 37,852 auf beiden Seiten, mit
 * Threadnamen am Server-Mixin belegt. Der Blickvektor daraus: {@code |y| = 0,6136} gegen {@code |x| = 0,6086}
 * gegen {@code |z| = 0,5033}. Die senkrechte Achse gewann um <b>0,8 Prozent</b>.
 *
 * <p>Deshalb verlangt {@link BuilderProcess#mostAlignedPointOnFace} seit dem Fix einen Vorsprung von 15 Prozent
 * ({@code AIM_DOMINANCE_MARGIN}) und verwirft den Punkt sonst. Ein Punkt, den es nicht gibt, kann auch nicht
 * knapp gewinnen; die Standplatzsuche muss dann einen deutlicheren finden.
 *
 * <p><b>Dieser Test ruft die Produktionsmethode auf</b>, nicht eine Kopie der Formel. Eine Regel, die nur in einer
 * Testkopie steht, kann im Produktionscode still zurückfallen -- genau das ist bei der Ebenenband-Formel schon
 * einmal passiert, und deshalb ist {@code layerBand} inzwischen ebenfalls package-private.
 *
 * <p>Kein Minecraft-Zustand nötig: die Methode rechnet auf Vektoren, einer Blockposition und einer AABB.
 * {@code Bootstrap} wird trotzdem geladen, weil {@link Direction} und {@link AABB} statisch initialisieren.
 */
public class BuilderAimDominanceTest {

    /** Ein voller Block an der Stelle, gegen die geklickt wird. */
    private static final AABB FULL_CUBE = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    @BeforeClass
    public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /**
     * Das Auge steht so, dass der gewuenschte Blick klar dominiert -- der Punkt muss angenommen werden.
     *
     * <p>Auge zwei Bloecke ueber dem Klickziel und einen daneben: der Blick nach UNTEN gewinnt deutlich, weil die
     * senkrechte Strecke doppelt so lang ist wie die waagerechte.
     */
    @Test
    public void aClearlyDominantAimIsAccepted() {
        Vec3 eye = new Vec3(0.5D, 3.0D, 0.5D);
        Vec3 point = BuilderProcess.mostAlignedPointOnFace(
                eye, new BlockPos(0, 0, 0), FULL_CUBE, Direction.UP, Direction.DOWN);
        assertNotNull("ein Blick mit klarem Vorsprung nach unten muss einen Zielpunkt liefern", point);
        // Und der Punkt muss wirklich nach unten dominieren, nicht nur existieren.
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        assertTrue("die senkrechte Komponente muss die groesste sein",
                Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > Math.abs(dz));
    }

    /**
     * DER GEMESSENE SCHADENSFALL, als Zahlenprobe: ein Auge, von dem aus die senkrechte Achse nur um Haaresbreite
     * gewinnt, darf keinen Zielpunkt mehr bekommen.
     *
     * <p>Konstruiert so, dass der Abstand nach unten und der zur Seite fast gleich sind -- also genau die Lage, in
     * der {@code 0,6136 gegen 0,6086} entstanden ist. Vor dem Fix haette diese Methode den Punkt angenommen.
     */
    @Test
    public void anAimThatBarelyWinsIsRefused() {
        // Auge einen Block ueber der Oberseite und fast genauso weit seitlich versetzt: die Strecke nach unten und
        // die nach Osten sind praktisch gleich lang, der Vorsprung liegt weit unter fuenfzehn Prozent.
        Vec3 eye = new Vec3(-0.52D, 1.5D, 0.5D);
        Vec3 point = BuilderProcess.mostAlignedPointOnFace(
                eye, new BlockPos(0, 0, 0), FULL_CUBE, Direction.UP, Direction.DOWN);
        assertNull("ein Zielpunkt, dessen Blickachse nur knapp gewinnt, ist nicht vorhersagbar und muss"
                + " verworfen werden", point);
    }

    /**
     * Die Marge wirkt in BEIDE Richtungen derselben Achse und fuer alle drei Achsen -- sonst waere sie eine Regel
     * fuer Kolben nach unten und ein Zufall fuer alles andere.
     */
    @Test
    public void theMarginAppliesToEveryLookDirection() {
        for (Direction look : Direction.values()) {
            Direction face = look.getOpposite();
            // Auge genau auf der Diagonalen zwischen der Blickachse und einer Querachse: nie dominant genug.
            Direction sideways = look.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP;
            Vec3 eye = new Vec3(
                    0.5D - look.getStepX() * 1.5D - sideways.getStepX() * 1.5D,
                    0.5D - look.getStepY() * 1.5D - sideways.getStepY() * 1.5D,
                    0.5D - look.getStepZ() * 1.5D - sideways.getStepZ() * 1.5D);
            assertNull("Blickrichtung " + look + ": eine 45-Grad-Diagonale darf keinen Zielpunkt liefern",
                    BuilderProcess.mostAlignedPointOnFace(eye, new BlockPos(0, 0, 0), FULL_CUBE, face, look));
        }
    }

    /**
     * Ein Blick, der die Achse in die FALSCHE Richtung entlangzeigt, wird weiterhin verworfen -- das konnte die
     * Methode schon vorher, und die Marge darf es nicht versehentlich aufweichen.
     */
    @Test
    public void anAimPointingTheWrongWayIsStillRefused() {
        // Auge UNTER dem Block, verlangt wird aber ein Blick nach unten: unmoeglich.
        Vec3 eye = new Vec3(0.5D, -3.0D, 0.5D);
        assertNull("ein Blick, der die Achse rueckwaerts entlangzeigt, darf keinen Zielpunkt liefern",
                BuilderProcess.mostAlignedPointOnFace(
                        eye, new BlockPos(0, 0, 0), FULL_CUBE, Direction.DOWN, Direction.DOWN));
    }

    /**
     * DIE EIGENTLICHE INVARIANTE, und sie ist staerker als jeder konstruierte Einzelfall: wenn diese Methode einen
     * Punkt zurueckgibt, dann gewinnt dessen Blickachse mit mindestens der Marge.
     *
     * <p>Der erste Anlauf dieses Tests hat eine Augenposition konstruiert, von der aus der Vorsprung angeblich
     * genau 1,10 bzw. 1,25 betragen sollte -- und lag falsch, weil der Zielpunkt auf der Flaeche GEKLEMMT wird und
     * die Strecke daher eine andere ist als die konstruierte. Der Test war rot, und er hatte recht: nicht der Code
     * war falsch, sondern meine Vorstellung davon, wo der Punkt landet.
     *
     * <p>Deshalb jetzt umgekehrt herum. Statt eine Zahl vorzugeben und zu hoffen, dass die Geometrie sie trifft,
     * wird ueber viele Augenpositionen gefegt und fuer JEDEN angenommenen Punkt nachgerechnet, was tatsaechlich
     * herauskommt. Das prueft die Regel selbst und nicht meine Rekonstruktion davon.
     */
    @Test
    public void everyAcceptedPointWinsItsAxisByTheMargin() {
        final double margin = 1.15D;
        int accepted = 0;
        for (double ex = -4.0D; ex <= 5.0D; ex += 0.37D) {
            for (double ey = 1.05D; ey <= 5.0D; ey += 0.41D) {
                for (double ez = -4.0D; ez <= 5.0D; ez += 0.43D) {
                    Vec3 eye = new Vec3(ex, ey, ez);
                    Vec3 point = BuilderProcess.mostAlignedPointOnFace(
                            eye, new BlockPos(0, 0, 0), FULL_CUBE, Direction.UP, Direction.DOWN);
                    if (point == null) {
                        continue;
                    }
                    accepted++;
                    double dx = Math.abs(point.x - eye.x);
                    double dy = Math.abs(point.y - eye.y);
                    double dz = Math.abs(point.z - eye.z);
                    // Blick ist DOWN, die tragende Achse ist also y. Sie muss beide anderen um die Marge schlagen.
                    assertTrue("Auge " + eye + ": y=" + dy + " schlaegt x=" + dx + " nicht um die Marge",
                            dy >= dx * margin - 1e-9D);
                    assertTrue("Auge " + eye + ": y=" + dy + " schlaegt z=" + dz + " nicht um die Marge",
                            dy >= dz * margin - 1e-9D);
                }
            }
        }
        assertTrue("der Sweep muss ueberhaupt Punkte annehmen, sonst prueft er nichts", accepted > 50);
    }

    /**
     * Bei senkrechtem Blick muss die Senkrechte die GANZE Waagerechte schlagen, nicht nur ihre Aufteilung.
     *
     * <p>Der Test darueber prueft x und z einzeln, und genau das reicht nicht: die waagerechte Laenge des
     * Blickvektors ist cos(pitch) und haengt nicht vom Yaw ab -- der Yaw entscheidet nur, wie sie sich auf die
     * beiden Achsen verteilt. Diagonal traegt jede rund 71 Prozent und beide verlieren gegen y; achsparallel
     * traegt eine alles. Ein Punkt, der die Einzelachsen schlaegt, kann also kippen, sobald der Server einen
     * anderen Yaw heranzieht als der Client geprueft hat.
     *
     * <p>Drei Laeufe endeten daran, jedes Mal {@code sticky_piston[facing=up]} waagerecht gelandet, jedes Mal
     * unter 45 Grad Blickneigung. Bei 104,-59,82 lag der Yaw diagonal bei 227,5 Grad: x = 0,524 und z = -0,481
     * gegen y = -0,703, also Faktor 1,34 und damit ueber der Marge -- dieselbe Waagerechte achsparallel
     * gerechnet traegt 0,715 und schlaegt y = 0,699. Zwischen den beiden Faellen liegt nichts als der Yaw.
     */
    @Test
    public void everyAcceptedVerticalAimBeatsTheWholeHorizontal() {
        final double margin = 1.15D;
        int accepted = 0;
        for (double ex = -4.0D; ex <= 5.0D; ex += 0.37D) {
            for (double ey = 1.05D; ey <= 5.0D; ey += 0.41D) {
                for (double ez = -4.0D; ez <= 5.0D; ez += 0.43D) {
                    Vec3 eye = new Vec3(ex, ey, ez);
                    Vec3 point = BuilderProcess.mostAlignedPointOnFace(
                            eye, new BlockPos(0, 0, 0), FULL_CUBE, Direction.UP, Direction.DOWN);
                    if (point == null) {
                        continue;
                    }
                    accepted++;
                    double dy = Math.abs(point.y - eye.y);
                    double waagerecht = Math.hypot(point.x - eye.x, point.z - eye.z);
                    assertTrue("Auge " + eye + ": y=" + dy + " schlaegt die ganze Waagerechte "
                                    + waagerecht + " nicht um die Marge -- bei achsparallelem Yaw kippt das",
                            dy >= waagerecht * margin - 1e-9D);
                }
            }
        }
        assertTrue("der Sweep muss ueberhaupt Punkte annehmen, sonst prueft er nichts", accepted > 20);
    }

    /**
     * Die Nachrechnung des Schadensfalls selbst -- ohne die Methode, rein arithmetisch, damit die Zahlen im
     * Kommentar nachpruefbar sind und nicht nur behauptet.
     *
     * <p>yaw -50,404 / pitch 37,852 war die Rotation, die Client UND Server beim toedlichen Klick hatten.
     * Minecraft rechnet {@code x = sin(-yaw)*cos(pitch)}, {@code y = -sin(pitch)}, {@code z = cos(-yaw)*cos(pitch)}.
     */
    @Test
    public void theMeasuredCaseReallyWasOnTheEdge() {
        double yaw = -50.404D;
        double pitch = 37.852D;
        double f = Math.toRadians(pitch);
        double g = Math.toRadians(-yaw);
        double x = Math.sin(g) * Math.cos(f);
        double y = -Math.sin(f);
        double z = Math.cos(g) * Math.cos(f);

        assertEquals("senkrechte Komponente", 0.6136D, Math.abs(y), 0.001D);
        assertEquals("waagerechte Komponente in x", 0.6086D, Math.abs(x), 0.001D);
        assertEquals("waagerechte Komponente in z", 0.5033D, Math.abs(z), 0.001D);

        double advantage = Math.abs(y) / Math.abs(x);
        assertTrue("die senkrechte Achse gewann, aber nur knapp -- weniger als ein Prozent",
                advantage > 1.0D && advantage < 1.01D);
        assertTrue("und damit deutlich unter der Marge, die den Punkt heute verwirft",
                advantage < 1.15D);
    }
}
