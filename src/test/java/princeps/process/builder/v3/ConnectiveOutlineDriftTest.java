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
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Why the gate says {@code WRONG_FACE} while the body stands on the proven point: the block being clicked has a
 * different OUTLINE in the live world than in the plan's.
 *
 * <p>Run {@code 9561f19f}, action 74 — a target block at {@code 125,-59,80}, placed against the WEST face of the
 * redstone wire at {@code 126,-59,80}. The gate refused it for 74 ticks and the run re-planned:
 *
 * <pre>
 * WRONG_FACE — wanted 126,-59,80 face west at 126.188,-58.969,80.344 to land minecraft:target[power=0],
 *              live ray hit 126,-59,80 face up  at 126.119,-58.938,80.272, aim point 0.104 from the hit
 * </pre>
 *
 * <p>The body was at {@code 123.506,-59.000,77.510} against a proven approach of {@code 123.50,-59.00,77.50} — 0.0117
 * blocks away, deep inside any arrival ball this engine can grant. So the ball is NOT what went wrong, and re-casting
 * the ray from the ball's rim — which {@link PlacementOracle} already does, eight times, in
 * {@code placementHoldsOverRim} — cannot catch this: every rim point casts against the same predicted world and gets
 * the same answer the centre did.
 *
 * <p>What differs is the wire. In the plan's world it is the unconnected DOT, one box,
 * {@code [3/16,13/16] x [0,1/16] x [3/16,13/16]}, and the aim is the centre-ish sample on that box's west face. In
 * the live world it had grown a WEST arm, and that arm's flat top stands exactly where the ray passes. The
 * acceptance rules do not care which of the two the wire is — {@code north/east/south/west} are in
 * {@link PlacementGeometry#AUTO_RESOLVED_PROP_NAMES}, the game's to resolve. The GEOMETRY is the whole difference
 * between a click that lands and one the gate refuses, and nothing in the engine checks it.
 *
 * <p>Both rays below are vanilla's own {@code BlockGetter.clip} with {@code OUTLINE}, the same call the live
 * crosshair makes, and both reproduce the trace's hit to the millimetre.
 *
 * <h2>What is measured here and what is not</h2>
 *
 * <p>MEASURED: the two shapes produce the two rays; the body was on the proven point; and the planner's placement
 * simulation never derives a connection, so every connective block the plan places is stored unconnected whatever
 * its neighbours are.
 *
 * <p>NOT MEASURED, and the reason no repair is committed with this: WHY the live wire had that arm. The schematic
 * leaves {@code 125,-60,80} and {@code 127,-60,80} empty, so in a world matching the plan vanilla would derive the
 * dot as well — which means teaching {@link PredictedWorld} to derive connections (the seam
 * {@code PlacementOracle.supportCollisionState} already uses for a stair) cannot be shown to fix THIS case. The
 * connection came from something the live world had and the captured one did not, and the artefacts of run
 * {@code 9561f19f} do not say what. Anyone repairing this starts by finding that out; the shape of the failure is
 * pinned below so that step is the only one left.
 */
public class ConnectiveOutlineDriftTest {

    /** {@code 9561f19f.r22-plan.txt} action 000072 places a wire here; action 000074 clicks its west face. */
    private static final BlockPos AGAINST = new BlockPos(126, -59, 80);

    /** Action 000074's proven approach: {@code from 123,-59,77 @123.50,-59.00,77.50}. */
    private static final Vec3 APPROACH = new Vec3(123.50D, -59.00D, 77.50D);

    /** {@code T 21695}: where the body actually stood while the gate refused. */
    private static final Vec3 MEASURED_BODY = new Vec3(123.506D, -59.000D, 77.510D);

    /** Action 000074's proven aim, to the ulp: {@code 126 + 3/16, -59 + 1/32, 80 + 11/32}. */
    private static final Vec3 PROVEN_AIM = new Vec3(126.1875D, -58.96875D, 80.34375D);

    /** The gate's report of where the live ray actually landed. */
    private static final Vec3 LIVE_HIT = new Vec3(126.119D, -58.938D, 80.272D);

    private static final Rotation REFERENCE = new Rotation(0.0F, 0.0F);

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

    /** The body was on the proven point, so no arrival-ball proof — rim or otherwise — has anything to bite on. */
    @Test
    public void theBodyWasOnTheProvenPointWhenTheGateRefused() {
        double offset = Math.sqrt(FineApproach.horizontalDistanceSq(MEASURED_BODY, APPROACH));

        assertTrue("1.2 cm from the planned point: inside every rung of the tolerance ladder, including the floor",
                offset < FineApproach.ACHIEVABLE_TOLERANCE);
        assertEquals("and on the proven feet plane, so the eye was the proven eye too",
                APPROACH.y, MEASURED_BODY.y, 1.0E-3D);
    }

    /** The planner's own aim generation produces the trace's aim point from the DOT, and nothing else does. */
    @Test
    public void theProvenAimPointIsAFaceOfTheUNCONNECTEDWire() {
        List<AABB> parts = OutlineGeometry.localParts(worldWith(dot()), AGAINST);
        assertEquals("an unconnected wire is one box", 1, parts.size());

        List<Vec3> sampled = PlacementGeometry.aimPointsOnFace(PlayerPose.CROUCHED.eyeAt(APPROACH), AGAINST,
                parts.get(0), Direction.EAST, Blocks.TARGET.defaultBlockState());
        assertTrue("the plan's aim is one of the five points the sampler offers on that face",
                sampled.stream().anyMatch(point -> point.distanceTo(PROVEN_AIM) < 1.0E-9D));
    }

    /**
     * The same eye, the same look, the two shapes — and the two rays are the two lines of the gate's complaint.
     *
     * <p>This is the whole defect in one assertion pair. Nothing about the body, the arrival ball or the aim
     * quantiser differs between them; only which state the wire is in.
     */
    @Test
    public void theSameRayHitsTwoDifferentFacesDependingOnWhetherTheWireIsConnected() {
        Vec3 eye = PlayerPose.CROUCHED.eyeAt(APPROACH);
        Rotation rotation = RotationUtils.calcRotationFromVec3d(eye, PROVEN_AIM, REFERENCE);
        Vec3 look = RotationUtils.calcLookDirectionFromRotation(rotation);
        Vec3 end = eye.add(look.x * PlayerPose.PLANNING_REACH, look.y * PlayerPose.PLANNING_REACH,
                look.z * PlayerPose.PLANNING_REACH);

        OutlineGeometry.Ray planned = OutlineGeometry.clip(worldWith(dot()), eye, end);
        assertNotNull("the planner's world answers, which is why the plan was written", planned.hit());
        assertSame("what the plan proved: the wire's west face", Direction.WEST, planned.hit().getDirection());
        assertEquals("at the trace's 126.188,-58.969,80.344", 0.0D,
                planned.hit().getLocation().distanceTo(PROVEN_AIM), 1.0E-3D);

        OutlineGeometry.Ray live = OutlineGeometry.clip(worldWith(cross()), eye, end);
        assertNotNull(live.hit());
        assertSame("what the client did: the top of the west arm", Direction.UP, live.hit().getDirection());
        assertEquals("at the trace's 126.119,-58.938,80.272", 0.0D,
                live.hit().getLocation().distanceTo(LIVE_HIT), 1.0E-3D);
    }

    /**
     * The repair, at the point the drift was created: the state the planner writes for a placed wire is now the state
     * vanilla will produce for it.
     *
     * <p>This is the same cell and the same click the plan made — a wire on the farm's glass floor with nothing
     * redstone around it. It used to be stored as the item default, the one-box dot whose west face the aim was taken
     * from. Vanilla places an isolated dust as a CROSS ({@code getStateForPlacement} runs
     * {@code getConnectionState} over {@code crossState}, so the dot short circuit does not fire and the completion
     * rules put all four arms back), and the planner now agrees.
     */
    @Test
    public void thePlannerNowWritesTheStateVanillaWillActuallyProduce() {
        BlockPos floor = new BlockPos(126, -60, 80);
        PredictedWorld world = worldWith(Blocks.AIR.defaultBlockState());
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);

        BlockState placed = oracle.simulate(world, new ItemStack(Items.REDSTONE, 64), floor, Direction.UP,
                new Vec3(126.5D, -59.0D, 80.5D), new Rotation(0.0F, 60.0F), true);

        assertNotNull("the placement itself is legal; only its connections were ever the question", placed);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            assertSame("an isolated dust lands as a cross, and the plan now says so: " + direction,
                    RedstoneSide.SIDE,
                    placed.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction)));
        }
        assertEquals("so the outline the planner measures aim points on is the live three-box one",
                3, OutlineGeometry.localParts(worldWith(placed), AGAINST).size());
    }

    /**
     * And the aim that killed the click is no longer one the planner can pick, because the face it sat on is gone.
     *
     * <p>The proven aim was the sampler's nearest point on the west face of the DOT, at {@code x = 126.1875}. On the
     * state the wire actually lands in, that plane is interior — the west arm runs from {@code x = 126.0} — so no
     * sample lands there and the ray that hit the arm's top cannot be proposed.
     */
    @Test
    public void theAimThatFailedIsNoLongerReachableFromTheStateTheWireLandsIn() {
        PredictedWorld live = worldWith(cross());
        for (AABB part : OutlineGeometry.localParts(live, AGAINST)) {
            for (Vec3 point : PlacementGeometry.aimPointsOnFace(PlayerPose.CROUCHED.eyeAt(APPROACH), AGAINST,
                    part, Direction.EAST, Blocks.TARGET.defaultBlockState())) {
                assertTrue("the dot's west face is interior to the landed wire, so nothing may aim at it: " + point,
                        point.distanceTo(PROVEN_AIM) > 1.0E-9D);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- fixtures

    private static BlockState dot() {
        return Blocks.REDSTONE_WIRE.defaultBlockState();
    }

    private static BlockState cross() {
        return dot()
                .setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
    }

    /** The wire on the farm's glass floor, everything else air. */
    private static PredictedWorld worldWith(BlockState wire) {
        return PredictedWorld.capture(
                (x, y, z) -> {
                    if (new BlockPos(x, y, z).equals(AGAINST)) {
                        return wire;
                    }
                    return y <= -60 ? Blocks.BLACK_STAINED_GLASS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState();
                },
                (x, z) -> true, new Vec3i(118, -64, 72), new Vec3i(132, -54, 86), 0, V3Settings.defaults());
    }
}
