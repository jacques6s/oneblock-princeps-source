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

import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CalibratedSculkSensorBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DriedGhastBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GlazedTerracottaBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The aim mathematics, lifted out of {@code BuilderProcess} and made pure.
 *
 * <p>Every method here depends on its arguments and nothing else. No {@code Level}, no {@code LocalPlayer}, no
 * {@code IPlayerContext}, no {@code Princeps.settings()} — the class does not import them and must not start. That is
 * enforceable at review time by reading the import block, and it is the property that makes the placement search
 * testable at all: today not one of {@code simulatePlacement}, {@code orientationAchievableFrom},
 * {@code aimPointsOnFace} or {@code mostAlignedPointOnFace} has a single unit test, because reaching any of them
 * requires a running client.
 *
 * <p>Twenty-two of V2's statics move here unchanged; they were already referentially transparent, they just lived in
 * a 5 377-line class that was not. Two more — {@link #sameBlockstate} and {@link #valid} — could not, because they
 * read {@code Princeps.settings()} directly. They are here anyway, in the one form that keeps the class free of
 * globals: with a {@link V3Settings} snapshot as their first argument. {@code V3Settings}'s instance methods of the
 * same names are the intended call site and delegate here, so the rule has exactly one implementation and the
 * "no settings read outside {@code V3Settings.capture()}" invariant still holds by inspection of the imports.
 *
 * <p>Two additions beyond the port. {@link #mostAlignedPointOnFaceWithMargin}: V2 asks the aim question as a boolean
 * and throws away the number, and the number is what V3 selects on. {@link #playerBodyIntersects}: check A1 of the
 * approach conditions, which V2 never asks explicitly and therefore misdiagnoses as an obstruction.
 */
public final class PlacementGeometry {

    private PlacementGeometry() {
    }

    /** Placement-CONTROLLABLE orientation: a survival player picks these by where they stand / which face+spot they
     *  click, so they must match the schematic exactly (only bypassed when the user sets buildIgnoreDirection). */
    public static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    TrapDoorBlock.HALF
            );

    /** Properties a placement can NEVER dictate — they are resolved by the environment (fence/wall/pane/bars/redstone
     *  connections derive from neighbours; leaf decay bookkeeping the game maintains) or need a follow-up interaction
     *  or redstone we don't drive (open, powered). A fence set between two posts legitimately stands unconnected until
     *  its neighbours exist, and every such state is CORRECT the instant it's placed. Matched by name so it holds
     *  across every connective block and mapping. NOTE: kept OFF this list on purpose — facing / half / hinge / axis
     *  (the things a human actually aims), and waterlogged (owned by the bucket pass).
     *
     *  <p>{@code shape} belongs here despite reading like geometry: a stair's SHAPE is not placeable. Vanilla derives
     *  it in getStateForPlacement and recomputes it in updateShape whenever a neighbour changes — no aim, stance or
     *  click can choose it. Enforcing it meant the builder rejected its OWN correct placement forever: the bench's
     *  oriented scenario stalled on stairs where the simulation produced exactly the wanted facing and half, and
     *  differed only in shape=outer_right vs straight, which the adjacent stairs had already decided. Rails carry a
     *  {@code shape} too and self-connect the same way, so the same reasoning covers them.</p> */
    public static final Set<String> AUTO_RESOLVED_PROP_NAMES =
            ImmutableSet.of(
                    "north", "east", "south", "west", "up", "down",
                    "powered", "power", "in_wall",
                    "distance", "persistent",
                    "shape",
                    // Written by the GAME, never by whoever places the block, and no right-click sets them either.
                    // Each one below was confirmed against 26.1.2's own bytecode (the overriding method is named), not
                    // assumed -- because getting this list wrong is silent and total: the builder compares the state
                    // it wanted against the state that landed, they differ in a property no click can influence, the
                    // cell is "incorrect" forever, and with buildInLayers + skipFailedLayers=false a single such cell
                    // stops the whole build at its layer. etz-basalt is 1081 repeaters, 544 note blocks, 2048 pistons
                    // and 140 hoppers, so this list is the difference between building it and never leaving layer 3.
                    "locked",       // RepeaterBlock.updateShape -- a powered repeater to the side locks this one
                    "instrument",   // NoteBlock.updateShape -- read off the block BELOW, recomputed when it changes
                    "enabled",      // HopperBlock.neighborChanged -- redstone decides it
                    "extended",     // PistonBaseBlock.onPlace -- redstone decides it
                    "bottom",       // ScaffoldingBlock.updateShape -- whether anything holds it from below
                    "triggered",    // dispenser/dropper, pulsed by redstone
                    "lit",          // redstone torch/lamp/furnace -- burning or powered, not placed
                    "short",        // piston head length, owned by the piston
                    "snowy",        // grass/podzol, read off the block ABOVE
                    "attached",     // tripwire, owned by its hook
                    "disarmed",     // tripwire
                    "signal_fire",  // campfire, read off the hay bale below
                    "occupied",     // bed, owned by whoever sleeps in it
                    // CONTENTS. A freshly placed bookshelf is empty, a freshly placed lectern has no book, a freshly
                    // placed cauldron holds nothing. Filling them is item logistics, not construction -- no click and
                    // no placement sets these, so comparing them makes a correctly built shelf permanently wrong.
                    // (lighthouse.litematic has 12 chiseled bookshelves and a lectern, all recorded full.)
                    "slot_0_occupied", "slot_1_occupied", "slot_2_occupied",
                    "slot_3_occupied", "slot_4_occupied", "slot_5_occupied",
                    "has_book", "has_record", "has_bottle_0", "has_bottle_1", "has_bottle_2", "level",
                    // GROWTH. Crops age, honey fills, eggs hatch, sculk cracks -- all on the game's schedule.
                    "age", "stage", "honey_level", "bites", "eggs", "hatch",
                    "moisture", "hydration", "dusted", "berries", "cracked"
            );

    /** Properties a placement CAN'T set but a follow-up right-click CAN: trapdoor/door/gate OPEN, repeater DELAY,
     *  comparator MODE, note-block NOTE. These are enforced for correctness (so the build isn't falsely "done")
     *  but fixed by the interaction pass (aim + right-click N times), never by break-and-replace. See
     *  {@link #interactionClicks}. "open" moved here from AUTO_RESOLVED so trapdoors/doors actually get opened. */
    public static final Set<String> INTERACTION_PROP_NAMES =
            ImmutableSet.of("open", "delay", "mode", "note",
                    "inverted");  // daylight detector: right-click flips it, exactly like comparator mode

    /** How far inside the face's edge a derived aim point sits, so the ray cannot graze past it. */
    public static final double FACE_INSET = 0.02D;

    /**
     * Dominance slack a solution must clear, in blocks. Alias of {@link SolveBudget#MIN_MARGIN} so the geometry and the
     * budget cannot drift to two different numbers — the threshold is one decision, and it belongs next to the method
     * that produces the quantity it thresholds.
     *
     * <p>Why it is not zero, which is what {@link #mostAlignedPointOnFace} effectively accepts: that method compares
     * strictly, so a margin of 0.02 passes. 0.02 blocks is a measured, real value on V2's own aim points, and it is
     * also less than the noise it has to survive — the crouch pose interpolates 1.62 to 1.27 over about six ticks so
     * the eye is still moving when the click fires, and the humanized aim curve leaves a residual of its own. Below the
     * threshold the dominant axis is decided by whichever of those happens to round which way, and a placement that
     * lands the wrong facing is not a retry: it is a break and a re-place. Cells solvable only underneath it are
     * reported as TIGHT with the number attached, never taken silently.
     */
    public static final double MIN_MARGIN = SolveBudget.MIN_MARGIN;

    // ------------------------------------------------------------------------------- support and look derivation

    /**
     * Does this block hang on a wall rather than stand on the ground?
     *
     * <p>Signs, torches, ladders, buttons and levers are placed by clicking the SIDE face of the block they attach
     * to. While the surrounding structure is still going up that face is usually walled in or unreachable, the ray
     * lands on the floor instead, and what would be placed is the standing variant -- so the cell is deferred, over
     * and over, at the cost of a full stance search each time. The owner's basalt schematic spent its throughput
     * exactly there: 52 deferrals of one wall sign while the build crawled.
     *
     * <p>A human hangs the decorations after the walls are up. So does the builder now.
     */
    public static boolean isWallMounted(BlockState state) {
        net.minecraft.world.level.block.Block block = state.getBlock();
        return block instanceof net.minecraft.world.level.block.WallSignBlock
                || block instanceof net.minecraft.world.level.block.WallTorchBlock
                || block instanceof net.minecraft.world.level.block.WallHangingSignBlock
                || block instanceof net.minecraft.world.level.block.WallBannerBlock
                || block instanceof net.minecraft.world.level.block.WallSkullBlock
                || block instanceof net.minecraft.world.level.block.LadderBlock
                || block instanceof net.minecraft.world.level.block.ButtonBlock
                || block instanceof net.minecraft.world.level.block.LeverBlock;
    }

    /**
     * Which neighbour has to be clicked to make this state exist — read off the state, not searched for.
     *
     * <p>A block has six faces, and for anything that hangs, stands or sticks, exactly one of them is the answer and
     * the state already says which. A wall sign with {@code facing=west} hangs on the block to its EAST; the only
     * click that can produce it is on that block's west face. Trying the other five is not a search, it is five
     * raytraces whose outcome was known before they were cast — and at five aim points per face, per candidate
     * stance, that was 30 rays where 5 suffice.
     *
     * <p>Returned as the direction FROM the target cell TO the block to click, which is what the placement code
     * calls {@code against}. Null means the state genuinely does not care — a full cube, a log, a fence — and every
     * face is a legitimate answer, so the caller falls back to trying them.
     */
    public static Direction requiredSupportDirection(BlockState desired) {
        // Buttons and levers say it outright: they carry the attachment as a property.
        if (desired.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            switch (desired.getValue(BlockStateProperties.ATTACH_FACE)) {
                case FLOOR:
                    return Direction.DOWN;
                case CEILING:
                    return Direction.UP;
                case WALL:
                    return desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                            ? desired.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()
                            : null;
            }
        }
        // NOT a wall hanging sign: it hangs BETWEEN the blocks to its left and right, so its support is perpendicular
        // to facing, not behind it. The rule below would aim the bot at a face that holds nothing.
        if (desired.getBlock() instanceof net.minecraft.world.level.block.WallHangingSignBlock) {
            return null;
        }
        // A HOPPER's facing comes from the face you CLICK, not from where you look:
        // HopperBlock.getStateForPlacement is getClickedFace().getOpposite() (verified in 26.1.2 bytecode, with a
        // vertical result collapsed to DOWN). So the support is determined outright -- click the face F.getOpposite()
        // of the block at T+F -- and there is nothing to search for. etz-basalt has 140 hoppers, all horizontal, and
        // every one of them was being tried against all six faces with the look treated as if it mattered.
        //
        // Keyed on HopperBlock.FACING and NOT on BlockStateProperties.FACING: they are DIFFERENT Property objects.
        // HopperBlock.FACING is BlockStateProperties.FACING_HOPPER (five values, no UP), and StateHolder.valueIndex
        // scans propertyKeys[] with if_acmpne -- by IDENTITY, never Property.equals, which compares class and name and
        // would have matched. So hasProperty(BlockStateProperties.FACING) is false for every hopper in the game, this
        // branch never fired, and all six faces were tried with the look treated as if it mattered.
        if (desired.getBlock() instanceof HopperBlock && desired.hasProperty(HopperBlock.FACING)) {
            Direction facing = desired.getValue(HopperBlock.FACING);
            // facing IS the support direction: the click lands on the face of T+facing that points at T, and vanilla
            // reads that face's opposite. A horizontal hopper therefore has exactly ONE neighbour that can produce it.
            if (facing.getAxis() != Direction.Axis.Y) {
                return facing;
            }
            // facing=down is produced by ANY vertical click -- the top of the block BELOW or the underside of the
            // block ABOVE -- and one Direction cannot say "either". Null keeps both; simulate then refuses the four
            // lateral faces by name. (The old `return Direction.UP` kept only the rarer of the two.)
            return null;
        }
        // Signs, torches, ladders, banners, skulls: facing points AWAY from the wall they hang on.
        if (isWallMounted(desired) && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return desired.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        return null;
    }

    /**
     * The faces worth trying for this state: the one the state determines, or all six ORDERED when it determines none.
     *
     * <p>Ordered, never narrowed, for look-derived facings. Narrowing was tried and it is the most expensive mistake
     * in this file's history: constraining the neighbour to the facing axis starved 624 of etz-basalt's 672 sticky
     * pistons of every clickable face at once. Clicking the neighbour at T+L does send the ray straight down L with
     * both competing axes clamped to zero, which is why it comes first — but the other five are what made those
     * pistons placeable at all.
     */
    public static Direction[] supportDirectionsFor(BlockState desired) {
        Direction only = requiredSupportDirection(desired);
        if (only != null) {
            return new Direction[] { only };
        }
        // NOT narrowed for look-derived facings. That was tried and it was the single most expensive mistake in this
        // file's history: see requiredLookDirections for the measurement. A block whose facing comes from the LOOK can
        // be clicked against ANY neighbour, and constraining the neighbour to the facing axis starved 624 of
        // etz-basalt's 672 sticky pistons of every clickable face at once.
        //
        // Ordered rather than narrowed, though, because one face IS better than the rest. Clicking the neighbour at
        // T+L, where L is the look direction, sends the ray straight along L: the two competing axes clamp to zero and
        // the aim is dominant by the full 1.5 blocks. Every other face wins on a margin -- for a horizontal look
        // against a lateral face it comes down to 0.52 against 0.50, which is two hundredths of a block between the
        // right facing and the wrong one. So try the sure thing first and keep the others as the fallback that made
        // those pistons placeable at all.
        Direction[] looks = requiredLookDirections(desired);
        if (looks == null) {
            return Direction.values();
        }
        Direction[] ordered = new Direction[Direction.values().length];
        int at = 0;
        for (Direction look : looks) {
            ordered[at++] = look;
        }
        for (Direction d : Direction.values()) {
            boolean alreadyFirst = false;
            for (Direction look : looks) {
                alreadyFirst |= d == look;
            }
            if (!alreadyFirst) {
                ordered[at++] = d;
            }
        }
        return ordered;
    }

    /**
     * The directions the bot must be LOOKING to get this state's facing, or null when the look does not decide it.
     *
     * <p>These are look directions, not faces to click, and the difference is the whole correction. An earlier version
     * of this method returned {@code {facing.getOpposite(), facing}} as the *support* faces, reasoning that the ray
     * runs along L, through the target cell, and strikes the block beyond it. That is one way to do it and it is not
     * the requirement. Vanilla asks only two things, and they are independent: the click must land on a face of a
     * neighbour pointing at the cell (any of the six), and the look's dominant axis must be L. Tying the neighbour to
     * the facing axis threw away the other four.
     *
     * <p>What that cost, measured on etz-basalt: 624 of its 672 sticky pistons are {@code facing=up} in a single row
     * with AIR beneath them in the schematic and their solid neighbour ABOVE, which a bottom-up build has not reached
     * yet. So both faces the rule allowed were air, and the stance report read "of 145 candidate stances: 49 standable,
     * 0 would work [49 nothing solid to click]" -- 49 out of 49, with every other rejection counter at zero. 1548 of
     * that run's 1685 stance failures were this one block. The lateral neighbours were standing there the whole time.
     *
     * <p>Both signs are returned rather than one, because vanilla genuinely is not consistent about which way round it
     * reads the look, and the inconsistency is not guessable. Verified in 26.1.2 bytecode: a PISTON is
     * {@code getNearestLookingDirection().getOpposite()}, while an OBSERVER is
     * {@code getNearestLookingDirection().getOpposite().getOpposite()} -- a double opposite that cancels, so an
     * observer faces exactly where you look and a piston faces exactly away. Two blocks that look like the same family
     * and take opposite conventions. Returning both signs and letting the simulation pick is what makes that
     * survivable; hardcoding per block class would be a list to get wrong for every block nobody thought of.
     *
     * <p>ONLY for the blocks that read the DOMINANT axis of the look. It does not apply to anything that reads only the
     * yaw -- doors, repeaters, comparators, stairs. There the pitch is free, so the bot can face north while aiming
     * steeply down at the block BELOW the cell, and nothing about the aim is determined.
     *
     * @see #mostAlignedPointOnFace which turns one of these into the exact point to aim at
     */
    public static Direction[] requiredLookDirections(BlockState desired) {
        // The six-way FACING property AND a block that derives it from getNearestLookingDirection. Both conditions
        // matter: the property alone would catch shulker boxes and end rods, the block alone would miss nothing but
        // reads less clearly.
        if (!desired.hasProperty(BlockStateProperties.FACING)) {
            return null;
        }
        Block block = desired.getBlock();
        if (!(block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)
                && !(block instanceof net.minecraft.world.level.block.DispenserBlock)
                && !(block instanceof ObserverBlock)) {
            return null;
        }
        Direction facing = desired.getValue(BlockStateProperties.FACING);
        return new Direction[] { facing.getOpposite(), facing };
    }

    // -------------------------------------------------------------------------------- the four-way yaw convention

    /**
     * How vanilla turns the player's yaw into a four-way {@code HORIZONTAL_FACING}.
     *
     * <p>The six-way {@code FACING} family reads the DOMINANT axis of the look and is handled by
     * {@link #requiredLookDirections}. This is its horizontal sibling, and it is a different question: a four-way
     * facing comes from {@code BlockPlaceContext.getHorizontalDirection()}, which is {@code Direction.fromYRot(yaw)} —
     * the yaw alone, the pitch free. Vanilla then applies one of exactly three transforms to it, and WHICH one is a
     * per-block fact that cannot be derived from the state.
     */
    public enum YawFacing {

        /** {@code FACING = getHorizontalDirection()}. A stair, a door and a bed face where the player looks. */
        TOWARDS_LOOK,

        /** {@code FACING = getHorizontalDirection().getOpposite()}. A repeater, a furnace and a chest face back at
         *  the player who placed them. */
        AWAY_FROM_LOOK,

        /** {@code FACING = getHorizontalDirection().getClockWise()}. The anvil, and nothing else in 26.1.2. */
        CLOCKWISE_FROM_LOOK
    }

    /**
     * Which of the three conventions this block uses, or null when this file does not know.
     *
     * <p>Every entry was read out of 26.1.2's own {@code getStateForPlacement} bytecode, not assumed, for the same
     * reason {@link #AUTO_RESOLVED_PROP_NAMES} was: a wrong entry here is a whole family placed a quarter turn out.
     * The two halves of the list look alike and behave oppositely — a DOOR faces where you look and a TRAPDOOR faces
     * away, and they sit in adjacent doorways — so neither side may be inferred from the other.
     *
     * <p><b>Null is a real answer and it is the safe one.</b> The engine used to copy the schematic's own facing into
     * the predicted state, which made {@code predicted.facing == desired.facing} true by construction for every stance
     * and turned the whole property into a guess that the live gate then had to catch. A block this list does not
     * name keeps the item's DEFAULT facing through {@link PlacementOracle#simulate}, so the acceptance test refuses
     * every stance and the cell is reported as a named blocker at plan time. An honest stop beats a wrong block.
     *
     * <p>Deliberately absent, all of them verified as NOT a plain yaw read: {@code BellBlock} (its facing switches
     * between the yaw and the clicked face depending on which face is hit), {@code CocoaBlock} and
     * {@code TripWireHookBlock} (they walk {@code getNearestLookingDirections} against the world),
     * {@code BaseCoralWallFanBlock}, {@code SegmentableBlock} (pink petals, leaf litter: the yaw read is real, but
     * the segment count that goes with it is neighbour-dependent), {@code AttachedStemBlock} and
     * {@code BigDripleafStemBlock} (no item places them at all). Wall-mounted blocks are excluded by
     * {@link #yawLookDirection} rather than here, because the block class is the same one that stands on the floor.
     */
    public static YawFacing yawFacingConvention(Block block) {
        if (block == null) {
            return null;
        }
        // Faces where you look.
        if (block instanceof StairBlock || block instanceof DoorBlock || block instanceof BedBlock
                || block instanceof FenceGateBlock || block instanceof CampfireBlock
                || block instanceof DecoratedPotBlock || block instanceof CalibratedSculkSensorBlock
                // Buttons, levers and the grindstone, but only when they stand on a floor or hang from a ceiling;
                // the WALL variant takes its facing from the clicked face and yawLookDirection refuses it.
                || block instanceof FaceAttachedHorizontalDirectionalBlock) {
            return YawFacing.TOWARDS_LOOK;
        }
        // Faces back at you. DiodeBlock covers repeater and comparator, AbstractFurnaceBlock covers furnace, smoker
        // and blast furnace, ChestBlock covers trapped and copper chests, and each Weathering* variant extends the
        // class it weathers, so the instanceof chain covers all 59 registry entries these classes account for.
        if (block instanceof DiodeBlock || block instanceof AbstractFurnaceBlock || block instanceof ChestBlock
                || block instanceof EnderChestBlock || block instanceof TrapDoorBlock
                || block instanceof GlazedTerracottaBlock || block instanceof CarvedPumpkinBlock
                || block instanceof EndPortalFrameBlock || block instanceof StonecutterBlock
                || block instanceof LoomBlock || block instanceof LecternBlock
                || block instanceof ChiseledBookShelfBlock || block instanceof ShelfBlock
                || block instanceof VaultBlock || block instanceof BeehiveBlock
                || block instanceof BigDripleafBlock || block instanceof SmallDripleafBlock
                || block instanceof DriedGhastBlock || block instanceof CopperGolemStatueBlock) {
            return YawFacing.AWAY_FROM_LOOK;
        }
        if (block instanceof AnvilBlock) {
            return YawFacing.CLOCKWISE_FROM_LOOK;
        }
        return null;
    }

    /** The facing this block would land with for a given horizontal look, or null when the convention is unknown.
     *  The forward direction of {@link #yawLookDirection}, and the two must stay exact inverses — a search that ranks
     *  stances by one and a simulation that predicts by the other would disagree cell by cell. */
    public static Direction yawFacingFor(Block block, Direction look) {
        YawFacing convention = yawFacingConvention(block);
        if (convention == null || look == null || look.getAxis() == Direction.Axis.Y) {
            return null;
        }
        switch (convention) {
            case TOWARDS_LOOK:
                return look;
            case AWAY_FROM_LOOK:
                return look.getOpposite();
            default:
                return look.getClockWise();
        }
    }

    /**
     * The ONE horizontal look that produces this state's four-way facing, or null when the yaw does not decide it.
     *
     * <p>The horizontal counterpart of {@link #requiredLookDirections}, and the reason it returns one direction where
     * that one returns two: the six-way family genuinely has two candidate signs because vanilla is inconsistent about
     * them and the pure oracle cannot tell which, whereas here the convention is named per block and the sign follows
     * from it. That single direction is what closes the hole this method was written for — the planner used to score
     * only the AXIS of a four-way facing, so a stance two cells NORTH of a {@code facing=north} stair and a stance two
     * cells SOUTH of it scored identically, the tiebreak took the lower coordinate, and the bot stood looking SOUTH
     * while the plan claimed NORTH. Re-planning is deterministic, so it made the same wrong choice forever.
     *
     * <p>Null for a wall-mounted state, and for the WALL variant of a button, lever or grindstone: those read the
     * clicked FACE, not the yaw. Null for a block {@link #yawFacingConvention} does not name.
     */
    public static Direction yawLookDirection(BlockState desired) {
        if (desired == null || !desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return null;
        }
        if (desired.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            // A button, lever or grindstone reads the YAW when it stands on a floor or hangs from a ceiling and the
            // clicked FACE when it is on a wall. The state says which outright, so isWallMounted's blanket answer --
            // which is a deferral heuristic about hanging decorations last, not a rule about facings -- is not
            // consulted for these.
            if (desired.getValue(BlockStateProperties.ATTACH_FACE) == AttachFace.WALL) {
                return null;
            }
        } else if (isWallMounted(desired)) {
            return null;
        }
        YawFacing convention = yawFacingConvention(desired.getBlock());
        if (convention == null) {
            return null;
        }
        Direction facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
        switch (convention) {
            case TOWARDS_LOOK:
                return facing;
            case AWAY_FROM_LOOK:
                return facing.getOpposite();
            default:
                return facing.getCounterClockWise();
        }
    }

    /**
     * Does placing this state require the bot to look UPWARD? If so it has to stand BELOW the cell, and the usual
     * one-block stance window cannot contain such a stance.
     *
     * <p>The bound, exactly: the highest aimable point on any neighbour of T is the underside of T+UP at
     * {@code T.y + 1.0}, and the crouched eye is feet + 1.27, so {@code feet < T.y - 0.27}, i.e. every upward stance
     * stands at least one full layer below T. Without exception.
     *
     * <p>V2's arithmetic for the lateral case, which is what forced the window to reach dy=-2: for a support face
     * whose top edge is the highest point on it worth aiming at, level with the cell the crouched eye sits 1.27 above
     * the feet and the edge only 0.98, so the aim points DOWN. One below, the eye is 0.27 under the edge against a
     * horizontal 1.5 -- still not dominant. Two below it is 1.71 against 0.5 from directly beneath, which is dominant
     * by a wide margin. So the window has to reach dy=-2 to contain a working stance at all, and -3 to have any choice
     * among them.
     *
     * <p>Only asked for the vertical half of the family, because the horizontal facings never need it: their look is
     * level, and a level look is available from a level stance.
     */
    public static boolean needsUpwardLook(BlockState desired) {
        if (desired == null || !desired.hasProperty(BlockStateProperties.FACING)) {
            return false;
        }
        Block block = desired.getBlock();
        Direction facing = desired.getValue(BlockStateProperties.FACING);
        if (block instanceof ObserverBlock) {
            // ObserverBlock.getStateForPlacement applies getOpposite twice, so the observer faces exactly where the
            // player looks.
            return facing == Direction.UP;
        }
        if (block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || block instanceof net.minecraft.world.level.block.DispenserBlock) {
            // Pistons, dispensers and droppers face away from the dominant look direction.
            return facing == Direction.DOWN;
        }
        return false;
    }

    /**
     * The deepest {@link #lowestStanceOffset} any family asks for.
     *
     * <p>The bound over the per-state answer, for the one caller that has to reason about the window WITHOUT a state
     * in hand: {@link PlacementOracle#withinStanceWindow} screens a (stance, cell) pair before the cell's desired
     * state has been read. Derived from the same number below rather than written twice.
     */
    public static final int DEEPEST_STANCE_OFFSET = -3;

    /** Lowest stance worth considering for this state, relative to the cell: -3 for the upward family, -1 otherwise.
     *  Widening the window unconditionally would cost 67% more standability tests across the whole build to serve 4%
     *  of it. @see #needsUpwardLook */
    public static int lowestStanceOffset(BlockState desired) {
        // -3 rather than -1 for these, and -1 for everything else: the window is walked for every candidate cell, so
        // widening it unconditionally would cost 67% more standability tests on the whole build to serve 4% of it.
        return needsUpwardLook(desired) ? DEEPEST_STANCE_OFFSET : -1;
    }

    /** X=0, Y=1, Z=2. Written out rather than {@code Axis.ordinal()} so a reordered vanilla enum cannot silently
     *  reinterpret every aim point in this file. */
    public static int axisIndex(Direction.Axis axis) {
        switch (axis) {
            case X: return 0;
            case Y: return 1;
            default: return 2;
        }
    }

    // ------------------------------------------------------------------------------------------------ aim points

    /**
     * The point on a support face that makes {@code look} the dominant axis of the aim, or null if this face cannot.
     *
     * <p>A derivation with a closed form, replacing five sampled points and a hope. The aim direction is
     * {@code d = P - eye} and vanilla's {@code getNearestLookingDirection} picks whichever axis of {@code d} is largest,
     * so the job is to choose {@code P} on the face rectangle maximising {@code d} along {@code look} while shrinking
     * the other two components. Each axis has exactly one right answer:
     *
     * <ul>
     *   <li>the face's own normal axis -- fixed, it is the plane the face lies in;
     *   <li>the look's axis, when free -- the far EDGE in that direction, which is what makes the look steep;
     *   <li>the remaining axis -- clamped to the eye, since any offset there is pure competition.
     * </ul>
     *
     * <p>Worked through for the 624 pistons: face is the lateral neighbour's side (normal X), look is DOWN, so the
     * point is the BOTTOM edge of that side face, level in Z with the eye. From a stance one block above the cell and
     * next to it the aim comes out at {@code d = (1.5, -2.25, 0)} -- down by a clear margin, so the piston lands
     * {@code facing=up}. The old sampled points offered y = 0.25, 0.5 and 0.75 of that same face and nothing lower, so
     * against a crouched eye 1.27 up the vertical component could never exceed the horizontal 1.5. Not one of them
     * could have worked, from any stance, ever.
     *
     * <p>Dominance is compared here rather than by asking vanilla, and strictly: the look's component must EXCEED both
     * others, where {@code getNearest} would accept a tie and break it by enum order. A tie is a coin flip decided by
     * floating point, and this is the one decision in the build that must not be a coin flip.
     */
    public static Vec3 mostAlignedPointOnFace(Vec3 eye, BlockPos againstPos, AABB aabb, Direction faceNormal,
                                              Direction look) {
        double[] min = { againstPos.getX() + aabb.minX, againstPos.getY() + aabb.minY, againstPos.getZ() + aabb.minZ };
        double[] max = { againstPos.getX() + aabb.maxX, againstPos.getY() + aabb.maxY, againstPos.getZ() + aabb.maxZ };
        double[] e = { eye.x, eye.y, eye.z };
        int fixed = axisIndex(faceNormal.getAxis());
        int lookAxis = axisIndex(look.getAxis());
        double[] p = new double[3];
        for (int a = 0; a < 3; a++) {
            if (a == fixed) {
                p[a] = faceNormal.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max[a] : min[a];
            } else if (a == lookAxis) {
                p[a] = look.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? max[a] - FACE_INSET : min[a] + FACE_INSET;
            } else {
                p[a] = Math.min(Math.max(e[a], min[a] + FACE_INSET), max[a] - FACE_INSET);
            }
        }
        double[] d = { p[0] - e[0], p[1] - e[1], p[2] - e[2] };
        double along = look.getStepX() * d[0] + look.getStepY() * d[1] + look.getStepZ() * d[2];
        for (int a = 0; a < 3; a++) {
            // Also rejects along <= 0 on its own: an aim pointing the wrong way down the look axis loses to any
            // non-negative competitor, including zero.
            if (a != lookAxis && Math.abs(d[a]) >= along) {
                return null;
            }
        }
        return new Vec3(p[0], p[1], p[2]);
    }

    /**
     * The same point, plus the number V2 computes and discards: how much the dominant axis wins by.
     *
     * <pre>margin = |d[lookAxis]| - max(|d[a]|) for a != lookAxis</pre>
     *
     * <p>In blocks. This is the single most load-bearing addition in the package, because the planner selects on it
     * rather than taking the first face that clears the comparison. Measured margins of 0.02 are real — that is two
     * hundredths of a block between a piston facing up and a piston facing sideways, decided by whatever the crouch
     * interpolation and the aim curve's residual happen to leave. A solution at 0.41 survives all of it; one at 0.02
     * is a coin flip that the plan has no business taking when a better stance was available for the asking.
     *
     * <p>The point itself is the same closed form, character for character; only the verdict is reported as a number
     * instead of a null. The margin is computed from the SIGNED projection {@code along = look . d} rather than from
     * {@code |d[lookAxis]|}, and that is what makes the two methods agree exactly rather than approximately: for a
     * point that is accepted the two are identical, because {@code along >= 0} there and {@code |d[lookAxis]| = along}
     * for a unit-axis look. For a point aiming backwards down the look axis they differ, and it is this form that is
     * right — {@code along} is negative, so every non-negative competitor beats it and the point is refused, which is
     * precisely what {@link #mostAlignedPointOnFace}'s {@code >=} comparison does. Taking the absolute value instead
     * would hand a positive margin to an aim pointing the wrong way.
     *
     * <p>The rejection is therefore literally the same test: V2 returns null iff some {@code |d[a]| >= along}, i.e.
     * iff {@code max|d[a]| >= along}, i.e. iff {@code margin <= 0}. IEEE-754 subtraction preserves the sign of a
     * comparison exactly, so there is no window in which one accepts and the other refuses.
     *
     * @return the point and its margin, or null when this face cannot make {@code look} dominant at all
     */
    public static AlignedPoint mostAlignedPointOnFaceWithMargin(Vec3 eye, BlockPos againstPos, AABB aabb,
                                                                Direction faceNormal, Direction look) {
        double[] min = { againstPos.getX() + aabb.minX, againstPos.getY() + aabb.minY, againstPos.getZ() + aabb.minZ };
        double[] max = { againstPos.getX() + aabb.maxX, againstPos.getY() + aabb.maxY, againstPos.getZ() + aabb.maxZ };
        double[] e = { eye.x, eye.y, eye.z };
        int fixed = axisIndex(faceNormal.getAxis());
        int lookAxis = axisIndex(look.getAxis());
        double[] p = new double[3];
        for (int a = 0; a < 3; a++) {
            if (a == fixed) {
                p[a] = faceNormal.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max[a] : min[a];
            } else if (a == lookAxis) {
                p[a] = look.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? max[a] - FACE_INSET : min[a] + FACE_INSET;
            } else {
                p[a] = Math.min(Math.max(e[a], min[a] + FACE_INSET), max[a] - FACE_INSET);
            }
        }
        double[] d = { p[0] - e[0], p[1] - e[1], p[2] - e[2] };
        double along = look.getStepX() * d[0] + look.getStepY() * d[1] + look.getStepZ() * d[2];
        double strongestCompetitor = 0.0D;
        for (int a = 0; a < 3; a++) {
            if (a != lookAxis) {
                strongestCompetitor = Math.max(strongestCompetitor, Math.abs(d[a]));
            }
        }
        double margin = along - strongestCompetitor;
        if (margin <= 0.0D) {
            return null;
        }
        return new AlignedPoint(new Vec3(p[0], p[1], p[2]), margin);
    }

    /**
     * An aim point and the dominance slack it buys, in blocks.
     *
     * @param point  the exact world-space point to aim at
     * @param margin {@code |d[look]| - max(|d[other]|)}; strictly positive by construction, since a non-positive
     *               value means the look is not dominant and the factory returns null instead
     */
    public record AlignedPoint(Vec3 point, double margin) {
    }

    /**
     * Every point on this support face worth casting a ray at, in world coordinates.
     *
     * <p>One method for both callers by construction. The stance search and the live click each used to walk
     * {@link #aabbSideMultipliers} themselves, and a builder that decides where to stand by one rule and what to click
     * by another routes the bot to cells it then cannot place — the failure mode this file has paid for more than
     * once.
     */
    public static List<Vec3> aimPointsOnFace(Vec3 eye, BlockPos againstPos, AABB aabb, Direction against,
                                             BlockState desired) {
        Direction[] looks = requiredLookDirections(desired);
        if (looks == null) {
            List<Vec3> sampled = new ArrayList<>(5);
            for (Vec3 m : aabbSideMultipliers(against)) {
                sampled.add(new Vec3(
                        againstPos.getX() + aabb.minX * m.x + aabb.maxX * (1 - m.x),
                        againstPos.getY() + aabb.minY * m.y + aabb.maxY * (1 - m.y),
                        againstPos.getZ() + aabb.minZ * m.z + aabb.maxZ * (1 - m.z)));
            }
            return sampled;
        }
        // At most one point per candidate look, and usually one in total, because the opposite sign almost always aims
        // backwards and is dropped without a raycast. Six faces x 2 looks replaces six x 5 sampled points, and the
        // points it does return are the only ones that could have produced the wanted facing anyway.
        Direction faceNormal = against.getOpposite();
        List<Vec3> derived = new ArrayList<>(looks.length);
        for (Direction look : looks) {
            Vec3 p = mostAlignedPointOnFace(eye, againstPos, aabb, faceNormal, look);
            if (p != null) {
                derived.add(p);
            }
        }
        return derived;
    }

    /**
     * The same, with each point's margin, for the orientation-sensitive path. Points whose look is not dominant are
     * absent rather than present with a negative margin — a candidate that cannot produce the wanted facing is not a
     * worse candidate, it is not a candidate.
     *
     * <p>Returns the same points in the same order as {@link #aimPointsOnFace} for every input, including the states
     * whose facing the look does NOT decide. Those get {@link Double#POSITIVE_INFINITY}, which is not a placeholder:
     * there is no dominance contest to win, so the aim cannot lose one, and any finite number would make a full cube
     * look like it were competing for a facing it does not have. It also keeps every downstream use honest by
     * construction — a {@code MIN_MARGIN} filter passes it, {@code isTight} refuses it, and the tight-cell report never
     * names a block that has no orientation to get wrong. What it deliberately does NOT do is let the caller rank
     * unconstrained candidates against each other; that is reach's job, not the margin's.
     */
    public static List<AlignedPoint> aimPointsOnFaceWithMargin(Vec3 eye, BlockPos againstPos, AABB aabb,
                                                               Direction against, BlockState desired) {
        Direction[] looks = requiredLookDirections(desired);
        if (looks == null) {
            List<AlignedPoint> sampled = new ArrayList<>(5);
            for (Vec3 point : aimPointsOnFace(eye, againstPos, aabb, against, desired)) {
                sampled.add(new AlignedPoint(point, Double.POSITIVE_INFINITY));
            }
            return sampled;
        }
        Direction faceNormal = against.getOpposite();
        List<AlignedPoint> derived = new ArrayList<>(looks.length);
        for (Direction look : looks) {
            AlignedPoint p = mostAlignedPointOnFaceWithMargin(eye, againstPos, aabb, faceNormal, look);
            if (p != null) {
                derived.add(p);
            }
        }
        return derived;
    }

    /** The five sampled points on a face, as fractions of its AABB: centre plus four offsets. Two points down the
     *  middle of a side face is a thin target — everything that hangs ON a wall can only be placed by hitting that
     *  face, and from a stance beside it a two-point sample produced 78 "ray missed the face" against 0 out-of-reach
     *  on the lighthouse's wall torches. */
    public static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                // Two points down the middle of a side face is a thin target. Everything that hangs ON a wall --
                // torches, signs, banners, ladders, buttons -- can only be placed by hitting that face, and from a
                // stance beside it the ray arrives at a steep angle and clips the block's top or a neighbour
                // instead: 78 "ray missed the face" against 0 out-of-reach on the lighthouse's wall torches. Offer
                // the centre and the lateral thirds as well, so a stance that can see any part of the face works.
                double lateralA = side.getStepX() == 0 ? 0.25 : x;
                double lateralB = side.getStepX() == 0 ? 0.75 : x;
                double lateralAz = side.getStepZ() == 0 ? 0.25 : z;
                double lateralBz = side.getStepZ() == 0 ? 0.75 : z;
                return new Vec3[]{
                        new Vec3(x, 0.5, z),
                        new Vec3(x, 0.25, z),
                        new Vec3(x, 0.75, z),
                        new Vec3(lateralA, 0.5, lateralAz),
                        new Vec3(lateralB, 0.5, lateralBz),
                };
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    // -------------------------------------------------------------------------------------------------- the body

    /**
     * Check A1 of the four approach conditions: would the bot's own body be inside the block it is about to place?
     *
     * <p>Vanilla's {@code isUnobstructed} tests the collision shape of the PLACED block against every entity in the
     * way, and the bot is one of them, so a stance whose body overlaps the target cell refuses its own placement. That
     * is a silent trap in V2 rather than a diagnostic: {@code BlockItem.getPlacementState} folds {@code canSurvive} and
     * {@code isUnobstructed} into one null, which the old code reported as "canPlace false" — i.e. "the target cell is
     * not replaceable", pointing at an obstruction that did not exist. A {@code soul_sand} cell in open air sent the
     * stance search hunting for a blockage that was the bot itself.
     *
     * <p>Measured against the real collision shape, never a full cube, and that is an extension rather than a
     * translation: a bottom slab occupies only {@code T.y..T.y+0.5}, so a body standing in the upper half of T is
     * legal, and a full-cube test would throw that stance away for nothing. Shapes that are empty — torches, signs,
     * redstone — can never obstruct anything and fall out of this for free.
     *
     * <p>The width is not a parameter because there is only one: vanilla's player is 0.6 wide standing and crouched
     * alike. Only the height changes with the pose, which is why the height IS one.
     *
     * @param approach     the exact sub-block position the feet stand at, as the planner chose it
     * @param crouchHeight body height in the pose the placement is planned for; vanilla crouched is 1.5
     * @param cell         the cell the block is going into, which is what {@code placedShape} is relative to
     * @param placedShape  the collision shape of the state being placed, in block-local coordinates
     */
    public static boolean playerBodyIntersects(Vec3 approach, double crouchHeight, BlockPos cell,
                                               VoxelShape placedShape) {
        if (placedShape.isEmpty()) {
            return false;
        }
        AABB body = PlayerPose.CROUCHED.bodyAt(approach, crouchHeight);
        // Vanilla's own test, verbatim in form: Level.isUnobstructed intersects the moved collision shape with the
        // entity box through Shapes.joinIsNotEmpty(..., AND). Written this way rather than as an AABB sweep so that
        // touching faces resolve exactly as they do in the client -- a body resting ON a slab shares the plane y+0.5
        // with it and must not count as inside it.
        return Shapes.joinIsNotEmpty(placedShape.move(cell.getX(), cell.getY(), cell.getZ()),
                Shapes.create(body), BooleanOp.AND);
    }

    // ---------------------------------------------------------------------------------- state classification

    /** Can an item that places {@code available} also place {@code desired}? Compares the placing ITEM, not the block:
     *  one {@code torch} item makes {@code torch} on the ground and {@code wall_torch} on a side, and the same holds
     *  for every sign, banner, head and coral fan. Blocks with no item form (fire, piston heads, redstone wire) fall
     *  through to the identity check. */
    public static boolean itemCanPlaceBlock(BlockState available, BlockState desired) {
        if (available.getBlock() == desired.getBlock()) {
            return true;
        }
        net.minecraft.world.item.Item availableItem = available.getBlock().asItem();
        return availableItem != net.minecraft.world.item.Items.AIR
                && availableItem == desired.getBlock().asItem();
    }

    /** Does this state need the deliberate stance search rather than any adjacent cell? */
    public static boolean shouldUseStanceRecovery(BlockState state, boolean fullCollisionCube) {
        return !fullCollisionCube || placementStateIsGeometrySensitive(state);
    }

    /** Does anything about this state depend on where the bot stands and what it clicks — i.e. does it carry a
     *  property outside {@link #AUTO_RESOLVED_PROP_NAMES}? Null counts as sensitive: unknown is not safe. */
    public static boolean placementStateIsGeometrySensitive(BlockState state) {
        if (state == null) {
            return true;
        }
        for (Property<?> property : state.getProperties()) {
            if (!AUTO_RESOLVED_PROP_NAMES.contains(property.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Which blocks the pathfinder's default "get adjacent" GOAL gets wrong — not which blocks have a facing. Read
     *  {@link #yawLookDirection} for the second question; the two are different and were once conflated here, which is
     *  what let a stair be planned from a stance that could only place it backwards. V3's oracle ranks its stances by
     *  the look direction and never by this list; this is the goal predicate and nothing else.
     *
     *  <p>Blocks whose final facing is chosen by the player's yaw at placement, AND that the default "get adjacent"
     *  goal gets wrong: doors, beds, redstone diodes, observers, trapdoors. Pistons and dispensers are excluded on purpose
     *  — adding them cost etz-basalt 400 blocks, twice (0.3.59 to 0.3.60 and again later). They do take their facing
     *  from the look, but routing all 2048 of basalt's pistons through the oriented stance search replaces a cheap
     *  goal with a specific stance that usually does not exist yet, so the bot idles instead of building what it can.
     *  Piston orientation belongs in the stance ORDERING, which costs nothing when no stance fits. Do not "fix" this
     *  without re-measuring. */
    public static boolean isOrientationSensitive(BlockState state) {
        if (state == null) {
            return false;
        }
        // ONLY the blocks whose facing genuinely depends on WHERE the player stands AND that the default "get
        // adjacent" goal gets wrong: doors, beds, redstone diodes (repeater/comparator), observers, trapdoors.
        // Everything else with a facing (stairs, chests, furnaces, ...) is reached fine by the default goal -- NOT
        // "places fine from any side", which is what this comment used to claim and is false: a stair takes its facing
        // from the look like every other four-way block. Which side the bot must stand on is the ORACLE's question and
        // yawLookDirection answers it. Running the standing GOAL for them is pure overhead — and worse, its
        // over-approximation can park the bot idle on a
        // false-positive stance (the "just stands there" the user hit live). Keep this list tight.
        Block b = state.getBlock();
        // Pistons and dispensers were added here once and it cost etz-basalt 400 blocks: 1438 placed fell to 1025 and
        // stayed there when the change was the only difference. They do take their facing from
        // getNearestLookingDirection, so the reasoning was sound -- but routing all 2048 of basalt's pistons through
        // the oriented standing search replaces a cheap GoalAdjacent with a specific stance, and in a sparse layer that
        // stance usually does not exist yet, so the bot idles instead of building what it can.
        //
        // The project has been here before (0.3.59 -> 0.3.60, same list, same idle). The lesson holds: this list is
        // about which blocks the DEFAULT goal gets wrong, not which blocks have a facing. Piston orientation is handled
        // where it belongs -- in the stance ordering, which costs nothing when no stance fits.
        return b instanceof DoorBlock || b instanceof BedBlock || b instanceof DiodeBlock
                || b instanceof ObserverBlock || b instanceof TrapDoorBlock;
    }

    /** An interaction whose open/closed state changes whether the bot can walk through or over the block — door,
     *  trapdoor, fence gate. Repeater delay, comparator mode and note pitch never affect walking. */
    public static boolean isTraversalInteraction(BlockState desired) {
        if (desired == null) {
            return false;
        }
        Block b = desired.getBlock();
        return b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock;
    }

    /** The UPPER half of a door or the HEAD of a bed — the second cell of a two-block placement that vanilla fills in
     *  when the base goes down. It can never be placed directly, so it must never be planned as its own action. */
    public static boolean isSecondaryHalf(BlockState desired) {
        if (desired == null) {
            return false;
        }
        Block b = desired.getBlock();
        if (b instanceof DoorBlock) {
            return desired.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
        }
        if (b instanceof BedBlock) {
            return desired.getValue(BedBlock.PART) == BedPart.HEAD;
        }
        return false;
    }

    /**
     * Is this property beyond anything a builder can do — neither placeable nor right-clickable?
     *
     * <p>Almost all of the classification is by property name alone, but {@code open} is the one property whose class
     * depends on the block carrying it. A wooden door, trapdoor or fence gate opens under a right-click. An IRON one
     * moves only under redstone, so placement cannot set it and {@link #interactionClicks} correctly refuses it — and
     * a permanently invalid cell that still looks placeable gets broken and re-placed forever. That combination is a
     * trap the builder paid for: it broke and re-placed the same 40 iron trapdoors in etz-basalt for as long as the
     * run lasted, never advancing the layer.
     *
     * <p>An iron trapdoor placed closed IS built correctly. Whether it is open afterwards is the redstone's business.
     */
    public static boolean beyondOurControl(Block block, String prop) {
        if (AUTO_RESOLVED_PROP_NAMES.contains(prop)) {
            return true;
        }
        return "open".equals(prop) && (block == Blocks.IRON_DOOR || block == Blocks.IRON_TRAPDOOR);
    }

    /**
     * True when current already has the right block and every non-interaction, non-environment property matches — the
     * block is correctly placed and oriented, and only a right-click-settable state still differs.
     *
     * <p>Iterates {@code desired}'s properties where {@link V3Settings#sameBlockstate} iterates the current state's,
     * and ignores {@code buildIgnoreDirection} / {@code buildIgnoreProperties} entirely. Both asymmetries are
     * deliberate; do not unify them.
     */
    public static boolean matchesExceptInteraction(BlockState current, BlockState desired) {
        if (desired == null || current.getBlock() != desired.getBlock()) {
            return false;
        }
        for (Property<?> p : desired.getProperties()) {
            String n = p.getName();
            if (INTERACTION_PROP_NAMES.contains(n) || AUTO_RESOLVED_PROP_NAMES.contains(n)) {
                continue;
            }
            if (!current.getValue(p).equals(desired.getValue(p))) {
                return false;
            }
        }
        return true;
    }

    /**
     * How many right-clicks bring current's interactive state to desired's: repeater DELAY (+1 each, wraps 4 to 1),
     * comparator MODE (toggle), note-block NOTE (+1 each, wraps 24 to 0), trapdoor/door/gate OPEN (toggle). Returns 0
     * when already correct and -1 when this is not interaction-fixable — wrong block, wrong orientation, or an iron
     * door or trapdoor no bare hand can move.
     *
     * <p>Declared {@code static} here; the V2 original is an instance method that touches no instance state.
     */
    public static int interactionClicks(BlockState current, BlockState desired) {
        if (!matchesExceptInteraction(current, desired)) {
            return -1;
        }
        Block b = current.getBlock();
        if (b == Blocks.IRON_DOOR || b == Blocks.IRON_TRAPDOOR) {
            return -1; // only redstone toggles these — a right-click does nothing
        }
        if (b instanceof RepeaterBlock) {
            int d = current.getValue(RepeaterBlock.DELAY);
            int want = desired.getValue(RepeaterBlock.DELAY);
            return ((want - d) % 4 + 4) % 4;
        }
        if (b instanceof ComparatorBlock) {
            return current.getValue(ComparatorBlock.MODE) != desired.getValue(ComparatorBlock.MODE) ? 1 : 0;
        }
        if (b instanceof net.minecraft.world.level.block.DaylightDetectorBlock) {
            // Same shape as the comparator: one right-click flips it. Listed because `inverted` is in
            // INTERACTION_PROP_NAMES, and a property claimed there with no branch here returns -1 and sends the cell
            // to the break-and-replace path, which can never set it either -- an infinite loop instead of one click.
            return !current.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.INVERTED)
                    .equals(desired.getValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.INVERTED)) ? 1 : 0;
        }
        if (b instanceof NoteBlock) {
            int d = current.getValue(NoteBlock.NOTE);
            int want = desired.getValue(NoteBlock.NOTE);
            return ((want - d) % 25 + 25) % 25;
        }
        if (b instanceof TrapDoorBlock) {
            return current.getValue(TrapDoorBlock.OPEN) != desired.getValue(TrapDoorBlock.OPEN) ? 1 : 0;
        }
        if (b instanceof DoorBlock) {
            return current.getValue(DoorBlock.OPEN) != desired.getValue(DoorBlock.OPEN) ? 1 : 0;
        }
        if (b instanceof FenceGateBlock) {
            return current.getValue(FenceGateBlock.OPEN) != desired.getValue(FenceGateBlock.OPEN) ? 1 : 0;
        }
        return -1;
    }

    /** Does any state in this collection come from an item that could place {@code state}? */
    public static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (itemCanPlaceBlock(testee, state)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- acceptance, parameterised by the snapshot

    /**
     * Are these two states the same as far as the builder is concerned? Port of {@code BuilderProcess.sameBlockstate}
     * (:5123) with its two {@code Princeps.settings()} reads lifted into the snapshot parameter.
     *
     * <p>The only two predicates in this class that take a {@link V3Settings}, and they take it as an argument rather
     * than reading a global — which is the whole point. They are also the only two of V2's 24 acceptance statics that
     * were not referentially transparent; given the snapshot they are pure again, and this class stays testable.
     * {@link V3Settings#sameBlockstate} is the intended call site and delegates here, so there is exactly one
     * implementation of the rule.
     *
     * <p>Iterates {@code first}'s properties, not {@code second}'s, and {@link #matchesExceptInteraction} iterates the
     * other one. It also applies {@code buildIgnoreDirection} / {@code buildIgnoreProperties} where that one ignores
     * them entirely. Both asymmetries are deliberate; do not unify them.
     */
    public static boolean sameBlockstate(V3Settings settings, BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        if (first.equals(second)) {
            return true; // exact match — fast path
        }
        boolean ignoreDirection = settings.buildIgnoreDirection();
        List<String> ignoredProps = settings.buildIgnoreProperties();
        for (Property<?> prop : first.getProperties()) {
            if (first.getValue(prop).equals(second.getValue(prop))) {
                continue;
            }
            // environment-derived / interaction-only property: a placed fence/wall/pane/trapdoor is CORRECT regardless
            // of this value, so a mismatch here must never make the builder break-and-retry it forever.
            if (beyondOurControl(first.getBlock(), prop.getName())) {
                continue;
            }
            if (ignoreDirection && ORIENTATION_PROPS.contains(prop)) {
                continue;
            }
            if (ignoredProps.contains(prop.getName())) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Does {@code current} satisfy {@code desired}? Port of {@code BuilderProcess.valid} (:5244) with its five
     * {@code Princeps.settings()} reads lifted into the snapshot parameter.
     *
     * <p>{@code itemVerify} is not a convenience flag: passing {@code true} disables the {@code buildIgnoreExisting}
     * and {@code buildValidSubstitutes} escapes, so "would this click be accepted" and "is this cell done" genuinely
     * ask different questions of the same landed state. V2 passes {@code true} from {@code placementResultAccepted}
     * and {@code false} from {@code placementSatisfiedOrHandedOff} on purpose. Do not unify them.
     *
     * @param desired null means "outside the schematic", which is always satisfied
     */
    public static boolean valid(V3Settings settings, BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && settings.okIfWater()) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && settings.okIfAir().contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && settings.buildIgnoreBlocks().contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && settings.buildIgnoreExisting() && !itemVerify) {
            return true;
        }
        if (settings.buildValidSubstitutes().getOrDefault(desired.getBlock(), Collections.emptyList())
                .contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(settings, current, desired);
    }

    // ------------------------------------------------------------------------------------------------- identity

    /** The block's registry path, for reports and log lines. */
    public static String blockName(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** Collision-free coordinate identity within Minecraft's supported world bounds. Keep every V3 map keyed on this
     *  and never on a {@code BetterBlockPos}: its {@code hashCode} is {@code longHash}, so it and a plain
     *  {@code BlockPos} with identical coordinates hash differently and must never share a map. */
    public static long positionKey(BlockPos pos) {
        return pos.asLong();
    }

    /** @see #positionKey(BlockPos) */
    public static long positionKey(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }
}
