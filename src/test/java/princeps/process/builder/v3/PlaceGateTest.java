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

package princeps.process.builder.v3;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;
import princeps.api.utils.input.Input;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The click gate and the fine approach, headless.
 *
 * <h2>What these tests can and cannot reach</h2>
 * <p>{@link PlaceGate} and {@link FineApproach} are pure by construction, which is the entire reason the executor was
 * split the way it was: the two decisions that turn a proven plan into an irreversible click — "does this exact ray
 * produce the state we proved" and "which keys walk me to the point I proved it from" — are functions of values, so
 * they run under JUnit against real vanilla block states after {@code Bootstrap.bootStrap()}.
 *
 * <p><b>What is NOT covered here, plainly.</b> {@link CellExecutor} itself has no tests and cannot have any in this
 * repository as it stands. It reaches {@code Princeps}, {@code IPlayerContext}, {@code InputOverrideHandler},
 * {@code LookBehavior}, {@code PathingBehavior} and {@code BlockPlaceHelper}, and there is no fake world, no fake
 * level, no fake player and no mocking framework on the test classpath — {@code Bootstrap.bootStrap()} gives
 * registries and nothing else. So the state machine's transitions, the two-phase movement handoff, the same-tick
 * arm-and-click, the confirmation hold and the layer gate are verified by the in-game bench and by reading the trace,
 * not here. Building a headless client harness to close that gap is a real work package and it is not this one; the
 * mitigation chosen instead was to move every decision that could be pure into a class that is.
 */
public class PlaceGateTest {

    /**
     * Registries, plus the component binding that makes {@link ItemStack} constructible.
     *
     * <p>{@code Bootstrap.bootStrap()} alone is not enough: since the data-component rework every stack reads
     * {@code Holder.Reference.components()} in its constructor, and that binding happens during datapack load rather
     * than during bootstrap. The failure is a bare {@code NullPointerException: Components not bound yet} three
     * frames inside vanilla. Same fixture as {@code HotbarScheduleTest}, for the same reason.
     */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    /** The cell being filled. */
    private static final BlockPos CELL = new BlockPos(0, 65, 0);

    /** The neighbour whose upward face is clicked — a plain stone floor under the cell. */
    private static final BlockPos AGAINST = new BlockPos(0, 64, 0);

    private static final Direction FACE = Direction.UP;

    /** A point on that face, off centre, the way an optimised aim point is. */
    private static final Vec3 AIM = new Vec3(0.35D, 65.0D, 0.62D);

    /** The rotation the plan proved. Irrelevant to a cobblestone's landed state, which is the point of using one:
     *  these tests are about the gate's decisions, not about the oracle's geometry. */
    private static final Rotation LOOK = new Rotation(140.0F, 52.0F);

    private static final int SLOT = 3;

    // ------------------------------------------------------------------------------------------- the happy path

    @Test
    public void fires_whenTheLiveRayReproducesTheProvenResult() {
        assertEquals(PlaceGate.Verdict.FIRE, evaluate(plan(), live().build()));
    }

    @Test
    public void fire_isTheOnlyVerdictThatFires() {
        for (PlaceGate.Verdict verdict : PlaceGate.Verdict.values()) {
            assertEquals(verdict.name(), verdict == PlaceGate.Verdict.FIRE, verdict.fires());
        }
    }

    // ---------------------------------------------------------------------------- the ray is not there yet (WAIT)

    @Test
    public void noHit_whileTheCrosshairIsOnNothing() {
        PlaceGate.Verdict verdict = evaluate(plan(), live().hit(null, null, null).build());
        assertEquals(PlaceGate.Verdict.NO_HIT, verdict);
        assertEquals(PlaceGate.Response.WAIT, verdict.response());
    }

    @Test
    public void wrongBlock_isAWaitAndNotADivergence() {
        // Mid-turn the crosshair rests on the neighbour one over. That is the aim curve doing exactly what it was
        // asked to do, and treating it as a contradiction would re-plan on every tick of every turn.
        PlaceGate.Verdict verdict = evaluate(plan(),
                live().hit(new BlockPos(1, 64, 0), FACE, new Vec3(1.5D, 65.0D, 0.5D)).build());
        assertEquals(PlaceGate.Verdict.WRONG_BLOCK, verdict);
        assertEquals(PlaceGate.Response.WAIT, verdict.response());
    }

