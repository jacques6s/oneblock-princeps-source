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

import princeps.api.utils.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One entry of the frozen order — the unit the executor consumes and the unit the plan is made of.
 *
 * <p>A cell compiles to a LIST of these, not to one, and it is not done until every one of them is confirmed. That is
 * what makes a repeater's delay or a sign's text structurally impossible to lose: they sit in the same order as the
 * placement rather than in a follow-up pass that can be abandoned.
 *
 * <h2>Why every action carries its own sneak state and hand slot</h2>
 * <p>Because vanilla's answer to a right-click depends on both, and the two requirements contradict each other:
 *
 * <ul>
 *   <li>A crouched right-click on a repeater does not open or step it — it PLACES the block in the hand. So
 *       {@link Interact} must release sneak and hold an empty or non-placing slot.</li>
 *   <li>Sneak forces a chest to SINGLE, destroying a planned double chest. So a chest {@link Place} must NOT
 *       sneak, while the placements around it do.</li>
 * </ul>
 *
 * <p>A single global sneak state for the whole build gets one of those two wrong every time, silently. Carrying the
 * pair per action costs two fields and removes the class of bug entirely.
 *
 * <p>Both rules are enforced in the compact constructors rather than written down in a comment: an {@code Interact}
 * with {@code sneak == true} and a {@code Place} of a paired chest with {@code sneak == true} do not construct. The
 * planner then fails at plan time with a sentence, which is a hundred times cheaper than a run that quietly builds
 * two single chests and a repeater at the wrong delay and only shows it in the bench audit three hours later.
 */
