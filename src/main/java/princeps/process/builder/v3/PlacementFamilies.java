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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * The three block families whose placed state depends on their NEIGHBOURS, and the rules that predict them.
 *
 * <p>This class is the reason V3 does not need a {@code PredictedLevel extends Level}. For roughly 95 % of blocks the
 * landed state is decided by the look direction and the clicked face — both of which the plan chooses — so the
 * predicted state falls out of the geometry with no world involved. Only three families genuinely read their
 * surroundings in {@code getStateForPlacement} AND carry a property that is not in
 * {@link PlacementGeometry#AUTO_RESOLVED_PROP_NAMES}, i.e. a property the builder is held to. Three rules of about
 * thirty lines each replace two to three days of {@code Level} implementation on the critical path.
 *
 * <p>Honest limit, stated where it will be read: this is where the dry run's residual risk lives. A dry run cannot
 * catch a family whose rule is wrong, and it cannot catch a fourth family nobody has noticed. The mitigation is not
 * hope, it is three headless test classes that construct the neighbourhood and compare against vanilla's own
 * {@code getStateForPlacement} — and the executor's live gate, which compares the predicted state against the real
 * simulation of the real ray and names any mismatch rather than clicking through it. A fourth family shows up there
 * as a named divergence on its first cell, and a new rule is thirty lines.
 *
 * <p>Pure over {@link PredictedWorld}: no {@code Level}, no player, no settings.
 *
 * <h2>Every rule below was transcribed from 26.1.2's own bytecode, not from memory</h2>
 * <p>The Mojmap jar this project compiles against ships no sources and no decompiler is on the offline classpath, so
 * each of the three was read out of {@code javap -c} of the shipped class and is cited by method name at its use site.
 * The transcription is deliberately literal — same comparison order, same tie-breaks, same {@code >} versus
 * {@code >=} — because every one of those details is the difference between a door hinged left and a cell that is
 * "incorrect" for the rest of the build.
 *
 * <h2>Shape of the split, and why it is not one method per family</h2>
 * <p>Each family is a package-private pure core that takes the neighbourhood as plain values, plus a public wrapper
 * that does the {@link PredictedWorld} reads and delegates. The cores are what the tests pin: vanilla's own
 * {@code getHinge} / {@code getChestType} / {@code getPlacementState} all need a live {@code Level} and cannot be
 * called headlessly at all, so the only thing a test can compare against is a rule transcribed from the bytecode —
 * and a rule that is reachable without building a world is a rule that gets tested.
 *
 * <h2>Solidity is read conservatively</h2>
 * <p>Vanilla asks {@code isCollisionShapeFullBlock} (doors) and {@code canSurvive} (the standing/wall family);
 * {@link PredictedWorld} offers {@link PredictedWorld#isSolidFullCube} and nothing finer, because the full-cube bitset
 * is also what {@link GridRay} walks. Full cube implies sturdy face implies "can support centre", so the answers here
 * err towards refusing a placement that vanilla would have allowed (a sign on top of a slab, a door beside a stair).
 * That direction is the safe one: a refusal becomes a named blocker in the report, a false accept becomes a cell that
 * is silently wrong forever.
 */
public final class PlacementFamilies {

    private PlacementFamilies() {
    }

    /** Which neighbour-sensitive family a desired state belongs to, if any. */
    public enum Family {

        /** {@code DoorBlock}: {@code hinge} comes from the neighbouring blocks and the X/Z position of the hit. */
        DOOR_HINGE,

        /** {@code ChestBlock}: {@code type} comes from adjacent chests and the sneak state. */
        CHEST_TYPE,

        /** {@code StandingAndWallBlockItem} — signs, torches, banners, heads: standing form versus wall form, chosen
         *  by which variant can survive where. */
        STANDING_OR_WALL,

        /** Everything else: the landed state is a function of look and clicked face alone. */
        NONE
    }

    /** Which family this state belongs to. */
    public static Family classify(BlockState desired) {
        Block block = desired.getBlock();
        if (block instanceof DoorBlock) {
            return Family.DOOR_HINGE;
        }
        if (block instanceof ChestBlock) {
            // TrappedChestBlock extends ChestBlock and pairs by the same rule; EnderChestBlock does not extend it and
            // has no TYPE property, so it correctly falls through to NONE.
            return Family.CHEST_TYPE;
        }
        // Membership is read off the ITEM rather than a list of block classes, because the item IS the family:
        // StandingAndWallBlockItem.registerBlocks maps both the standing block and the wall block to the same item, so
        // WALL_TORCH and TORCH both answer Items.TORCH here. A hand-maintained list of wall classes would have to be
        // extended for every new decoration; this does not.
        return block.asItem() instanceof StandingAndWallBlockItem ? Family.STANDING_OR_WALL : Family.NONE;
    }

    /** Shorthand for {@code classify(desired) != Family.NONE} — the branch that decides whether the oracle may take
     *  the geometry-only prediction or has to consult a rule. */
    public static boolean isNeighbourSensitive(BlockState desired) {
        return classify(desired) != Family.NONE;
    }

    // ------------------------------------------------------------------------------------------------ doors

    /**
     * How far the aim must sit from vanilla's hinge boundary before the hinge it produces counts as PROVEN.
     *
     * <p>Stage 3 of {@code getHinge} is a comparison against 0.5 with no width at all, so for a door whose flanking
     * blocks do not decide it the hinge is a step function of ONE coordinate of the hit. The planner used to aim at a
     * face centre — exactly 0.5 on both tangential axes — take the LEFT that vanilla's asymmetric comparisons hand an
     * exact 0.5, and then watch the live ray land a few hundredths to the other side and produce RIGHT. Planning is
     * deterministic, so it aimed at the same boundary on every re-plan and the cell stayed blocked forever: the pale
     * oak door at {@code 98,-60,93} on etz-basalt, aimed at {@code x=98.50} in a cell whose X starts at 98.
     *
     * <p>The width this has to clear is the BODY's, not the head's. The executor re-derives the look from the live
     * eye every tick, so the ray passes through the aim point and the residual is the eye's own offset from the
     * approach point — {@link FineApproach#TOLERANCE}, 0.08 blocks — which lands one-for-one on the face when the
     * look is axis-aligned. That door's plan reads {@code look 0.0/41.1}: at a yaw of exactly zero the hit's X IS the
     * body's X, and 0.08 of legal body slack is eighty times the distance to the boundary.
     *
     * <p>The width it must not exceed is the sampling's: every point {@link PlacementGeometry#aabbSideMultipliers}
     * offers on a full cube sits either exactly on the boundary or at least 0.25 from it, so any threshold inside
     * {@code (0.08, 0.25)} keeps precisely the off-boundary samples and rejects precisely the boundary. 0.15 is that
     * interval's most defensible point — nearly twice the body slack below, and well clear of the nearest sample
     * above.
     */
    public static final double HINGE_DECISION_MARGIN = 0.15D;

    /**
     * How far off the boundary the planner MOVES an aim point when it selects a hinge rather than merely predicting
     * one.
     *
     * <p>Strictly larger than {@link #HINGE_DECISION_MARGIN}, so a point placed here is accepted by a comparison with
     * room to spare instead of resting on the exact equality of two doubles arrived at by different routes.
     */
    public static final double HINGE_SELECTION_OFFSET = 0.25D;

    /**
     * One horizontal axis of the door's own cell, and the closed interval of cell-relative offsets along it that
     * prove a wanted hinge.
     *
     * <p>Produced by {@link #doorHingeWindow}. Aim inside it and vanilla has no choice left to make.
     */
    public record HingeWindow(Direction.Axis axis, double min, double max) {

        /** Is a hit at this cell-relative offset inside the window? */
        public boolean contains(double offset) {
            return offset >= this.min && offset <= this.max;
        }
    }

    /**
     * Which side the hinge lands on, reproducing {@code DoorBlock.getStateForPlacement}'s own condition against the
     * predicted world.
     *
     * <p>Vanilla counts the two blocks flanking the door on the axis perpendicular to {@code facing} — both halves of
     * each — and falls back to which side of the cell the hit landed on when they do not decide it. Both inputs are
     * things the plan chooses or predicts, so the answer is determined at plan time; it is simply not derivable from
     * the look alone, which is what puts doors in this class.
     *
     * <p>Faithful to vanilla including at the boundary, which is why it is not the method the planner proves a hinge
     * with: see {@link #hingeDecidedByHit}.
     *
     * @param hit the exact aim point, whose fractional X/Z is the tiebreak vanilla uses
     */
    public static DoorHingeSide predictDoorHinge(PredictedWorld world, BlockPos cell, Direction facing, Vec3 hit) {
        DoorHingeSide decided = doorHingeFromNeighbours(world, cell, facing);
        return decided != null ? decided : hingeFromHit(facing, hit.x - cell.getX(), hit.z - cell.getZ());
    }

    /**
     * Stages 1 and 2 of {@code getHinge} against the predicted world: the hinge the flanking blocks alone settle, or
     * null when vanilla falls through to the hit position.
     *
     * <p>Mirrors {@code DoorBlock.getHinge(BlockPlaceContext)}: left is counter-clockwise of facing, right is
     * clockwise, and BOTH halves of each column are read — the upper half matters because a door is two cells tall
     * and vanilla weights the neighbour that would sit beside the head, not just the feet. The cells are addressed
     * from {@code cell}, which is what {@code BlockPlaceContext.getClickedPos()} returns for every click the builder
     * makes: the clicked block is solid, hence not replaceable, hence the context reports the cell the block goes
     * INTO. A top-face click and a lateral one address the same six cells.
     */
    public static DoorHingeSide doorHingeFromNeighbours(PredictedWorld world, BlockPos cell, Direction facing) {
        Direction left = facing.getCounterClockWise();
        Direction right = facing.getClockWise();
        BlockPos leftPos = cell.relative(left);
        BlockPos rightPos = cell.relative(right);
        BlockPos leftAbove = leftPos.above();
        BlockPos rightAbove = rightPos.above();

        return doorHingeFromNeighbours(
                solid(world, leftPos), solid(world, leftAbove),
                solid(world, rightPos), solid(world, rightAbove),
                isLowerDoor(world.get(leftPos)), isLowerDoor(world.get(rightPos)));
    }

    /**
     * {@code DoorBlock.getHinge} with the six world reads already done — the whole of the hinge rule, as values.
     *
     * <p>Transcribed from the 26.1.2 bytecode of {@code getHinge}. The three stages, in vanilla's order:
     * <ol>
     *   <li>a signed neighbour score, {@code -1} per solid cell on the counter-clockwise side and {@code +1} per solid
     *       cell on the clockwise side, both halves counted;</li>
     *   <li>an override for an adjacent LOWER door half, which beats the score outright in both directions — this is
     *       what makes double doors mirror instead of both hinging the same way;</li>
     *   <li>the hit position as the tiebreak, read against {@code facing}'s step vector.</li>
     * </ol>
     *
     * <p>Split across {@link #doorHingeFromNeighbours} (stages 1 and 2) and {@link #hingeFromHit} (stage 3) because
     * the planner needs the two apart: whether the aim matters at all is stage 1 and 2's answer, and where to aim is
     * stage 3's. Composed here so this signature stays exactly what it was and the tests that pin it against vanilla
     * keep pinning the same thing.
     *
     * @param hitOffsetX the hit's X minus the cell's X, i.e. the fraction across the cell
     * @param hitOffsetZ the hit's Z minus the cell's Z
     */
    static DoorHingeSide doorHinge(Direction facing,
                                   boolean leftLowerSolid, boolean leftUpperSolid,
                                   boolean rightLowerSolid, boolean rightUpperSolid,
                                   boolean leftIsLowerDoor, boolean rightIsLowerDoor,
                                   double hitOffsetX, double hitOffsetZ) {
        DoorHingeSide decided = doorHingeFromNeighbours(leftLowerSolid, leftUpperSolid, rightLowerSolid,
                rightUpperSolid, leftIsLowerDoor, rightIsLowerDoor);
        return decided != null ? decided : hingeFromHit(facing, hitOffsetX, hitOffsetZ);
    }

    /** Stages 1 and 2 as values: the signed neighbour score and the adjacent-lower-door override, which between them
     *  settle the hinge without looking at the hit at all. Null means vanilla falls through to stage 3. */
    static DoorHingeSide doorHingeFromNeighbours(boolean leftLowerSolid, boolean leftUpperSolid,
                                                 boolean rightLowerSolid, boolean rightUpperSolid,
                                                 boolean leftIsLowerDoor, boolean rightIsLowerDoor) {
        int score = (leftLowerSolid ? -1 : 0) + (leftUpperSolid ? -1 : 0)
                + (rightLowerSolid ? 1 : 0) + (rightUpperSolid ? 1 : 0);

        if ((leftIsLowerDoor && !rightIsLowerDoor) || score > 0) {
            return DoorHingeSide.RIGHT;
        }
        if ((rightIsLowerDoor && !leftIsLowerDoor) || score < 0) {
            return DoorHingeSide.LEFT;
        }
        return null;
    }

    /**
     * Stage 3 as values: the hit position read against {@code facing}'s step vector.
     *
     * <p>The comparisons are asymmetric in vanilla and are kept asymmetric here: the two branches that key off
     * {@code stepZ} use {@code > 0.5} and {@code < 0.5}, the two that key off {@code stepX} use {@code < 0.5} and
     * {@code > 0.5}, and an exact 0.5 therefore falls to LEFT on all four facings and RIGHT on none. That is
     * vanilla's answer and this method gives it, but it is an answer no plan may REST on — a boundary value is a
     * prediction the live ray is free to contradict. {@link #hingeDecidedByHit} is the gate that refuses it and
     * {@link #doorHingeWindow} is what moves the aim off it.
     */
    static DoorHingeSide hingeFromHit(Direction facing, double hitOffsetX, double hitOffsetZ) {
        int stepX = facing.getStepX();
        int stepZ = facing.getStepZ();
        boolean rightByHit = (stepX < 0 && hitOffsetZ < 0.5D)
                || (stepX > 0 && hitOffsetZ > 0.5D)
                || (stepZ < 0 && hitOffsetX > 0.5D)
                || (stepZ > 0 && hitOffsetX < 0.5D);
        return rightByHit ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
    }

    /**
     * Which coordinate of the hit vanilla reads when the flanking blocks do not decide the hinge: the X offset for a
     * door facing north or south, the Z offset for one facing east or west.
     *
     * <p>Read straight off stage 3, whose two {@code stepZ} branches test {@code hitOffsetX} and whose two
     * {@code stepX} branches test {@code hitOffsetZ}. A door's facing is always horizontal, so exactly one pair of
     * branches is live and exactly one coordinate is read.
     */
    public static Direction.Axis hingeDecisionAxis(Direction facing) {
        return facing.getStepZ() != 0 ? Direction.Axis.X : Direction.Axis.Z;
    }

    /**
     * Does a hit here PROVE the hinge, or does it sit on vanilla's boundary where a few hundredths of body drift flip
     * it? Only meaningful once stages 1 and 2 have left the answer to the hit.
     *
     * <p>The one place the engine deliberately answers a narrower question than vanilla does. Vanilla always has an
     * answer at 0.5; the planner must not, because the number it would be proving is not the number the live ray will
     * carry.
     */
    static boolean hingeDecidedByHit(Direction facing, double hitOffsetX, double hitOffsetZ) {
        double deciding = hingeDecisionAxis(facing) == Direction.Axis.X ? hitOffsetX : hitOffsetZ;
        return Math.abs(deciding - 0.5D) >= HINGE_DECISION_MARGIN;
    }

    /**
     * Where on the door's own cell the aim has to land for the hinge to come out {@code wanted}, or null when no aim
     * can change the answer — the flanking blocks decide it, one way or the other, and the caller's job is then to
     * check WHAT they decided rather than where to point.
     *
     * <p>This is what turns the hinge from something the plan predicts into something it CHOOSES. The planner picks
     * the aim point, the aim point is stage 3's only input, and stage 3 is a clean split of the cell down one
     * horizontal axis. The window is the half {@link #HINGE_SELECTION_OFFSET} away from the boundary rather than the
     * whole half, for the reason that constant names.
     *
     * <p>Which side of 0.5 yields {@code wanted} is not tabulated per facing: it is read off the transcribed stage 3
     * by asking it. A table would be a second transcription to keep in step with the first, and this file's whole
     * discipline is that vanilla's rule appears exactly once.
     */
    public static HingeWindow doorHingeWindow(PredictedWorld world, BlockPos cell, Direction facing,
                                              DoorHingeSide wanted) {
        if (facing == null || wanted == null || facing.getAxis() == Direction.Axis.Y
                || doorHingeFromNeighbours(world, cell, facing) != null) {
            return null;
        }
        Direction.Axis axis = hingeDecisionAxis(facing);
        boolean lowerHalfWins = hingeAtOffset(facing, axis, 0.0D) == wanted;
        return lowerHalfWins
                ? new HingeWindow(axis, 0.0D, 0.5D - HINGE_SELECTION_OFFSET)
                : new HingeWindow(axis, 0.5D + HINGE_SELECTION_OFFSET, 1.0D);
    }

    /** Stage 3 asked about one axis alone: the other coordinate is not read for this facing, so any value will do. */
    private static DoorHingeSide hingeAtOffset(Direction facing, Direction.Axis axis, double offset) {
        return hingeFromHit(facing,
                axis == Direction.Axis.X ? offset : 0.5D,
                axis == Direction.Axis.Z ? offset : 0.5D);
    }

    // ------------------------------------------------------------------------------------------------ chests

    /**
     * Whether the chest lands SINGLE, LEFT or RIGHT.
     *
     * <p>The rule that matters more than the prediction: <b>chests are placed standing, never sneaking.</b> Sneak
     * forces SINGLE, so a crouched placement silently destroys a planned double chest, and the builder places
     * crouched by default. That is why every {@link BuildAction} carries its own sneak state instead of the executor
     * holding sneak down for the whole build. The pairing order is fixed by the planner, so the second half knows
     * which side it is completing before either is placed.
     *
     * <p>The sneak guard is answered BEFORE any world read, exactly as vanilla answers it: {@code getChestType} is
     * only reached under {@code type == SINGLE && !isSecondaryUseActive()}. Vanilla does have one further branch in
     * which a sneaking placement still pairs — sneak-clicking the horizontal face of an existing single chest — but
     * that branch rewrites {@code facing} as well as {@code type} and so cannot be expressed as a type alone; it lives
     * in {@link #resolve}, which has the clicked face and can return the whole state.
     *
     * <p>One thing this signature cannot express: vanilla's {@code chestCanConnectTo} is {@code state.is(this)}, so a
     * trapped chest never pairs with a plain one, and with no candidate state here there is no {@code this} to compare
     * against. It therefore accepts any {@code ChestBlock} neighbour. That over-accepts by exactly one case — a plain
     * chest placed beside a trapped one — which {@link #resolve} does not, and {@link #resolve} is what the oracle
     * calls. Use this method for the standalone question, not as a shortcut around the resolver.
     *
     * @param facing the chest's own FACING, i.e. {@code getHorizontalDirection().getOpposite()} — the direction the
     *               lid's front points, not the direction the placer looks
     */
    public static ChestType predictChestType(PredictedWorld world, BlockPos cell, Direction facing, boolean sneaking) {
        if (sneaking) {
            return ChestType.SINGLE;
        }
        return chestType(facing,
                world.get(cell.relative(facing.getClockWise())),
                world.get(cell.relative(facing.getCounterClockWise())),
                null);
    }

    /**
     * {@code ChestBlock.getChestType} with the two neighbours supplied.
     *
     * <p>The asymmetry is vanilla's and is load-bearing: a partner found CLOCKWISE of the facing makes this chest the
     * LEFT half, counter-clockwise makes it the RIGHT half. Getting it backwards produces two chests that both claim
     * the same half, which the server resolves by leaving them single — a double chest that never opens as one.
     *
     * @param self the block both halves must be, or null to accept any chest block the neighbour happens to be, which
     *             is what {@link #predictChestType} passes when the planner has not fixed the variant yet. Vanilla's
     *             {@code chestCanConnectTo} is {@code state.is(this)}, so a trapped chest never pairs with a plain one;
     *             pass the block when that distinction matters.
     */
    static ChestType chestType(Direction facing, BlockState clockWiseNeighbour, BlockState counterClockWiseNeighbour,
                               Block self) {
        if (facing == candidatePartnerFacing(clockWiseNeighbour, self)) {
            return ChestType.LEFT;
        }
        if (facing == candidatePartnerFacing(counterClockWiseNeighbour, self)) {
            return ChestType.RIGHT;
        }
        return ChestType.SINGLE;
    }

    /**
     * {@code ChestBlock.candidatePartnerFacing}: the facing of the neighbour if it is a chest of the same kind that is
     * still SINGLE, else null. An already-paired chest is not a candidate — that is what stops a third chest joining a
     * double.
     */
    static Direction candidatePartnerFacing(BlockState neighbour, Block self) {
        if (neighbour == null || !(neighbour.getBlock() instanceof ChestBlock)) {
            return null;
        }
        if (self != null && neighbour.getBlock() != self) {
            return null;
        }
        if (!neighbour.hasProperty(ChestBlock.TYPE) || neighbour.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return null;
        }
        return neighbour.getValue(ChestBlock.FACING);
    }

    // ------------------------------------------------------------------------------------------- chest pairs

    /**
     * The cell holding the OTHER half of a double chest, or null when {@code desired} is not half of one.
     *
     * <p>{@code ChestBlock.getConnectedDirection} verbatim: LEFT connects clockwise of its facing, RIGHT
     * counter-clockwise. The direction is a property of the state alone, so this needs no world.
     *
     * <p>Exists because a double chest cannot be placed in one click. {@code getStateForPlacement} can only return
     * LEFT or RIGHT when a chest is ALREADY standing beside the cell — {@link #candidatePartnerFacing} requires the
     * neighbour to be a chest with {@code TYPE == SINGLE} — so the first half of every pair lands SINGLE no matter
     * which stance, face, aim or sneak state produced it. SINGLE is a mandatory intermediate state, not a mistake.
     */
    public static BlockPos chestPairPartner(BlockState desired, BlockPos cell) {
        if (desired == null || cell == null || !(desired.getBlock() instanceof ChestBlock)
                || !desired.hasProperty(ChestBlock.FACING) || !desired.hasProperty(ChestBlock.TYPE)
                || desired.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return cell.relative(ChestBlock.getConnectedDirection(desired));
    }

    /**
     * Are these two states the two halves of one double chest vanilla could actually produce?
     *
     * <p>Every condition is one vanilla enforces somewhere: same block ({@code chestCanConnectTo} is
     * {@code state.is(this)}), same facing ({@code ChestBlock.updateShape} refuses to pair halves that disagree), and
     * opposite non-SINGLE types (two halves both claiming LEFT is the "double chest that never opens as one").
     *
     * <p>A schematic can and does contain pairs that fail this. etz-basalt has four such cells at the corners of its
     * chest box, pointing at each other with matching types and opposing facings. Vanilla cannot build them, so the
     * planner must not pretend it can — they stay blocked, by name, which is the whole point.
     */
    public static boolean chestPairConsistent(BlockState first, BlockState second) {
        if (first == null || second == null
                || !(first.getBlock() instanceof ChestBlock) || first.getBlock() != second.getBlock()
                || !first.hasProperty(ChestBlock.FACING) || !first.hasProperty(ChestBlock.TYPE)
                || !second.hasProperty(ChestBlock.FACING) || !second.hasProperty(ChestBlock.TYPE)) {
            return false;
        }
        ChestType firstType = first.getValue(ChestBlock.TYPE);
        ChestType secondType = second.getValue(ChestBlock.TYPE);
        return firstType != ChestType.SINGLE
                && secondType != ChestType.SINGLE
                && first.getValue(ChestBlock.FACING) == second.getValue(ChestBlock.FACING)
                && secondType == firstType.getOpposite();
    }

    /**
     * What the ALREADY-STANDING neighbour becomes the moment {@code placed} lands beside it, or null if it does not
     * change.
     *
     * <p>{@code ChestBlock.updateShape} as values:
     * <pre>
     *   if (chestCanConnectTo(neighbour) &amp;&amp; direction.getAxis().isHorizontal()
     *           &amp;&amp; state.getValue(TYPE) == SINGLE &amp;&amp; neighbourType != SINGLE
     *           &amp;&amp; state.getValue(FACING) == neighbour.getValue(FACING)
     *           &amp;&amp; getConnectedDirection(neighbour) == direction.getOpposite())
     *       return state.setValue(TYPE, neighbourType.getOpposite());
     * </pre>
     * with {@code state} = {@code partnerNow} and {@code neighbour} = {@code placed}. The
     * {@code getConnectedDirection(placed) == direction.getOpposite()} condition is satisfied BY CONSTRUCTION and so
     * is not re-tested here: the only caller derives the partner cell from
     * {@link #chestPairPartner}, i.e. from {@code getConnectedDirection(placed)} itself, so the direction from the
     * partner back to the placed chest is that direction's opposite by definition. Likewise
     * {@code direction.getAxis().isHorizontal()} holds because {@code getConnectedDirection} returns a clockwise or
     * counter-clockwise rotation of a horizontal facing.
     *
     * <p>The game does this for free through ordinary neighbour-shape propagation — independent of sneak, independent
     * of who placed either half. The forward simulation has to reproduce it because {@code PredictedWorld.apply} is a
     * raw write with no propagation.
     */
    public static BlockState chestPairCompletion(BlockState placed, BlockState partnerNow) {
        if (placed == null || partnerNow == null
                || !(placed.getBlock() instanceof ChestBlock) || partnerNow.getBlock() != placed.getBlock()
                || !placed.hasProperty(ChestBlock.FACING) || !placed.hasProperty(ChestBlock.TYPE)
                || !partnerNow.hasProperty(ChestBlock.FACING) || !partnerNow.hasProperty(ChestBlock.TYPE)) {
            return null;
        }
        ChestType placedType = placed.getValue(ChestBlock.TYPE);
        if (placedType == ChestType.SINGLE
                || partnerNow.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                || partnerNow.getValue(ChestBlock.FACING) != placed.getValue(ChestBlock.FACING)) {
            return null;
        }
        return partnerNow.setValue(ChestBlock.TYPE, placedType.getOpposite());
    }

    // --------------------------------------------------------------------------------------- standing or wall

    /**
     * Standing form or wall form, for the {@code StandingAndWallBlockItem} family.
     *
     * <p>Mostly already solved: {@link PlacementGeometry#requiredSupportDirection} reads the wall variant's
     * {@code facing} and returns the exact neighbour to click, so the planner picks the variant by picking the face.
     * This method closes the loop by confirming that the chosen face actually yields the wanted variant against the
     * predicted world — the standing form survives only on a floor, the wall form only on a side.
     *
     * <p>Confirmed against {@code StandingAndWallBlockItem.getPlacementState}, which loops
     * {@code BlockPlaceContext.getNearestLookingDirections()} and takes the first direction that is not
     * {@code attachmentDirection.getOpposite()} and whose variant survives. The loop's first entry is not free: when
     * the clicked block is not replaceable — always true for the builder, which clicks solid neighbours —
     * {@code getNearestLookingDirections} moves {@code getClickedFace().getOpposite()} to the front of the array. So
     * the variant is decided by the face the plan clicks, and {@code requiredSupportDirection} is that face's
     * opposite. That is the confirmation the plan asked for.
     */
    public static BlockState predictStandingOrWall(PredictedWorld world, BlockPos cell, BlockState desired,
                                                   Direction clickedFace) {
        if (classify(desired) != Family.STANDING_OR_WALL) {
            return desired;
        }
        // The support is always the block that was clicked: the accepted branch is the first entry of
        // getNearestLookingDirections, and that entry is clickedFace.getOpposite() by construction.
        BlockPos support = cell.relative(clickedFace.getOpposite());
        return standingOrWall(desired, clickedFace, solid(world, support));
    }

    /**
     * {@code StandingAndWallBlockItem.getPlacementState} reduced to the one direction the plan actually clicks.
     *
     * <p>Vanilla walks the whole look-ordered array and falls through to the second-, third- and fourth-nearest
     * direction when the first variant cannot survive. This rule predicts only the first entry and returns null when
     * that one fails, deliberately: the fallback entries depend on the exact look ordering, which humanised aim only
     * approximately controls, so predicting them would be predicting a coin flip. Null costs a named blocker in the
     * report; a wrong guess costs a cell that is silently a wall torch where the schematic wanted a floor torch.
     *
     * @param clickedBlockIsSolid whether the neighbour at {@code clickedFace.getOpposite()} is a full solid cube
     * @return {@code desired} when this click produces exactly it, else null
     */
    static BlockState standingOrWall(BlockState desired, Direction clickedFace, boolean clickedBlockIsSolid) {
        Direction attach = clickedFace.getOpposite();
        Direction itemAttach = attachmentDirection(desired);
        if (attach == itemAttach.getOpposite()) {
            // Vanilla skips this direction before testing anything: a torch does not hang from a ceiling and a hanging
            // sign does not stand on a floor. No amount of solid neighbour makes this click work.
            return null;
        }

        boolean wallFormWanted = isWallForm(desired);
        if (attach == itemAttach) {
            // The item's own block -- the standing form (the ceiling form, for hanging signs).
            return !wallFormWanted && clickedBlockIsSolid ? desired : null;
        }

        // Everything else is a horizontal attachment, which yields the wall block.
        if (!wallFormWanted) {
            return null;
        }
        if (desired.getBlock() instanceof WallHangingSignBlock) {
            // The one member of the family whose support is NOT behind its facing: a wall hanging sign spans BETWEEN
            // the blocks to its left and right, so the clicked face does not determine it and canSurvive reads two
            // cells this rule was never given. BuilderProcess.requiredSupportDirection returns null here for the same
            // reason. Refused rather than guessed.
            return null;
        }
        if (!desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return null;
        }
        // The wall block's own getStateForPlacement sets FACING to the attachment direction's opposite, so exactly one
        // face produces the wanted facing and every other face produces a different -- and therefore wrong -- one.
        if (attach != desired.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()) {
            return null;
        }
        return clickedBlockIsSolid ? desired : null;
    }

    /**
     * Which side of the block the item attaches its standing form to: DOWN for signs, torches, banners and heads,
     * UP for hanging signs.
     *
     * <p>Read off the block rather than the item because {@code StandingAndWallBlockItem.attachmentDirection} is
     * private with no accessor. The two values are fixed at construction and there are only two of them:
     * {@code SignItem} passes {@code Direction.DOWN} and {@code HangingSignItem} passes {@code Direction.UP}, and both
     * hanging-sign blocks are recognisable by class.
     */
    static Direction attachmentDirection(BlockState desired) {
        Block block = desired.getBlock();
        return block instanceof CeilingHangingSignBlock || block instanceof WallHangingSignBlock
                ? Direction.UP
                : Direction.DOWN;
    }

    /**
     * Whether this state is the family's WALL member rather than its standing member.
     *
     * <p>Derived, not listed: {@code BlockItem.getBlock()} returns the standing block the item was constructed with,
     * so any other block that maps to the same item is by definition the wall block. That covers wall torches, wall
     * signs, wall banners, wall skulls and wall hanging signs without naming one of them.
     */
    static boolean isWallForm(BlockState desired) {
        Item item = desired.getBlock().asItem();
        return item instanceof BlockItem blockItem && blockItem.getBlock() != desired.getBlock();
    }

    // ------------------------------------------------------------------------------------------------ entry

    /**
     * The single entry point the oracle calls: given the geometry-derived candidate state, apply whichever family rule
     * applies and return the state that will actually land, or null when this click cannot produce the wanted family
     * member at all.
     *
     * @param candidate what the look-and-face derivation predicts, before neighbour effects
     * @param sneaking  the sneak state the action will carry — an input, not an assumption
     */
    public static BlockState resolve(PredictedWorld world, BlockPos cell, BlockState candidate, BlockState desired,
                                     Direction face, Vec3 aimPoint, boolean sneaking) {
        switch (classify(desired)) {
            case DOOR_HINGE: {
                if (!candidate.hasProperty(DoorBlock.FACING) || !candidate.hasProperty(DoorBlock.HINGE)) {
                    return candidate;
                }
                Direction facing = candidate.getValue(DoorBlock.FACING);
                DoorHingeSide decided = doorHingeFromNeighbours(world, cell, facing);
                if (decided != null) {
                    return candidate.setValue(DoorBlock.HINGE, decided);
                }
                double hitOffsetX = aimPoint.x - cell.getX();
                double hitOffsetZ = aimPoint.z - cell.getZ();
                if (!hingeDecidedByHit(facing, hitOffsetX, hitOffsetZ)) {
                    // Vanilla's stage 3 is a comparison against 0.5 with no width, and this aim sits on it. The hinge
                    // it predicts is one the live ray is free to contradict, so this is not a candidate: the search
                    // moves to the next aim point, which doorHingeWindow has already put on the proven side. A named
                    // stop, or a better aim, but never a coin flip clicked through.
                    return null;
                }
                return candidate.setValue(DoorBlock.HINGE, hingeFromHit(facing, hitOffsetX, hitOffsetZ));
            }
            case CHEST_TYPE:
                return resolveChest(world, cell, candidate, face, sneaking);
            case STANDING_OR_WALL:
                // A StandingAndWallBlockItem's BlockItem points at the standing block, so the geometry-only candidate
                // is necessarily the standing variant even when this click is meant to create a wall sign/torch/
                // banner/skull. Classifying or resolving that candidate loses the desired wall member before the
                // family rule gets a chance to select it.
                return predictStandingOrWall(world, cell, desired, face);
            case NONE:
            default:
                return candidate;
        }
    }

    /** Compatibility entry point for family-level callers whose candidate is also the desired state. */
    public static BlockState resolve(PredictedWorld world, BlockPos cell, BlockState candidate, Direction face,
                                     Vec3 aimPoint, boolean sneaking) {
        return resolve(world, cell, candidate, candidate, face, aimPoint, sneaking);
    }

    /**
     * The whole of {@code ChestBlock.getStateForPlacement}, minus the waterlogging the bucket pass owns.
     *
     * <p>Kept as one piece rather than delegated to {@link #predictChestType} because vanilla's sneak branch changes
     * {@code facing} too: sneak-clicking the horizontal face of an existing single chest adopts THAT chest's facing
     * and pairs with it, which is the one way a crouched placement still produces a double chest. The planner does not
     * currently emit that click, but the executor's live gate compares against whatever this returns, and a resolver
     * that answered SINGLE there would report a divergence on a placement vanilla got right.
     */
    private static BlockState resolveChest(PredictedWorld world, BlockPos cell, BlockState candidate, Direction face,
                                           boolean sneaking) {
        if (!candidate.hasProperty(ChestBlock.FACING) || !candidate.hasProperty(ChestBlock.TYPE)) {
            return candidate;
        }
        Block self = candidate.getBlock();
        Direction facing = candidate.getValue(ChestBlock.FACING);
        ChestType type = ChestType.SINGLE;

        if (face.getAxis().isHorizontal() && sneaking) {
            // candidatePartnerFacing(level, clickedPos, clickedFace.getOpposite()) -- the neighbour it examines is the
            // block that was clicked, which is exactly `against`.
            Direction partner = candidatePartnerFacing(world.get(cell.relative(face.getOpposite())), self);
            if (partner != null && partner.getAxis() != face.getAxis()) {
                facing = partner;
                type = facing.getCounterClockWise() == face.getOpposite() ? ChestType.RIGHT : ChestType.LEFT;
            }
        }
        if (type == ChestType.SINGLE && !sneaking) {
            type = chestType(facing,
                    world.get(cell.relative(facing.getClockWise())),
                    world.get(cell.relative(facing.getCounterClockWise())),
                    self);
        }
        return candidate.setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, type);
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private static boolean solid(PredictedWorld world, BlockPos pos) {
        return world.isSolidFullCube(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The lower half of a door, which is the only half vanilla's hinge rule looks for. */
    private static boolean isLowerDoor(BlockState state) {
        return state != null
                && state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }
}
