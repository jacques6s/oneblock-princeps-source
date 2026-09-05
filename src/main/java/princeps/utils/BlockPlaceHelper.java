/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.utils;

import princeps.Princeps;
import princeps.api.utils.IPlayerContext;
import princeps.process.builder.BuildTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;

    private final IPlayerContext ctx;
    private final princeps.api.IPrinceps princeps;
    private int rightClickTimer;
    private long successfulBlockInteractions;
    private SuccessfulBlockInteraction lastSuccessfulBlockInteraction;
    private ExpectedPlacement expectedPlacement;
    // Place timing is execution-only (never replayed in path/reach prediction), so a plain RNG is fine here.
    private final java.util.Random placeRng = new java.util.Random();

    BlockPlaceHelper(princeps.api.IPrinceps princeps) {
        this.princeps = princeps;
        this.ctx = princeps.getPlayerContext();
    }

    /**
     * Is the place cooldown still running? A click forced now is silently discarded.
     *
     * <p>Exposed because nothing above ever asked. The builder forces a right-click every tick until one lands, which
     * WORKS but makes its own trace unreadable: 793 of 1733 episodes look like "clicked more than once" when the bot
     * was simply waiting for a door it could not see. Anything that counts or logs a click should ask this first.
     */
    public boolean isThrottled() {
        return rightClickTimer > 0;
    }

    public void tick(boolean rightClickRequested) {
        // The throttle is checked BEFORE the expectation is consumed. It used to read expectedPlacement into a local
        // and null the field first, so a click forced during the cooldown was dropped AND took its expectation with
        // it -- the builder had announced what it was about to place, and the announcement was destroyed without the
        // placement ever being attempted. Measured on one run: of 1578 repeat clicks on the same cell, 1573 landed on
        // the very next tick, i.e. the builder is re-forcing every tick against a closed door and losing an
        // expectation each time.
        if (rightClickTimer > 0) {
            rightClickTimer--;
            return;
        }
        ExpectedPlacement expected = expectedPlacement;
        expectedPlacement = null; // one forced-input tick owns one immutable expectation
        HitResult mouseOver = ctx.objectMouseOver();
        if (!rightClickRequested || ctx.player().isHandsBusy() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockHitResult blockHit = (BlockHitResult) mouseOver;
        if (expected != null && !expected.matches(
                blockHit,
                ctx.player().getInventory().getSelectedSlot(),
                ctx.player().getMainHandItem().getItem())) {
            return; // the world/look/inventory changed since planning; never execute a different click
        }
        // DIE ORIENTIERUNGS-SPERRE. Die fuenf Felder oben beschreiben WOHIN geklickt wird, nicht WAS dabei
        // entsteht -- und fuer die gerichtete Blockfamilie entscheidet darueber die Blickachse, die sich seit der
        // Anmeldung noch bewegt hat. Hier ist der letztmoegliche Moment, an dem das noch billig ist: ein
        // zurueckgehaltener Klick kostet einen Tick, ein gedrehter Block kostet nach P5 den ganzen Bau.
        if (expected != null && expected.stillCorrect != null && !expected.stillCorrect.getAsBoolean()) {
            orientationHoldbacks++;
            return;
        }
        // Jitter the inter-place cooldown so repeated placing (bridging, pillaring, scaffolding) is not a perfectly
        // periodic metronome — a constant place gap is a periodogram tell a human never produces. Symmetric
        // ~Gaussian jitter (sum-of-3 uniforms) preserves the mean throughput (verified 0.0% at the default), floored
        // at 1. Gated on humanizedLook (raw Princeps / rightClickSpeed<=3 keep the exact constant). Same treatment as
        // BlockBreakHelper's break cadence; does not touch the look tuning.
        final int placeBase = Princeps.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
        final boolean mayJitter = (expected == null || !expected.deterministic)
                && Princeps.settings().humanizedLook.value;
        if (mayJitter && placeBase > 2) {
            final double g = this.placeRng.nextDouble() + this.placeRng.nextDouble() + this.placeRng.nextDouble() - 1.5;
            rightClickTimer = Math.max(1, placeBase + (int) Math.round(g * 1.5));
        } else if (mayJitter && placeBase > 0) {
            // DIE LUECKE, DIE SICH ERST BEIM SCHNELLERWERDEN OEFFNET. Der Zweig darueber greift erst ab
            // placeBase > 2, also ab rightClickSpeed 4 -- dem Standardwert. Wer die Rate senkt, um schneller zu
            // bauen, schaltet den Jitter damit STILL ab und bekommt eine exakt periodische Klickfolge. Genau die
            // ist der Tell, gegen den der Jitter ueberhaupt existiert: nicht die Geschwindigkeit faellt auf,
            // sondern ein Takt ohne jede Streuung.
            //
            // Der Owner hat die Voraussetzung dazu richtiggestellt, und meine urspruengliche Begruendung war
            // falsch: die vier Ticks sind die Sperre fuer die GEHALTENE Maustaste. Einzelklicks umgehen sie, ein
            // Mensch schafft damit bis zu einen Block pro Tick. Schneller zu setzen ist also kein Tell -- ein
            // Metronom zu sein schon.
            //
            // Bei so kleinen Grundwerten traegt die Gauss-Streuung des Zweigs darueber nicht mehr (sie wuerde auf
            // 1 geklemmt und waere wieder konstant). Stattdessen eine ganzzahlige Streuung um plus/minus eins,
            // nach unten bis 0 offen: die mittlere Rate bleibt, der Takt verschwindet.
            final int spread = this.placeRng.nextInt(3) - 1;   // -1, 0 oder +1
            rightClickTimer = Math.max(0, placeBase + spread);
        } else {
            rightClickTimer = placeBase;
        }
        InteractionHand[] hands = expected == null
                ? InteractionHand.values() : new InteractionHand[]{InteractionHand.MAIN_HAND};
        for (InteractionHand hand : hands) {
            ItemStack usedStack = ctx.player().getItemInHand(hand);
            Item usedItem = usedStack.getItem();
            int selectedSlot = hand == InteractionHand.MAIN_HAND
                    ? ctx.player().getInventory().getSelectedSlot() : -1;
            // DER ABGLEICH, DEN DER OWNER VERLANGT HAT -- Client-Haelfte. Unmittelbar vor dem Absenden des
            // Use-Pakets: was sieht der Client in diesem Moment? Gegenstueck ist die [SRVAIM]-Zeile in
            // MixinServerGamePacketListenerImpl, die auf der Serverseite dasselbe Paket protokolliert -- und
            // zwar ZWEIMAL, einmal beim Eintreffen auf dem Netty-Thread und einmal bei der Ausfuehrung auf dem
            // Server-Thread. Genau diese zwei Zeitpunkte sind der Kern der Sache.
            //
            // Protokolliert wird JEDER Rechtsklick mit armed=yes/NO, nicht nur die angemeldeten. Gemessen,
            // basalt 20260807-163220: 846 von 846 Klicks waren bewaffnet -- es gibt keinen ungeschuetzten
            // Klickpfad im Client. Eine fruehere Rechnung ("372 ungeprueft") war eine Differenz zweier
            // unterschiedlich instrumentierter Zaehlungen und ist damit widerlegt.
            // KEPT, BUT NO LONGER ON BY DEFAULT.
            //
            // The question this was built to answer is answered, and the paragraph above says so: 846 of 846
            // clicks were armed, the "372 unchecked" figure was a difference between two differently instrumented
            // counts, and it is refuted. What remained was the cost. This block runs on EVERY right click the bot
            // makes -- six String.format calls and a synchronous stdout write on the render thread -- and a single
            // customer log carries 25,902 of these lines. The customer's word for the result was "lagged".
            //
            // Behind chatDebug, which the build profile already turns off for a session and which is how every
            // other engine diagnostic is switched on for one measured run.
            if (Princeps.settings().chatDebug.value) {
                final net.minecraft.world.phys.Vec3 look = ctx.player().getViewVector(1.0F);
                // WAS DER CLIENT GLAUBT, DASS DER SERVER BENUTZEN WIRD -- die Vorhersage selbst, nicht ihre
                // Zutaten. Die Rechnung stimmt (Kopf-Yaw eine Sendung alt plus aktueller Pitch), die Vorhersage
                // hat den Klick trotzdem durchgelassen; also ist die Annahme darueber, WELCHE vorherige Sendung
                // der Kopf traegt, noch nicht richtig. Diese zwei Zahlen gegen viewYRot/viewXRot der SRVAIM-Zeile
                // gehalten beantworten das in einem Lauf, statt es weiter herzuleiten.
                final princeps.api.utils.Rotation mix = princeps == null ? null
                        : princeps.getLookBehavior().getRotationTheServerWillUse().orElse(null);
                System.out.println("[CLIAIM]"
                        + " armed=" + (expected != null ? "yes" : "NO")
                        + " mixYaw=" + (mix == null ? "?" : String.format(java.util.Locale.ROOT, "%.3f", mix.getYaw()))
                        + " mixPitch=" + (mix == null ? "?" : String.format(java.util.Locale.ROOT, "%.3f", mix.getPitch()))
                        + " yaw=" + String.format(java.util.Locale.ROOT, "%.3f", ctx.player().getYRot())
                        + " pitch=" + String.format(java.util.Locale.ROOT, "%.3f", ctx.player().getXRot())
                        + " nearest=" + net.minecraft.core.Direction.getApproximateNearest(look.x, look.y, look.z)
                        + " pos=" + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                                ctx.player().getX(), ctx.player().getY(), ctx.player().getZ())
                        + " against=" + blockHit.getBlockPos().getX() + "," + blockHit.getBlockPos().getY()
                        + "," + blockHit.getBlockPos().getZ()
                        + " face=" + blockHit.getDirection()
                        + " target=" + blockHit.getBlockPos().relative(blockHit.getDirection()).getX()
                        + "," + blockHit.getBlockPos().relative(blockHit.getDirection()).getY()
                        + "," + blockHit.getBlockPos().relative(blockHit.getDirection()).getZ()
                        + " cursor=" + String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f",
                                blockHit.getLocation().x, blockHit.getLocation().y, blockHit.getLocation().z));
            }
            BlockPos placedAt = blockHit.getBlockPos().relative(blockHit.getDirection());
            var beforePlacement = ctx.world().getBlockState(placedAt);
            if (ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver) == InteractionResult.SUCCESS) {
                if (usedItem instanceof net.minecraft.world.item.BlockItem
                        && princeps.getBuilderProcess() instanceof princeps.process.BuilderProcess builder) {
                    builder.recordNavigationScaffold(placedAt, beforePlacement, ctx.world().getBlockState(placedAt));
                }
                successfulBlockInteractions++;
                // THE GROUND TRUTH OF THE WORLD-CHANGE CENSUS, and the only line in the codebase that is one.
                // Every other placement record in this project is written where a caller DECIDED to place; this is
                // written where the client actually issued the interaction. The difference is the whole point: a
                // decision that never became a click and a click nobody decided are opposite bugs, and until now
                // both looked identical from the log. If this reports src=UNATTRIBUTED, some path forced a
                // right-click without declaring itself -- see BuildTrace.intendWorldChange.
                BuildTrace.worldChanged(
                        blockHit.getBlockPos().getX(), blockHit.getBlockPos().getY(), blockHit.getBlockPos().getZ(),
                        "face=" + blockHit.getDirection()
                                + " item=" + BuiltInRegistries.ITEM.getKey(usedItem).getPath()
                                + " hand=" + (hand == InteractionHand.MAIN_HAND ? "main" : "off"));
                lastSuccessfulBlockInteraction = new SuccessfulBlockInteraction(
                        successfulBlockInteractions,
                        blockHit.getBlockPos(),
                        blockHit.getDirection(),
                        hand,
                        selectedSlot,
                        usedItem
                );
                ctx.player().swing(hand);
                return;
            }
            if (expected != null) {
                return; // a committed placement must never fall through to offhand or generic item use
            }
            if (!ctx.player().getItemInHand(hand).isEmpty() && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand) == InteractionResult.SUCCESS) {
                return;
            }
        }
    }

    /** Monotonic execution acknowledgement. Callers can distinguish a requested click from one the controller
     *  actually accepted after cooldown, hand-busy, and survival-consumption gates. */
    public long getSuccessfulBlockInteractions() {
        return successfulBlockInteractions;
    }

    public SuccessfulBlockInteraction getLastSuccessfulBlockInteraction() {
        return lastSuccessfulBlockInteraction;
    }

    /** Registers the exact placement that the next forced right-click is allowed to execute. */
    public void expectMainHandPlacement(BlockPos support, Direction face, BlockPos target,
                                        int selectedSlot, Item item) {
        expectMainHandPlacement(support, face, target, selectedSlot, item, null);
    }

    /**
     * Registers a placement AND the last-moment condition it must still satisfy when the click actually goes out.
     *
     * <p>WARUM DIE FUENF FELDER NICHT REICHEN, und das ist eine gemessene Luecke, kein Vorsichtsprinzip: der erste
     * scharfe P5-Abbruch auf etz-basalt lautete "wanted sticky_piston, got sticky_piston[facing=east]" an
     * 74,-59,108. Zielblock, Klickflaeche, Slot und Gegenstand stimmten alle -- {@link ExpectedPlacement#matches}
     * haette also zugestimmt. Die ORIENTIERUNG stimmte nicht, und sie steht in keinem dieser fuenf Felder: vanilla
     * leitet sie fuer Kolben, Beobachter und die ganze gerichtete Familie aus der BLICKRICHTUNG ab, nicht aus der
     * getroffenen Flaeche.
     *
     * <p>Dazwischen liegt aber ein halber Tick. Der Bauer prueft die Blickrichtung, meldet den Klick an und setzt
     * CLICK_RIGHT; danach dreht der Look-Prozessor im selben Tick weiter (humanisierte Drehung, gedeckelte
     * Platzier-Kurve), und erst DANN feuert {@link #tick}. Liegt die Blickachse dabei nahe einer 45-Grad-Grenze,
     * kippt die Richtung -- und der Block landet gedreht, obwohl jede einzelne Pruefung davor ja gesagt hat.
     *
     * <p>Der Aufrufer uebergibt deshalb seine EIGENE Pruefung als Bedingung, die unmittelbar vor
     * {@code processRightClickBlock} noch einmal laeuft. Faellt sie durch, geht der Klick diesen Tick nicht raus;
     * der Bauer meldet ihn im naechsten wieder an, wenn der Blick angekommen ist. Ein nicht abgeschickter Klick
     * kostet einen Tick. Ein falsch gedrehter Block kostet nach P5 den ganzen Bau.
     *
     * @param stillCorrect evaluated on the main thread immediately before the interaction; null means "no extra
     *                     condition", which is what every non-builder caller wants
     */
    public void expectMainHandPlacement(BlockPos support, Direction face, BlockPos target,
                                        int selectedSlot, Item item,
                                        java.util.function.BooleanSupplier stillCorrect) {
        expectedPlacement = new ExpectedPlacement(
                support.immutable(), face, target.immutable(), selectedSlot, item, false, stillCorrect);
    }

    /** How many clicks were held back because the aim had drifted off the simulated orientation. */
    public long getOrientationHoldbacks() {
        return orientationHoldbacks;
    }

    /** How many UNARMED clicks were refused because they would have landed a directional template block. */
    public long getUnguardedRefusals() {
        return unguardedRefusals;
    }

    private long orientationHoldbacks;
    private long unguardedRefusals;

    /** V3's committed placement: same exact click guard, with an unsampled cooldown for replayable execution. */
    public void expectDeterministicMainHandPlacement(BlockPos support, Direction face, BlockPos target,
                                                     int selectedSlot, Item item) {
        expectedPlacement = new ExpectedPlacement(
                support.immutable(), face, target.immutable(), selectedSlot, item, true);
    }

    public void clearExpectedPlacement() {
        expectedPlacement = null;
    }

    private static final class ExpectedPlacement {

        private final BlockPos support;
        private final Direction face;
        private final BlockPos target;
        private final int selectedSlot;
        private final Item item;
        private final boolean deterministic;
        /** Last-moment condition, evaluated just before the interaction. Null means unconditional. */
        private final java.util.function.BooleanSupplier stillCorrect;

        private ExpectedPlacement(BlockPos support, Direction face, BlockPos target,
                                  int selectedSlot, Item item, boolean deterministic) {
            this(support, face, target, selectedSlot, item, deterministic, null);
        }

        private ExpectedPlacement(BlockPos support, Direction face, BlockPos target,
                                  int selectedSlot, Item item, boolean deterministic,
                                  java.util.function.BooleanSupplier stillCorrect) {
            this.support = support;
            this.face = face;
            this.target = target;
            this.selectedSlot = selectedSlot;
            this.item = item;
            this.deterministic = deterministic;
            this.stillCorrect = stillCorrect;
        }

        private boolean matches(BlockHitResult hit, int actualSlot, Item actualItem) {
            return actualSlot == selectedSlot
                    && actualItem == item
                    && hit.getBlockPos().equals(support)
                    && hit.getDirection() == face
                    && hit.getBlockPos().relative(hit.getDirection()).equals(target);
        }
    }

    /** Immutable controller acknowledgement for the exact block-use that actually returned SUCCESS. */
    public static final class SuccessfulBlockInteraction {

        private final long serial;
        private final BlockPos support;
        private final Direction face;
        private final InteractionHand hand;
        private final int selectedSlot;
        private final Item item;

        SuccessfulBlockInteraction(long serial, BlockPos support, Direction face,
                                   InteractionHand hand, int selectedSlot, Item item) {
            this.serial = serial;
            this.support = support.immutable();
            this.face = face;
            this.hand = hand;
            this.selectedSlot = selectedSlot;
            this.item = item;
        }

        public long getSerial() {
            return serial;
        }

        public boolean matchesMainHandPlacement(BlockPos expectedSupport, Direction expectedFace,
                                                BlockPos expectedTarget, int expectedSlot, Item expectedItem) {
            return hand == InteractionHand.MAIN_HAND
                    && selectedSlot == expectedSlot
                    && item == expectedItem
                    && support.equals(expectedSupport)
                    && face == expectedFace
                    && support.relative(face).equals(expectedTarget);
        }
    }
}