    @Test
    public void wrongFace_isAWait() {
        PlaceGate.Verdict verdict = evaluate(plan(),
                live().hit(AGAINST, Direction.NORTH, new Vec3(0.5D, 64.5D, 0.0D)).build());
        assertEquals(PlaceGate.Verdict.WRONG_FACE, verdict);
        assertEquals(PlaceGate.Response.WAIT, verdict.response());
    }

    // ------------------------------------------------------------------------------- the hotbar moved (RE_EQUIP)

    @Test
    public void wrongSlot_sendsTheExecutorBackToEquipRatherThanClicking() {
        // The trap this exists for: BlockPlaceHelper.matches compares the selected slot, and a click that fails it is
        // discarded with no log line, no counter and a full cooldown charged anyway.
        PlaceGate.Verdict verdict = evaluate(plan(), live().selectedSlot(SLOT + 1).build());
        assertEquals(PlaceGate.Verdict.WRONG_SLOT, verdict);
        assertEquals(PlaceGate.Response.RE_EQUIP, verdict.response());
    }

    @Test
    public void wrongItem_sendsTheExecutorBackToEquip() {
        // InventoryBehavior re-asserts slots 0 and 8 every single tick; an item that moved between the plan and the
        // click voids the click, and item IDENTITY is what is compared.
        PlaceGate.Verdict verdict = evaluate(plan(), live().held(new ItemStack(Items.DIRT)).build());
        assertEquals(PlaceGate.Verdict.WRONG_ITEM, verdict);
        assertEquals(PlaceGate.Response.RE_EQUIP, verdict.response());
    }

    @Test
    public void emptyHand_readsAsWrongItemAndNotAsACrash() {
        assertEquals(PlaceGate.Verdict.WRONG_ITEM, evaluate(plan(), live().held(ItemStack.EMPTY).build()));
    }

    // ------------------------------------------------------------------------------------ the client is busy (WAIT)

    @Test
    public void throttled_isCheckedLastSoTheVerdictCarriesThatEverythingElseWasReady() {
        PlaceGate.Verdict verdict = evaluate(plan(), live().throttled(true).build());
        assertEquals(PlaceGate.Verdict.THROTTLED, verdict);
        assertEquals(PlaceGate.Response.WAIT, verdict.response());
    }

    @Test
    public void throttled_doesNotMaskTheRealReason() {
        // A throttled tick whose aim is also wrong must report the aim. Reporting THROTTLED there is how a trace ends
        // up saying "waiting on the cooldown" for sixty ticks of a turn that never arrived.
        assertEquals(PlaceGate.Verdict.NO_HIT,
                evaluate(plan(), live().throttled(true).hit(null, null, null).build()));
    }

    @Test
    public void consuming_neverFires_becauseTheHelperIsTickedFalseAndTheCommitIsDestroyed() {
        PlaceGate.Verdict verdict = evaluate(plan(), live().consuming(true).build());
        assertEquals(PlaceGate.Verdict.CONSUMING, verdict);
        assertEquals(PlaceGate.Response.WAIT, verdict.response());
    }

    @Test
    public void handsBusy_neverFires() {
        assertEquals(PlaceGate.Verdict.HANDS_BUSY, evaluate(plan(), live().handsBusy(true).build()));
    }

    @Test
    public void entityInTheCell_isAWait_becauseVanillaRefusesAndTheMobMayWalkAway() {
        PlaceGate.Verdict verdict = evaluate(plan(), live().entityClear(false).build());
        assertEquals(PlaceGate.Verdict.ENTITY_IN_CELL, verdict);
        assertEquals(PlaceGate.Response.WAIT, verdict.response());
    }

    // ----------------------------------------------------------------------------- the world contradicts (DIVERGE)

    @Test
    public void occupiedCell_divergesAndIsNotReportedAsAWrongResult() {
        Cells cells = floor();
        cells.set(CELL, Blocks.STONE.defaultBlockState());
        PlaceGate.Verdict verdict = PlaceGate.evaluate(plan(), SLOT, live().build(), oracle(), world(cells));
        assertEquals(PlaceGate.Verdict.CELL_OCCUPIED, verdict);
        assertEquals(PlaceGate.Response.DIVERGE, verdict.response());
    }