public sealed interface BuildAction
        permits BuildAction.Place, BuildAction.JumpPlace, BuildAction.Break, BuildAction.PlaceScaffold, BuildAction.RemoveScaffold,
        BuildAction.Interact, BuildAction.WriteSign, BuildAction.FillFluid, BuildAction.SwapHotbar {

    /** Discriminator, for reports, traces and counters that would otherwise pattern-match. */
    enum Kind {
        PLACE, JUMP_PLACE, BREAK, PLACE_SCAFFOLD, REMOVE_SCAFFOLD, INTERACT, WRITE_SIGN, FILL_FLUID,
        SWAP_HOTBAR
    }

    /**
     * The hand slot of an action whose material has not been scheduled yet.
     *
     * <p>The two-stage slot resolution of the material gap, spelled as a value. {@link PlacementOracle} proves a cell
     * placeable when the item exists anywhere in the 36-slot inventory, and which hotbar slot the click comes out of
     * is decided by {@link HotbarSchedule#belady} over the whole frozen order — so between those two moments the
     * honest answer is "not decided", and this is it. {@link HotbarSchedule#weave} replaces every one of them and
     * refuses to hand back an order that still carries one, so it can never reach the executor.
     *
     * <p>A value {@link #checkHandSlot} admits and nothing else does. Zero would have been a hotbar slot — the
     * pickaxe's — and an action silently clicking with the pickaxe in hand is the failure mode this exists to make
     * impossible.
     */
    int UNASSIGNED_SLOT = -1;

    Kind kind();

    /** The cell this action is about; null only for {@link SwapHotbar}, which is about the inventory. */
    BlockPos cell();

    /** Whether SNEAK is held while this action's click goes out. Read the class javadoc before changing one. */
    boolean sneak();

    /** The hotbar slot selected for this action. Selecting a slot is free — it is a number key, no throttle, no
     *  stationarity requirement; only FETCHING an item onto the hotbar costs, and that is {@link SwapHotbar}. */
    int handSlot();

    /** The layer this action belongs to. The executor asserts the hard layer bound against the LIVE world using it:
     *  layer L+1 does not start while any schematic cell in L is wrong or any scaffold ledger entry for L is open. */
    int layer();

    /**
     * How far the body may stand from this action's approach point and still be somewhere the click was proven from,
     * in blocks, measured horizontally.
     *
     * <p>The tolerance belongs to the ACTION and not to the engine — plan 19, and the answer to open question F2.
     * {@link FineApproach} used to hold one number for the whole build, which is a global answer to a question that
     * has none: the arrival slack a click can afford is a property of that click's own sight line, and the planner is
     * the only thing that can measure it. An action whose ray clears the corner of a neighbouring cell by two
     * centimetres gets two centimetres of slack; the open ones keep {@link FineApproach#TOLERANCE} and pay nothing
     * for it.
     *
     * <p>The default is the widest value, which is correct for every action kind that is not a placement: a break, a
     * scaffold removal, an interaction and a bucket use are all aimed at a block that is already there and confirmed
     * against the live world afterwards, so none of them can land a wrongly oriented block. Only {@link Place} and
     * {@link PlaceScaffold} carry a proof narrow enough to be worth tightening, and they override this.
     */
    default double approachTolerance() {
        return FineApproach.TOLERANCE;
    }

    /** One line for {@code run/plan/<runid>-plan.txt}. The frozen order on disk is what turns "why is this action
     *  here" from a reconstruction into a grep. */
    String describe();

    /**
     * Place the block the schematic asks for.
     *
     * @param solution the proven click geometry, item included; the {@link #handSlot} that item will be selected from
     *                 is written in by {@link HotbarSchedule#weave} and is {@link #UNASSIGNED_SLOT} until then
     */
    record Place(PlacementSolution solution, boolean sneak, int handSlot, int layer) implements BuildAction {

        public Place {
            Objects.requireNonNull(solution, "solution");
            checkHandSlot(handSlot);
            // Sneak forces ChestBlock.getStateForPlacement to SINGLE. Both halves of a planned double chest carry
            // type=LEFT/RIGHT as their desired state, so a crouched click on either one silently builds two single
            // chests that pass every "is a chest there" check and fail the audit.
            if (sneak && solution.desired() != null
                    && solution.desired().hasProperty(BlockStateProperties.CHEST_TYPE)
                    && solution.desired().getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
                throw new IllegalArgumentException("a paired chest must be placed standing, sneak forces SINGLE: "
                        + describePos(solution.cell()) + " " + describeState(solution.desired()));
            }
        }

        @Override
        public Kind kind() {
            return Kind.PLACE;
        }

        @Override
        public BlockPos cell() {
            return this.solution.cell();
        }

        /** Straight off the proof: the oracle measured it, nothing between here and the executor may widen it. */
        @Override
        public double approachTolerance() {
            return this.solution.approachTolerance();
        }

        @Override
        public String describe() {
            return line("PLACE", describePos(cell()) + "  " + describeSolution(this.solution)
                    + "  " + describePosture(this.sneak, this.handSlot));
        }
    }

    /**
     * Fill a cell by standing IN it, jumping, and clicking the block below on the way up.
     *
     * <p>The only action in the engine whose click goes out while the body is in the air, and it exists for the only
     * placement a settled body cannot make: a block whose landed facing is UP. Vanilla derives that facing from the
     * placer's look, so it takes a downward look, so it takes being above the cell — and in the layer currently being
     * built there is nothing above to stand on. Pillaring is how a player solves this without thinking about it.
     *
     * <p>Carries no {@code sneak}: a crouched click on the block below would place against the same face just the
     * same, but crouching is the one posture that changes what several blocks land as, and this action has no reason
     * to want it. It is fixed to standing so that no future change can quietly make it a variable.
     *
     * <p>The solution's stance IS its cell, which no other action may say, and that is the whole shape of the thing:
     * the body has to be in the space it is about to fill, and out of it again before the click.
     */
    record JumpPlace(PlacementSolution solution, int handSlot, int layer) implements BuildAction {

        public JumpPlace {
            Objects.requireNonNull(solution, "solution");
            checkHandSlot(handSlot);
            if (!solution.cell().equals(solution.stance())) {
                throw new IllegalArgumentException("a jump-place stands in the cell it fills, and this one stands at "
                        + describePos(solution.stance()) + " to fill " + describePos(solution.cell()));
            }
            if (solution.face() != net.minecraft.core.Direction.UP) {
                throw new IllegalArgumentException("a jump-place clicks the top face of the block below, not "
                        + solution.face() + " at " + describePos(solution.cell()));
            }
        }

        @Override
        public Kind kind() {
            return Kind.JUMP_PLACE;
        }

        @Override
        public BlockPos cell() {
            return this.solution.cell();
        }

        /** Never. The click is the same click a standing player makes when pillaring. */
        @Override
        public boolean sneak() {
            return false;
        }

        @Override
        public double approachTolerance() {
            return this.solution.approachTolerance();
        }

        @Override
        public String describe() {
            return line("JUMP_PLACE", describePos(cell()) + "  " + describeSolution(this.solution)
                    + "  " + describePosture(false, this.handSlot));
        }
    }

    /**
     * Break a block that stands in the way — foreign terrain, a leftover from an earlier run, a wrongly landed
     * orientation, or a cell a later action needs empty.
     *
     * <p>Equal in rank to {@link Place} and driven by the same machinery: stance, approach, aim curve, gate (the live
     * ray hits {@code cell}), a held {@code CLICK_LEFT}, and the server acknowledgement. Two constraints the executor
     * must respect and neither is negotiable: never break and place in the same tick, because {@code CLICK_LEFT}
     * clears {@code CLICK_RIGHT} before the helpers tick and the pending placement commit is eaten with it; and never
     * break the same position twice inside four seconds, because {@code BlockBreakHelper} blacklists it PERMANENTLY
     * and nothing in the codebase ever clears that blacklist.
     *
     * <p>Scope, as a design rule: the engine does not clear every cell the schematic calls air. It breaks only what is
     * in the way of an action. Clearing a volume stays an explicit command, never a side effect.
     *
     * @param expected what the planner believes stands there; a divergence check, not a wish
     * @param drops    false when the block yields nothing (glass), so the material ledger can book the replacement in
     *                 the plan report instead of discovering the shortfall mid-run
     */
    record Break(BlockPos cell, BlockPos stance, Vec3 approach, Vec3 aimPoint, Rotation rotation, Direction face,
                 BlockState expected, boolean drops, boolean sneak, int handSlot, int layer) implements BuildAction {

        public Break {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(stance, "stance");
            Objects.requireNonNull(approach, "approach");
            Objects.requireNonNull(aimPoint, "aimPoint");
            Objects.requireNonNull(rotation, "rotation");
            checkHandSlot(handSlot);
        }

        @Override
        public Kind kind() {
            return Kind.BREAK;
        }

        @Override
        public String describe() {
            return line("BREAK", describePos(this.cell) + "  " + describeState(this.expected)
                    + "  face " + describeFace(this.face)
                    + "  " + describeStance(this.stance, this.approach, this.aimPoint, this.rotation)
                    + "  " + describePosture(this.sneak, this.handSlot)
                    // The ledger has to know at plan time; a break that yields nothing is a material need, not a loss
                    // to be discovered when the stack runs out two thousand cells later.
                    + (this.drops ? "  drops" : "  no drop"));
        }
    }

    /**
     * Place a helper block that is not part of the schematic.
     *
     * <p>Under the hard layer rule this is the ONLY way to solve a cell whose click face belongs to a layer that is
     * not allowed yet — so it is a planned action type, decided in the dry run, and never a runtime rescue. The rules
     * that keep it honest: only the reserved throwaway block, whose type appears in no schematic cell (using a
     * schematic block as scaffold is where V2's wrongly rotated pistons in the finished world came from); allowed to
     * occupy a schematic cell only when that cell belongs to a LATER layer; never used as structural support for
     * anything that would fall when it is removed; and every one of them emits its {@link RemoveScaffold} into the
     * same plan.
     *
     * @param serves the cell this helper block exists for — printed in the report, so six helper blocks read as a
     *               footnote and six hundred read as the design problem they would be
     */
    record PlaceScaffold(PlacementSolution solution, BlockPos serves, boolean sneak, int handSlot, int layer)
            implements BuildAction {

        public PlaceScaffold {
            Objects.requireNonNull(solution, "solution");
            Objects.requireNonNull(serves, "serves");
            checkHandSlot(handSlot);
        }

        @Override
        public Kind kind() {
            return Kind.PLACE_SCAFFOLD;
        }

        @Override
        public BlockPos cell() {
            return this.solution.cell();
        }

        /** @see Place#approachTolerance() */
        @Override
        public double approachTolerance() {
            return this.solution.approachTolerance();
        }

        @Override
        public String describe() {
            return line("SCAFFOLD+", describePos(cell()) + "  serves " + describePos(this.serves)
                    + "  " + describeSolution(this.solution)
                    + "  " + describePosture(this.sneak, this.handSlot));
        }
    }

    /**
     * Take a helper block back out. Emitted with its {@link PlaceScaffold} and ordered in reverse placement order
     * before the layer bound, because the layer is not finished while a helper block is still standing and the bench
     * audit checks for exactly that.
     *
     * @param expected the scaffold state, so the executor can tell "already gone" from "something else is there"
     */
    record RemoveScaffold(BlockPos cell, BlockPos stance, Vec3 approach, Vec3 aimPoint, Rotation rotation,
                          BlockState expected, boolean sneak, int handSlot, int layer) implements BuildAction {

        public RemoveScaffold {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(stance, "stance");
            Objects.requireNonNull(approach, "approach");
            Objects.requireNonNull(aimPoint, "aimPoint");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(expected, "expected");
            checkHandSlot(handSlot);
        }

        @Override
        public Kind kind() {
            return Kind.REMOVE_SCAFFOLD;
        }

        @Override
        public String describe() {
            return line("SCAFFOLD-", describePos(this.cell) + "  was " + describeState(this.expected)
                    + "  " + describeStance(this.stance, this.approach, this.aimPoint, this.rotation)
                    + "  " + describePosture(this.sneak, this.handSlot));
        }
    }

    /**
     * Right-click an already-placed block N times to reach a state placement cannot set: repeater delay, comparator
     * mode, note-block note, trapdoor/door/gate open, daylight detector inverted.
     *
     * <p>Runs WITHOUT sneak and with a non-placing hand — see the class javadoc. {@code clicks} comes from
     * {@link PlacementGeometry#interactionClicks} and is exact, including the wrap-around, so the executor counts
     * confirmations rather than clicking until the state looks right.
     *
     * @param target the state to reach; the executor confirms against it and never break-and-replaces to get there
     */
    record Interact(BlockPos cell, BlockPos stance, Vec3 approach, Vec3 aimPoint, Rotation rotation, Direction face,
                    int clicks, BlockState target, boolean sneak, int handSlot, int layer) implements BuildAction {

        public Interact {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(stance, "stance");
            Objects.requireNonNull(approach, "approach");
            Objects.requireNonNull(aimPoint, "aimPoint");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(target, "target");
            checkHandSlot(handSlot);
            if (sneak) {
                throw new IllegalArgumentException("an interaction must not sneak — a crouched right-click places the "
                        + "held block instead of stepping the state: " + describePos(cell));
            }
            // interactionClicks returns 0 for "already correct" and -1 for "not interaction-fixable". Either of those
            // reaching the plan means the caller asked for an action whose confirmation can never arrive, and the
            // executor would sit in CONFIRM until the run is killed.
            if (clicks <= 0) {
                throw new IllegalArgumentException("an interaction needs at least one click, got " + clicks + " at "
                        + describePos(cell));
            }
        }

        @Override
        public Kind kind() {
            return Kind.INTERACT;
        }

        @Override
        public String describe() {
            return line("INTERACT", describePos(this.cell) + "  " + this.clicks + " clicks -> "
                    + describeState(this.target)
                    + "  face " + describeFace(this.face)
                    + "  " + describeStance(this.stance, this.approach, this.aimPoint, this.rotation)
                    + "  " + describePosture(this.sneak, this.handSlot));
        }
    }

    /**
     * Fill in a sign's text from the schematic's block-entity NBT.
     *
     * <p>Placing a sign opens the {@code SignEditScreen}, and the executor pauses aim and movement while ANY screen is
     * open — this action types the four lines and confirms. Vanilla-legitimate throughout; no packet is constructed.
     *
     * <p>Depends on the litematic reader passing tile-entity NBT through, which today it does not: the parser reads
     * only the block state palette and drops {@code TileEntities}, {@code Entities} and {@code Metadata} at parse
     * time. Small but real work, and until it lands this action can never be emitted.
     *
     * @param lines     up to four lines; shorter lists mean blank remainder lines, not "leave as is"
     * @param frontSide 26.1.2 signs have two writable sides
     */
    record WriteSign(BlockPos cell, List<String> lines, boolean frontSide, boolean sneak, int handSlot, int layer)
            implements BuildAction {

        /** What a vanilla sign holds; a fifth line is a schematic the reader misparsed, not text to type. */
        public static final int MAX_LINES = 4;

        public WriteSign {
            Objects.requireNonNull(cell, "cell");
            lines = List.copyOf(lines);
            checkHandSlot(handSlot);
            if (lines.size() > MAX_LINES) {
                throw new IllegalArgumentException("a sign side holds " + MAX_LINES + " lines, got " + lines.size()
                        + " at " + describePos(cell));
            }
        }

        @Override
        public Kind kind() {
            return Kind.WRITE_SIGN;
        }

        @Override
        public String describe() {
            StringBuilder text = new StringBuilder();
            for (String signLine : this.lines) {
                text.append(text.isEmpty() ? "" : " | ").append('"').append(signLine).append('"');
            }
            return line("SIGN", describePos(this.cell) + "  " + (this.frontSide ? "front" : "back")
                    + "  " + text
                    + "  " + describePosture(this.sneak, this.handSlot));
        }
    }

    /**
     * Empty a bucket into a cell, or use it on an already placed block to waterlog that block.
     *
     * <p>Source and waterlogging clicks have different geometry. For a source the destination is AIR and the live ray
     * hits a neighbour; for waterlogging the already placed block is both destination and live ray target. Keeping
     * {@code cell} and {@code against} separate, even when they are equal, makes that distinction explicit.
     *
     * @param cell     destination that must hold the source afterwards
     * @param against block the live ray must hit; equal to {@code cell} for waterlogging
     * @param face     exposed face of {@code against}
     * @param aimPoint point on that face
     * @param bucket   the filled bucket item; the empty one comes back into the same slot, which the material ledger
     *                 tracks
     * @param expected exact state {@code cell} must hold after the server acknowledges the bucket use
     */
    record FillFluid(BlockPos cell, BlockPos against, BlockPos stance, Vec3 approach, Vec3 aimPoint,
                     Rotation rotation, Direction face, Item bucket, BlockState expected, boolean sneak,
                     int handSlot, int layer)
            implements BuildAction {

        public FillFluid {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(against, "against");
            Objects.requireNonNull(stance, "stance");
            Objects.requireNonNull(approach, "approach");
            Objects.requireNonNull(aimPoint, "aimPoint");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(expected, "expected");
            if (!FluidPlan.validTarget(cell, against, face, bucket, expected)) {
                throw new IllegalArgumentException("fluid action at " + describePos(cell) + " cannot produce "
                        + describeState(expected) + " by clicking " + describeFace(face) + " of "
                        + describePos(against) + " with " + describeItem(bucket));
            }
            checkHandSlot(handSlot);
        }

        @Override
        public Kind kind() {
            return Kind.FILL_FLUID;
        }

        @Override
        public String describe() {
            return line("FLUID", describePos(this.cell) + "  " + describeItem(this.bucket)
                    + "  against " + describePos(this.against) + " face " + describeFace(this.face)
                    + "  -> " + describeState(this.expected)
                    + "  " + describeStance(this.stance, this.approach, this.aimPoint, this.rotation)
                    + "  " + describePosture(this.sneak, this.handSlot));
        }
    }

    /**
     * Fetch an item from the main inventory onto a hotbar slot.
     *
     * <p>The only inventory operation with a cost: selecting an existing slot is a number key, while this one is a
     * container click that is rate-limited, may require standing still, and competes with {@code InventoryBehavior}'s
     * own upkeep of slots 0 and 8. Which is why these are PLANNED rather than decided at runtime: with the order
     * frozen, the entire future of material demand is known, so eviction can use Belady — evict the item whose next
     * use is furthest away — which is provably minimal in the number of swaps and normally impossible because it
     * needs the future.
     *
     * <p>Two hazards the executor must respect: forcing {@code CLICK_RIGHT} every tick stops
     * {@code InventoryBehavior}'s move clock advancing, so the rate limiter refuses every swap forever; and merely
     * ASKING {@code stationaryForInventoryMove()} sets a pause request that halts the bot next tick.
     *
     * @param hotbarSlot         destination slot; never 0 or 8, which belong to the pickaxe and the throwaway
     * @param item               what must end up there
     * @param inventorySlotHint  where the planner expects to find it; a hint, because the real inventory moves
     */
    record SwapHotbar(int hotbarSlot, Item item, int inventorySlotHint, boolean sneak, int handSlot, int layer)
            implements BuildAction {

        public SwapHotbar {
            Objects.requireNonNull(item, "item");
            checkHandSlot(handSlot);
            // InventoryBehavior.onTick re-stocks slot 0 (pickaxe) and slot 8 (throwaway) every tick. A plan that
            // targets either one is planning against a component that will undo it, and the Belady schedule it was
            // derived from would be wrong from that swap onward.
            if (hotbarSlot < 1 || hotbarSlot > 7) {
                throw new IllegalArgumentException("material hotbar slots are 1..7, slot 0 is the pickaxe and slot 8 "
                        + "the throwaway; got " + hotbarSlot);
            }
        }

        @Override
        public Kind kind() {
            return Kind.SWAP_HOTBAR;
        }

        /** Null: this action is about the inventory, not about a cell. */
        @Override
        public BlockPos cell() {
            return null;
        }

        @Override
        public String describe() {
            return line("SWAP", "slot " + this.hotbarSlot + " <- " + describeItem(this.item)
                    + "  from inventory slot " + this.inventorySlotHint
                    + "  " + describePosture(this.sneak, this.handSlot));
        }
    }

    // ------------------------------------------------------------------------------- shared plan-file formatting

    /*
     * Every artefact this package writes — plan.txt, proof.txt, report.txt, logMechanic lines — renders coordinates,
     * states, points and rotations through the helpers below, for two reasons that are not style.
     *
     * One: BetterBlockPos.toString runs its coordinates through SettingsUtil.maybeCensor, so a plan file built from
     * toString is unusable exactly when it matters, and the coordinates cannot be parsed back out either.
     *
     * Two: Locale. String.format without Locale.ROOT renders 0.41 as "0,41" on this project's own development
     * machine, which breaks the reproducibility rule the whole design rests on — two runs of the same build must
     * produce the same trace byte for byte, and a trace that depends on the operating system's locale does not.
     */

    /** {@code 112,-57,93} — the form the plan, proof and report files all use, and the form a human types back into
     *  a {@code /tp}. Never {@code BlockPos#toString}. */
    static String describePos(BlockPos pos) {
        return pos == null ? "?" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** {@code 113.30,-58.00,93.50} — two decimals, which is one more than the 0.02-block margins that decide right
     *  from wrong and therefore enough to read an approach or an aim point off the file. */
    static String describePoint(Vec3 point) {
        return point == null ? "?" : String.format(Locale.ROOT, "%.2f,%.2f,%.2f", point.x, point.y, point.z);
    }

    /** {@code minecraft:piston[extended=false,facing=down]} — namespace included and every property listed, because
     *  the property that decides a cell is correct is frequently not the one anybody thought to print. Properties
     *  come out in the block's own definition order, which is fixed, so two runs render identical text. */
    static String describeState(BlockState state) {
        if (state == null) {
            return "?";
        }
        StringBuilder out = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        boolean first = true;
        for (Property<?> property : state.getProperties()) {
            out.append(first ? '[' : ',').append(property.getName()).append('=').append(valueName(state, property));
            first = false;
        }
        return first ? out.toString() : out.append(']').toString();
    }

    /** {@code minecraft:piston} — the item, not the block, because {@code BlockPlaceHelper.matches} compares item
     *  identity and a hotbar swap between plan and click voids the click with no log line. */
    static String describeItem(Item item) {
        return item == null ? "?" : BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** {@code look -12.4/-48.2}, yaw before pitch, one decimal — the aim curve's residual is larger than that. */
    static String describeRotation(Rotation rotation) {
        return rotation == null ? "look ?"
                : String.format(Locale.ROOT, "look %.1f/%.1f", rotation.getYaw(), rotation.getPitch());
    }

    /** {@code down}, or {@code -} for the actions that have no clicked face. */
    static String describeFace(Direction face) {
        return face == null ? "-" : face.getName();
    }

    /** {@code 0.02} — an approach tolerance, through the same {@code Locale.ROOT} that keeps every other number in
     *  these files readable on a machine whose decimal separator is a comma. */
    static String describeTolerance(double tolerance) {
        return String.format(Locale.ROOT, "%.2f", tolerance);
    }

    /** Where the bot stands and what it looks at — the four fields every action but {@link SwapHotbar} carries. */
    static String describeStance(BlockPos stance, Vec3 approach, Vec3 aimPoint, Rotation rotation) {
        return "from " + describePos(stance) + " @" + describePoint(approach)
                + "  aim " + describePoint(aimPoint) + " " + describeRotation(rotation);
    }

    /** Hand and body. {@code sneak} versus {@code stand} is spelled out rather than printed as a flag, because it is
     *  the field a wrongly built chest or a repeater that placed instead of stepped is diagnosed from. */
    static String describePosture(boolean sneak, int handSlot) {
        // "slot unassigned" and not "slot -1": an escaped UNASSIGNED_SLOT must read as the missing decision it is,
        // not as a number somebody has to recognise. weave refuses to emit one, so seeing it at all is the news.
        return (handSlot == UNASSIGNED_SLOT ? "slot unassigned" : "slot " + handSlot)
                + " " + (sneak ? "sneak" : "stand");
    }

    /** The whole click geometry of a {@link PlacementSolution}, shared by {@link Place#describe},
     *  {@link PlaceScaffold#describe} and {@code BuildPlan#toProofLines} so the plan file and the proof file cannot
     *  drift into two different renderings of the same solution. */
    static String describeSolution(PlacementSolution solution) {
        if (solution == null) {
            return "?";
        }
        return describeState(solution.desired())
                + "  against " + describePos(solution.against()) + " face " + describeFace(solution.face())
                + "  " + describeStance(solution.stance(), solution.approach(), solution.aimPoint(),
                        solution.rotation())
                // The item and not a slot: a solution names the material, the schedule names the slot, and the slot
                // is printed once per line by describePosture rather than twice with two chances to disagree.
                + "  item " + describeItem(solution.item())
                + String.format(Locale.ROOT, "  margin %.2f", solution.margin())
                // Absent for the ordinary cell and present for the one whose sight line threads a corner, so "which
                // actions will make the fine approach work harder" is a grep rather than a second file to correlate.
                + (solution.isOcclusionTight()
                        ? "  tol " + describeTolerance(solution.approachTolerance()) : "");
    }

    /** One plan-file line: a fixed-width kind column so a 15 000-line file can be read down its left edge, then the
     *  action's own text. */
    private static String line(String tag, String rest) {
        return String.format(Locale.ROOT, "%-10s %s", tag, rest);
    }

    /** The hotbar is nine slots, plus {@link #UNASSIGNED_SLOT} for an action whose material the schedule has not
     *  placed yet. Anything else is a planner arithmetic error that would otherwise surface as a click with the wrong
     *  item in hand, which the server accepts and the audit reports as a wrong block — and a BACKPACK index in
     *  particular, which is what widening the oracle's material search to 36 slots would produce if the slot were read
     *  off the solution instead of off the schedule. */
    private static void checkHandSlot(int handSlot) {
        if (handSlot != UNASSIGNED_SLOT && (handSlot < 0 || handSlot > 8)) {
            throw new IllegalArgumentException("hand slot out of hotbar range 0..8 and not UNASSIGNED_SLOT: "
                    + handSlot);
        }
    }

    /**
     * The property's value as vanilla names it — {@code down}, not {@code DOWN}.
     *
     * <p>Raw for one line, because {@code Property<T>.getName(T)} cannot be called through a {@code Property<?>}
     * without capturing the wildcard, and {@code Comparable#toString} is not a substitute: several value enums do not
     * override it, so the file would carry {@code facing=DOWN} for some blocks and {@code facing=down} for others.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(BlockState state, Property<?> property) {
        return ((Property) property).getName(state.getValue(property));
    }
}
