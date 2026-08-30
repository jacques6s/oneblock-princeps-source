/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.builder.v3;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.event.events.PacketEvent;
import princeps.api.event.events.ChunkEvent;
import princeps.api.event.events.type.EventState;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Packet-level tests for the sole positive evidence path used by the V3 executor. */
public class ServerAckTest {

    private static final BlockPos CELL = new BlockPos(31, 74, -19);
    private static final BlockPos AGAINST = CELL.below();
    private static final Direction FACE = Direction.UP;
    private static BlockState placed;
    private static final List<String> FRONT_LINES = List.of("north", "middle", "", "bottom");
    private static final List<String> BACK_LINES = List.of("behind", "", "three", "");

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        placed = Blocks.COBBLESTONE.defaultBlockState();
    }

    @Test
    public void matchingClientWorldWithoutAnAuthoritativePacketTimesOut() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), placed);
        fixture.ack.expectPlacement(CELL, AGAINST, FACE, placed, 0L);

        assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(0L));
        for (long tick = 1L; tick <= fixture.ack.timeoutTicks() + 1L; tick++) {
            fixture.ack.tick(tick);
        }

        assertEquals(ServerAck.Outcome.NOT_SENT, fixture.ack.outcome());
        assertEquals(0L, fixture.ack.confirmations());
    }

    @Test
    public void matchingOutboundAckAndAdaptiveHoldAreAllRequired() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), placed);
        fixture.ack.expectPlacement(CELL, AGAINST, FACE, placed, 0L);
        fixture.ack.tick(0L);
        fixture.send(placePacket(7));
        fixture.receive(new ClientboundBlockChangedAckPacket(7));

        assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(1L));
        long confirmationTick = 1L + fixture.ack.holdTicks();
        for (long tick = 2L; tick < confirmationTick; tick++) {
            assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(tick));
        }
        assertEquals(ServerAck.Outcome.CONFIRMED, fixture.ack.tick(confirmationTick));
        assertEquals(1L, fixture.ack.confirmations());
    }

    @Test
    public void rollbackDuringTheHoldCanNeverConfirm() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), placed);
        fixture.ack.expectPlacement(CELL, AGAINST, FACE, placed, 0L);
        fixture.ack.tick(0L);
        fixture.send(placePacket(13));
        fixture.receive(new ClientboundBlockChangedAckPacket(13));
        assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(1L));

        fixture.world.put(key(CELL), Blocks.AIR.defaultBlockState());
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, Blocks.AIR.defaultBlockState()));

        assertEquals(ServerAck.Outcome.REVERTED, fixture.ack.tick(2L));
        assertEquals(0L, fixture.ack.confirmations());
    }

    @Test
    public void breakWaitsForItsOwnStopPacketAndThenRequiresTheSameHold() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.STONE.defaultBlockState());
        fixture.ack.expectBreak(CELL, Blocks.AIR.defaultBlockState(), 0L);
        fixture.ack.tick(0L);

        fixture.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                CELL, Direction.UP, 99));
        fixture.receive(new ClientboundBlockChangedAckPacket(99));
        assertEquals("an unrelated ack is not evidence for a break whose STOP packet does not exist",
                ServerAck.Outcome.PENDING, fixture.ack.tick(1000L));

        fixture.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                CELL, Direction.UP, 100));
        fixture.world.put(key(CELL), Blocks.AIR.defaultBlockState());
        fixture.receive(new ClientboundBlockChangedAckPacket(100));
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, Blocks.AIR.defaultBlockState()));

        assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(1001L));
        long confirmationTick = 1001L + fixture.ack.holdTicks();
        for (long tick = 1002L; tick < confirmationTick; tick++) {
            fixture.ack.tick(tick);
        }
        assertEquals(ServerAck.Outcome.CONFIRMED, fixture.ack.tick(confirmationTick));
    }

    @Test
    public void instantBreakUsesItsStartPacketAndStillRequiresServerEvidenceAndHold() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.STONE.defaultBlockState());
        fixture.ack.expectBreak(CELL, Blocks.AIR.defaultBlockState(), 0L);
        fixture.ack.tick(0L);

        // Vanilla's creative/instant path removes the client block before constructing START and emits no STOP.
        fixture.world.put(key(CELL), Blocks.AIR.defaultBlockState());
        fixture.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                CELL, Direction.UP, 101));
        fixture.receive(new ClientboundBlockChangedAckPacket(101));
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, Blocks.AIR.defaultBlockState()));

        assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(1L));
        long confirmationTick = 1L + fixture.ack.holdTicks();
        for (long tick = 2L; tick < confirmationTick; tick++) {
            assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(tick));
        }
        assertEquals(ServerAck.Outcome.CONFIRMED, fixture.ack.tick(confirmationTick));
    }

    @Test
    public void breakPostStateIsExactAirEvenWhenTheSchematicMatcherWouldAcceptWater() {
        Map<Long, BlockState> world = new HashMap<>();
        world.put(key(CELL), Blocks.WATER.defaultBlockState());
        ServerAck ack = new ServerAck(Runnable::run,
                pos -> world.getOrDefault(key(pos), Blocks.AIR.defaultBlockState()),
                (observed, desired) -> true, ServerAck.ChangeSink.NONE);
        ack.activate();
        ack.expectBreak(CELL, Blocks.AIR.defaultBlockState(), 0L);
        ack.tick(0L);
        ack.onSendPacket(new PacketEvent(null, EventState.PRE,
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        CELL, Direction.UP, 101)));
        ack.onReceivePacket(new PacketEvent(null, EventState.PRE, new ClientboundBlockChangedAckPacket(101)));

        assertEquals(ServerAck.Outcome.REVERTED, ack.tick(1L));
    }

    @Test
    public void clearCannotBeTurnedIntoAClientOnlyConfirmation() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), placed);
        fixture.ack.expectPlacement(CELL, AGAINST, FACE, placed, 0L);
        fixture.ack.tick(0L);
        fixture.ack.clearExpectation();

        fixture.send(placePacket(21));
        fixture.receive(new ClientboundBlockChangedAckPacket(21));
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, placed));

        assertEquals(ServerAck.Outcome.IDLE, fixture.ack.tick(100L));
        assertEquals(0L, fixture.ack.confirmations());
    }

    @Test
    public void aBlockPacketBeforeOurOutboundIsOnlyAWorldChange() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), placed);
        fixture.ack.expectPlacement(CELL, AGAINST, FACE, placed, 0L);
        fixture.ack.tick(0L);
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, placed));

        for (long tick = 1L; tick <= fixture.ack.timeoutTicks() + 1L; tick++) {
            fixture.ack.tick(tick);
        }
        assertEquals(ServerAck.Outcome.NOT_SENT, fixture.ack.outcome());
        assertEquals(1, fixture.changes.size());
    }

    @Test
    public void armingCannotSilentlyOverwriteAnExistingExpectation() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.ack.expectPlacement(CELL, AGAINST, FACE, placed, 0L));
        BlockPos other = CELL.east();

        assertTrue("the second arm must be explicitly refused",
                !fixture.ack.expectPlacement(other, other.below(), FACE, placed, 1L));
        assertEquals(CELL, fixture.ack.pendingCell());
    }

    @Test
    public void unloadingThePendingCellChunkCancelsItsProof() {
        Fixture fixture = new Fixture();
        fixture.ack.expectPlacement(CELL, AGAINST, FACE, placed, 0L);

        fixture.ack.onChunkEvent(new ChunkEvent(EventState.PRE, ChunkEvent.Type.UNLOAD,
                CELL.getX() >> 4, CELL.getZ() >> 4));

        assertEquals(ServerAck.Outcome.UNLOADED, fixture.ack.outcome());
        assertEquals(0L, fixture.ack.confirmations());
    }

    @Test
    public void latePacketsReachTheChangeSinkEvenWithNoExpectation() {
        Fixture fixture = new Fixture();
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, Blocks.DIRT.defaultBlockState()));

        assertEquals(1, fixture.changes.size());
        assertEquals(CELL, fixture.changes.get(0));
        assertEquals(ServerAck.Outcome.IDLE, fixture.ack.outcome());
    }

    @Test
    public void interactionIntermediateStateUsesAnExactPerExpectationMatcher() {
        BlockState intermediate = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 2);
        Fixture exact = new Fixture((observed, desired) -> true);
        exact.world.put(key(CELL), intermediate);
        assertTrue(exact.ack.expectUseOn(CELL, CELL, FACE, intermediate, 0L));
        exact.ack.tick(0L);
        exact.send(usePacket(CELL, FACE, 30));
        exact.receive(new ClientboundBlockChangedAckPacket(30));
        exact.receive(new ClientboundBlockUpdatePacket(CELL, intermediate));
        assertConfirmsAfterHold(exact, 1L);

        BlockState skippedStep = intermediate.setValue(RepeaterBlock.DELAY, 4);
        Fixture wrong = new Fixture((observed, desired) -> true);
        wrong.world.put(key(CELL), skippedStep);
        assertTrue(wrong.ack.expectUseOn(CELL, CELL, FACE, intermediate, 0L));
        wrong.ack.tick(0L);
        wrong.send(usePacket(CELL, FACE, 31));
        wrong.receive(new ClientboundBlockChangedAckPacket(31));
        wrong.receive(new ClientboundBlockUpdatePacket(CELL, skippedStep));

        assertEquals("the exact overload must not inherit the permissive placement matcher",
                ServerAck.Outcome.WRONG_STATE, wrong.ack.tick(1L));
    }

    @Test
    public void fluidUseMatchesTheClickedNeighbourButConfirmsTheEffectDestination() {
        BlockPos clicked = CELL.west();
        Direction face = Direction.EAST;
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.WATER.defaultBlockState());
        assertTrue(fixture.ack.expectUseOn(CELL, clicked, face, Blocks.WATER.defaultBlockState(),
                FluidPlan::satisfies, 0L));
        fixture.ack.tick(0L);

        fixture.send(usePacket(CELL, face, 39)); // effect cell is not what the bucket clicked
        fixture.receive(new ClientboundBlockChangedAckPacket(39));
        assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(1L));

        fixture.send(usePacket(clicked, face, 40));
        fixture.receive(new ClientboundBlockChangedAckPacket(40));
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, Blocks.WATER.defaultBlockState()));
        assertConfirmsAfterHold(fixture, 2L);
    }

    @Test
    public void waterloggingUsesTheSameCellForClickAndEffect() {
        BlockState wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), wet);
        assertTrue(fixture.ack.expectUseOn(CELL, CELL, Direction.NORTH, wet, 0L));
        fixture.ack.tick(0L);
        fixture.send(usePacket(CELL, Direction.NORTH, 41));
        fixture.receive(new ClientboundBlockChangedAckPacket(41));
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, wet));

        assertConfirmsAfterHold(fixture, 1L);
    }

    @Test
    public void genericAndSignExpectationsCannotOverwritePendingWork() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.ack.expectUseOn(CELL, AGAINST, FACE, placed, 0L));

        assertTrue(!fixture.ack.expectSign(CELL.east(), true, FRONT_LINES, 1L));
        assertTrue(!fixture.ack.expectUseOn(CELL.east(), CELL, FACE, placed, 1L));
        assertEquals(CELL, fixture.ack.pendingCell());
    }

    @Test
    public void frontAndBackSignMetadataEachRequireTheirExactSideAndAdaptiveHold() {
        assertSignConfirms(true, FRONT_LINES);
        assertSignConfirms(false, BACK_LINES);
    }

    @Test
    public void wrongSignSideOrOutboundLinesAreNotEvidenceAndEndNotSent() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.OAK_SIGN.defaultBlockState());
        fixture.ack.expectSign(CELL, true, FRONT_LINES, 0L);
        fixture.ack.tick(0L);
        fixture.send(new ServerboundSignUpdatePacket(CELL, false,
                FRONT_LINES.get(0), FRONT_LINES.get(1), FRONT_LINES.get(2), FRONT_LINES.get(3)));
        fixture.send(new ServerboundSignUpdatePacket(CELL, true,
                "wrong", FRONT_LINES.get(1), FRONT_LINES.get(2), FRONT_LINES.get(3)));
        fixture.receive(signData(CELL, FRONT_LINES, BACK_LINES));

        for (long tick = 1L; tick <= fixture.ack.timeoutTicks() + 1L; tick++) {
            fixture.ack.tick(tick);
        }
        assertEquals(ServerAck.Outcome.NOT_SENT, fixture.ack.outcome());
        assertEquals(0L, fixture.ack.confirmations());
    }

    @Test
    public void signMetadataOnTheWrongSideOrWithWrongLinesNeverConfirms() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.OAK_SIGN.defaultBlockState());
        fixture.ack.expectSign(CELL, true, FRONT_LINES, 0L);
        fixture.ack.tick(0L);
        fixture.send(signPacket(CELL, true, FRONT_LINES));
        fixture.receive(signData(CELL, List.of("other", "", "", ""), FRONT_LINES));

        assertEquals(ServerAck.Outcome.WRONG_STATE, fixture.ack.tick(1L));
        assertEquals(0L, fixture.ack.confirmations());
    }

    @Test
    public void signRollbackDuringTheHoldCanNeverConfirm() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.OAK_SIGN.defaultBlockState());
        fixture.ack.expectSign(CELL, true, FRONT_LINES, 0L);
        fixture.ack.tick(0L);
        fixture.send(signPacket(CELL, true, FRONT_LINES));
        fixture.receive(signData(CELL, FRONT_LINES, BACK_LINES));
        assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(1L));

        fixture.world.put(key(CELL), Blocks.AIR.defaultBlockState());
        fixture.receive(new ClientboundBlockUpdatePacket(CELL, Blocks.AIR.defaultBlockState()));

        assertEquals(ServerAck.Outcome.REVERTED, fixture.ack.tick(2L));
        assertEquals(0L, fixture.ack.confirmations());
    }

    @Test
    public void signOutboundWithoutAuthoritativeMetadataTimesOut() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.OAK_SIGN.defaultBlockState());
        fixture.ack.expectSign(CELL, true, FRONT_LINES, 0L);
        fixture.ack.tick(0L);
        fixture.send(signPacket(CELL, true, FRONT_LINES));

        for (long tick = 1L; tick <= fixture.ack.timeoutTicks() + 1L; tick++) {
            fixture.ack.tick(tick);
        }
        assertEquals(ServerAck.Outcome.TIMED_OUT, fixture.ack.outcome());
    }

    @Test
    public void unloadingAPendingSignCellCancelsMetadataProof() {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.OAK_SIGN.defaultBlockState());
        fixture.ack.expectSign(CELL, false, BACK_LINES, 0L);
        fixture.ack.tick(0L);
        fixture.send(signPacket(CELL, false, BACK_LINES));

        fixture.ack.onChunkEvent(new ChunkEvent(EventState.PRE, ChunkEvent.Type.UNLOAD,
                CELL.getX() >> 4, CELL.getZ() >> 4));

        assertEquals(ServerAck.Outcome.UNLOADED, fixture.ack.outcome());
        assertEquals(0L, fixture.ack.confirmations());
    }

    private static ServerboundUseItemOnPacket placePacket(int sequence) {
        return usePacket(AGAINST, FACE, sequence);
    }

    private static ServerboundUseItemOnPacket usePacket(BlockPos clicked, Direction face, int sequence) {
        BlockHitResult hit = new BlockHitResult(new Vec3(
                clicked.getX() + 0.4D, clicked.getY() + 0.5D, clicked.getZ() + 0.6D), face, clicked, false);
        return new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, sequence);
    }

    private static ServerboundSignUpdatePacket signPacket(BlockPos cell, boolean frontSide, List<String> lines) {
        return new ServerboundSignUpdatePacket(cell, frontSide,
                lines.get(0), lines.get(1), lines.get(2), lines.get(3));
    }

    private static ClientboundBlockEntityDataPacket signData(BlockPos cell, List<String> front, List<String> back) {
        try {
            Constructor<ClientboundBlockEntityDataPacket> constructor =
                    ClientboundBlockEntityDataPacket.class.getDeclaredConstructor(
                            BlockPos.class, BlockEntityType.class, CompoundTag.class);
            constructor.setAccessible(true);
            return constructor.newInstance(cell, BlockEntityType.SIGN, signTag(front, back));
        } catch (ReflectiveOperationException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static CompoundTag signTag(List<String> front, List<String> back) {
        CompoundTag tag = new CompoundTag();
        putSignSide(tag, "front_text", front);
        putSignSide(tag, "back_text", back);
        return tag;
    }

    private static void putSignSide(CompoundTag tag, String key, List<String> lines) {
        if (lines == null) {
            return;
        }
        ListTag messages = new ListTag();
        for (String line : lines) {
            messages.add(StringTag.valueOf(line));
        }
        CompoundTag side = new CompoundTag();
        side.put("messages", messages);
        tag.put(key, side);
    }

    private static void assertSignConfirms(boolean frontSide, List<String> lines) {
        Fixture fixture = new Fixture();
        fixture.world.put(key(CELL), Blocks.OAK_SIGN.defaultBlockState());
        assertTrue(fixture.ack.expectSign(CELL, frontSide, lines, 0L));
        fixture.ack.tick(0L);
        fixture.send(signPacket(CELL, frontSide, lines));
        fixture.receive(signData(CELL, FRONT_LINES, BACK_LINES));
        assertConfirmsAfterHold(fixture, 1L);
    }

    private static void assertConfirmsAfterHold(Fixture fixture, long firstTick) {
        assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(firstTick));
        long confirmationTick = firstTick + fixture.ack.holdTicks();
        for (long tick = firstTick + 1L; tick < confirmationTick; tick++) {
            assertEquals(ServerAck.Outcome.PENDING, fixture.ack.tick(tick));
        }
        assertEquals(ServerAck.Outcome.CONFIRMED, fixture.ack.tick(confirmationTick));
    }

    private static long key(BlockPos pos) {
        return BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
    }

    private static final class Fixture {

        private final Map<Long, BlockState> world = new HashMap<>();
        private final List<BlockPos> changes = new ArrayList<>();
        private final ServerAck ack;

        private Fixture() {
            this(BlockState::equals);
        }

        private Fixture(ServerAck.StateMatcher matcher) {
            this.ack = new ServerAck(Runnable::run,
                    pos -> this.world.getOrDefault(key(pos), Blocks.AIR.defaultBlockState()),
                    matcher,
                    (pos, state) -> this.changes.add(pos));
            this.ack.activate();
        }

        private void send(Packet<?> packet) {
            this.ack.onSendPacket(new PacketEvent(null, EventState.PRE, packet));
        }

        private void receive(Packet<?> packet) {
            this.ack.onReceivePacket(new PacketEvent(null, EventState.PRE, packet));
        }
    }
}