    @Test
    public void wrongResult_whenTheLiveRayWouldLandSomethingElse() {
        // The gate's whole reason to exist. The plan proved a TOP slab — reachable only by clicking high on a side
        // face or downward — and the live ray is the same block, same face, but the UP face, which vanilla resolves
        // to BOTTOM. Angle equality would have said nothing at all about this; result equivalence refuses it.
        BlockState topSlab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP);
        PlacementSolution slab = new PlacementSolution(CELL, topSlab, new BlockPos(1, 65, 0),
                new Vec3(1.5D, 65.0D, 0.5D), AGAINST, FACE, AIM, LOOK, 0.4D, topSlab, Items.STONE_SLAB);
        PlaceGate.Verdict verdict = PlaceGate.evaluate(slab, SLOT,
                live().held(new ItemStack(Items.STONE_SLAB)).build(), oracle(), world(floor()));
        assertEquals(PlaceGate.Verdict.WRONG_RESULT, verdict);
        assertEquals(PlaceGate.Response.DIVERGE, verdict.response());
    }

    @Test
    public void productionGateUsesTheInjectedVanillaSimulatorAsAuthority() {
        PlacementSolution plan = plan();
        PlaceGate.Live live = live().build();

        assertEquals(PlaceGate.Verdict.WRONG_RESULT,
                PlaceGate.evaluate(plan, SLOT, live, V3Settings.defaults(), world(floor()),
                        (ignoredPlan, ignoredLive) -> Blocks.DIRT.defaultBlockState()));
        assertEquals(PlaceGate.Verdict.FIRE,
                PlaceGate.evaluate(plan, SLOT, live, V3Settings.defaults(), world(floor()),
                        (ignoredPlan, ignoredLive) -> plan.predicted()));
    }

    /** Placement confirms the predicted base state; ACT owns the later clicks that reach the schematic state. */
    @Test
    public void interactionPlannedStateFiresOnThePredictedBaseState() {
        BlockState predicted = Blocks.REPEATER.defaultBlockState();
        BlockState desired = predicted.setValue(RepeaterBlock.DELAY, 3);
        PlacementSolution repeater = new PlacementSolution(CELL, desired, new BlockPos(1, 65, 0),
                new Vec3(1.5D, 65.0D, 0.5D), AGAINST, FACE, AIM, LOOK, 0.4D, predicted, Items.REPEATER);
        PlaceGate.Live live = live().held(new ItemStack(Items.REPEATER)).build();

        assertEquals(PlaceGate.Verdict.FIRE,
                PlaceGate.evaluate(repeater, SLOT, live, V3Settings.defaults(), world(floor()),
                        (ignoredPlan, ignoredLive) -> predicted));
    }

    @Test
    public void wrongItem_whenTheHeldStackCannotPlaceAtAll() {
        // A non-block item simulates to nothing. Refused rather than clicked: a click with a bucket in hand on a
        // cell the plan wanted cobblestone in is not a placement, it is a bug that empties a bucket.
        assertEquals(PlaceGate.Verdict.WRONG_ITEM,
                evaluate(plan(), live().held(new ItemStack(Items.WATER_BUCKET)).build()));
    }

    @Test
    public void wrongCell_divergesRatherThanClickingIntoAPlanThatContradictsItself() {
        // against + face must open onto cell; ExpectedPlacement checks exactly this and discards the click silently
        // when it fails, so the executor has to name it instead.
        PlacementSolution inconsistent = new PlacementSolution(new BlockPos(5, 65, 5), cobblestone(),
                new BlockPos(1, 65, 0), new Vec3(1.5D, 65.0D, 0.5D), AGAINST, FACE, AIM, LOOK, 0.4D,
                cobblestone(), Items.COBBLESTONE);
        PlaceGate.Verdict verdict = PlaceGate.evaluate(inconsistent, SLOT, live().build(), oracle(), world(floor()));
        assertEquals(PlaceGate.Verdict.WRONG_CELL, verdict);
        assertEquals(PlaceGate.Response.DIVERGE, verdict.response());
    }

    // ------------------------------------------------------------------------------------------- determinism (D2)

    @Test
    public void sameInputsGiveTheSameVerdictEveryTime() {
        PlacementSolution plan = plan();
        PlaceGate.Live live = live().build();
        PlaceGate.Verdict first = PlaceGate.evaluate(plan, SLOT, live, oracle(), world(floor()));
        for (int repeat = 0; repeat < 50; repeat++) {
            assertEquals(first, PlaceGate.evaluate(plan, SLOT, live, oracle(), world(floor())));
        }
    }

    // ------------------------------------------------------------------------------------------- FineApproach

    @Test
    public void arrival_isEightCentimetresAndIsMeasuredHorizontally() {
        Vec3 target = new Vec3(10.30D, 64.0D, 10.70D);
        assertTrue(FineApproach.arrived(new Vec3(10.34D, 64.0D, 10.73D), target));
        assertFalse(FineApproach.arrived(new Vec3(10.45D, 64.0D, 10.70D), target));
        // The body bobs vertically while it lands; a vertical term would report "not arrived" for a bot standing
        // exactly on its point.
        assertTrue(FineApproach.arrived(new Vec3(10.30D, 63.4D, 10.70D), target));
    }

    @Test
    public void alreadyThere_choosesNoKeysAtAll() {
        Vec3 point = new Vec3(4.5D, 64.0D, 4.5D);
        assertFalse(FineApproach.chooseKeys(0.0F, point, point).any());
    }

    @Test
    public void facingSouth_theTargetAheadIsForward() {
        // Yaw 0 faces +Z. A target at +Z is straight ahead.
        FineApproach.Keys keys = FineApproach.chooseKeys(0.0F, new Vec3(0.5D, 64.0D, 0.5D),
                new Vec3(0.5D, 64.0D, 1.2D));
        assertEquals(Input.MOVE_FORWARD, keys.first());
        assertNull(keys.second());
    }

    @Test
    public void facingSouth_theTargetBehindIsBack() {
        FineApproach.Keys keys = FineApproach.chooseKeys(0.0F, new Vec3(0.5D, 64.0D, 0.5D),
                new Vec3(0.5D, 64.0D, -0.2D));
        assertEquals(Input.MOVE_BACK, keys.first());
    }

    /**
     * The mirrored frame, pinned.
     *
     * <p>{@code MovementOption.getOptions} is built from {@code (sin(yaw), cos(yaw))} whose FORWARD entry is that
     * exact pair, while Minecraft's real forward is {@code (-sin(yaw), cos(yaw))}. Every option carries the same flip
     * on X, so the wanted direction has to be flipped too. Flip one and not the other and the strafe keys come out
     * mirrored — the bot walks away from its point, arrives at nothing, and the only symptom anywhere is a stance
     * that times out. This test is the reason that cannot happen silently.
     */
    @Test
    public void facingSouth_theTargetToTheEastIsLeft_notRight() {
        // Facing +Z (south), east (+X) is on the player's LEFT.
        FineApproach.Keys keys = FineApproach.chooseKeys(0.0F, new Vec3(0.5D, 64.0D, 0.5D),
                new Vec3(1.2D, 64.0D, 0.5D));
        assertEquals(Input.MOVE_LEFT, keys.first());
        assertNotEquals(Input.MOVE_RIGHT, keys.first());
    }

    @Test
    public void facingSouth_theTargetToTheWestIsRight() {
        FineApproach.Keys keys = FineApproach.chooseKeys(0.0F, new Vec3(0.5D, 64.0D, 0.5D),
                new Vec3(-0.2D, 64.0D, 0.5D));
        assertEquals(Input.MOVE_RIGHT, keys.first());
    }

    @Test
    public void facingWest_theSameWorldDirectionPicksADifferentKey() {
        // Yaw 90 faces -X (west). A target at +Z, which was straight FORWARD while facing south, is now on the
        // player's LEFT — which is what proves the choice is made in the body frame and not in world coordinates.
        FineApproach.Keys keys = FineApproach.chooseKeys(90.0F, new Vec3(0.5D, 64.0D, 0.5D),
                new Vec3(0.5D, 64.0D, 1.2D));
        assertEquals(Input.MOVE_LEFT, keys.first());
        assertNotEquals(Input.MOVE_FORWARD, keys.first());
    }

    @Test
    public void aDiagonalTargetPicksTwoKeys() {
        FineApproach.Keys keys = FineApproach.chooseKeys(0.0F, new Vec3(0.5D, 64.0D, 0.5D),
                new Vec3(1.2D, 64.0D, 1.2D));
        assertEquals(Input.MOVE_FORWARD, keys.first());
        assertEquals(Input.MOVE_LEFT, keys.second());
    }

    @Test
    public void keyChoiceIsDeterministic_includingOnATie() {
        // A target exactly between two options must resolve the same way on every call, in every run: the whole
        // reproducibility rule rests on there being no sampling anywhere in the executor.
        Vec3 from = new Vec3(0.5D, 64.0D, 0.5D);
        Vec3 to = new Vec3(0.5D + Math.cos(Math.PI / 8), 64.0D, 0.5D + Math.sin(Math.PI / 8));
        FineApproach.Keys first = FineApproach.chooseKeys(0.0F, from, to);
        for (int repeat = 0; repeat < 100; repeat++) {
            assertEquals(first, FineApproach.chooseKeys(0.0F, from, to));
        }
    }

    @Test
    public void settled_isAboutHorizontalMomentumOnly() {
        assertTrue(FineApproach.settled(new Vec3(0.0D, -0.6D, 0.0D)));
        assertFalse(FineApproach.settled(new Vec3(0.05D, 0.0D, 0.0D)));
    }

    // ------------------------------------------------------------------------------------------------- fixtures

    private static BlockState cobblestone() {
        return Blocks.COBBLESTONE.defaultBlockState();
    }

    /** The plan under test: cobblestone into {@link #CELL}, clicked on the top of the stone below it. */
    private static PlacementSolution plan() {
        return new PlacementSolution(CELL, cobblestone(), new BlockPos(1, 65, 0), new Vec3(1.5D, 65.0D, 0.5D),
                AGAINST, FACE, AIM, LOOK, 0.4D, cobblestone(), Items.COBBLESTONE);
    }

    private static PlaceGate.Verdict evaluate(PlacementSolution plan, PlaceGate.Live live) {
        return PlaceGate.evaluate(plan, SLOT, live, oracle(), world(floor()));
    }

    private static PlacementOracle oracle() {
        return new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
    }

    /** Stone under the cell and under the stance; everything else air. */
    private static Cells floor() {
        Cells cells = new Cells();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                cells.set(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState());
            }
        }
        return cells;
    }

    private static PredictedWorld world(Cells cells) {
        return PredictedWorld.capture(cells::at, (x, z) -> true, new Vec3i(-6, 58, -6), new Vec3i(6, 72, 6), 0);
    }

    /** A hand-built neighbourhood. Everything not set is air. */
    private static final class Cells {

        private final Map<Long, BlockState> states = new HashMap<>();

        void set(BlockPos pos, BlockState state) {
            this.states.put(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()), state);
        }

        BlockState at(int x, int y, int z) {
            return this.states.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }
    }

    /** A mutable builder for {@link PlaceGate.Live}, so each test names only the one field it is about. Defaults are
     *  the happy path: the ray is on the plan's face, the right item is in the right slot, nothing is busy. */
    private static LiveBuilder live() {
        return new LiveBuilder();
    }

    private static final class LiveBuilder {

        private BlockPos hitPos = AGAINST;
        private Direction hitFace = FACE;
        private Vec3 hitLocation = AIM;
        private Rotation rotation = LOOK;
        private ItemStack held = new ItemStack(Items.COBBLESTONE);
        private int selectedSlot = SLOT;
        private boolean sneaking = true;
        private boolean throttled;
        private boolean consuming;
        private boolean handsBusy;
        private boolean entityClear = true;

        LiveBuilder hit(BlockPos pos, Direction face, Vec3 location) {
            this.hitPos = pos;
            this.hitFace = face;
            this.hitLocation = location;
            return this;
        }

        LiveBuilder held(ItemStack stack) {
            this.held = stack;
            return this;
        }

        LiveBuilder selectedSlot(int slot) {
            this.selectedSlot = slot;
            return this;
        }

        LiveBuilder throttled(boolean value) {
            this.throttled = value;
            return this;
        }

        LiveBuilder consuming(boolean value) {
            this.consuming = value;
            return this;
        }

        LiveBuilder handsBusy(boolean value) {
            this.handsBusy = value;
            return this;
        }

        LiveBuilder entityClear(boolean value) {
            this.entityClear = value;
            return this;
        }

        PlaceGate.Live build() {
            return new PlaceGate.Live(this.hitPos, this.hitFace, this.hitLocation, this.rotation, this.held,
                    this.selectedSlot, this.sneaking, this.throttled, this.consuming, this.handsBusy,
                    this.entityClear);
        }
    }
}
