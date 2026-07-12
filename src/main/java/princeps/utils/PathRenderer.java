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

import princeps.api.PrincepsAPI;
import princeps.api.event.events.RenderEvent;
import princeps.api.pathing.goals.*;
import princeps.api.process.IElytraProcess;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.interfaces.IGoalRenderPos;
import princeps.behavior.PathingBehavior;
import princeps.pathing.path.PathExecutor;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 * @since 8/9/2018
 */
public final class PathRenderer implements IRenderer {

    private PathRenderer() {}

    private static final float GOAL_BEACON_INNER_RADIUS = 0.2F;
    private static final float GOAL_BEACON_GLOW_RADIUS = 0.25F;
    private static final int GOAL_BEACON_GLOW_ALPHA = 32;

    public static double posX() {
        return renderManager.renderPosX();
    }

    public static double posY() {
        return renderManager.renderPosY();
    }

    public static double posZ() {
        return renderManager.renderPosZ();
    }

    public static void render(RenderEvent event, PathingBehavior behavior) {
        final IPlayerContext ctx = behavior.ctx;
        if (ctx.world() == null) {
            return;
        }
        if (ctx.minecraft().screen instanceof GuiClick) {
            ((GuiClick) ctx.minecraft().screen).onRender(event.getModelViewStack(), event.getProjectionMatrix());
        }

        final float partialTicks = event.getPartialTicks();
        final Goal goal = behavior.getGoal();

        final DimensionType thisPlayerDimension = ctx.world().dimensionType();
        final DimensionType currentRenderViewDimension = PrincepsAPI.getProvider().getPrimaryPrinceps().getPlayerContext().world().dimensionType();

        if (thisPlayerDimension != currentRenderViewDimension) {
            // this is a path for a bot in a different dimension, don't render it
            return;
        }

        if (goal != null && settings.renderGoal.value) {
            drawGoal(event.getModelViewStack(), ctx, goal, partialTicks, settings.colorGoalBox.value);
        }

        if (!settings.renderPath.value) {
            return;
        }

        // While the elytra process is flying, Princeps steers with the elytra, which draws its own flight
        // path. The walking pathfinder may still hold a ground path, but only the route actually being used
        // should be shown — so skip the walking path (and its break/place/in-progress overlays) here. The
        // goal box above still renders since the destination is shared.
        final IElytraProcess elytra = behavior.princeps.getElytraProcess();
        if (elytra != null && elytra.isActive()) {
            return;
        }

        PathExecutor current = behavior.getCurrent(); // this should prevent most race conditions?
        PathExecutor next = behavior.getNext(); // like, now it's not possible for current!=null to be true, then suddenly false because of another thread
        if (current != null && settings.renderSelectionBoxes.value) {
            if (settings.renderBreakTargetsFancy.value) {
                drawBreakTargets(event.getModelViewStack(), ctx.player(), current.toBreak());
            } else {
                drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toBreak(), settings.colorBlocksToBreak.value);
            }
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toPlace(), settings.colorBlocksToPlace.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toWalkInto(), settings.colorBlocksToWalkInto.value);
        }

        //drawManySelectionBoxes(player, Collections.singletonList(behavior.pathStart()), partialTicks, Color.WHITE);

        // Render the current path, if there is one — as a smooth arc-rounded curve (cached, not rebuilt per frame).
        if (current != null && current.getPath() != null) {
            int renderBegin = Math.max(current.getPosition() - 3, 0);
            drawSmoothCurrentPath(event.getModelViewStack(), current.getPath().positions(), renderBegin, settings.colorCurrentPath.value, 0.5D);
        }

        if (next != null && next.getPath() != null) {
            drawPath(event.getModelViewStack(), next.getPath().positions(), 0, settings.colorNextPath.value, settings.fadePath.value, 10, 20);
        }

        // If there is a path calculation currently running, render the path calculation process
        behavior.getInProgress().ifPresent(currentlyRunning -> {
            currentlyRunning.bestPathSoFar().ifPresent(p -> {
                drawPath(event.getModelViewStack(), p.positions(), 0, settings.colorBestPathSoFar.value, settings.fadePath.value, 10, 20);
            });

            currentlyRunning.pathToMostRecentNodeConsidered().ifPresent(mr -> {
                drawPath(event.getModelViewStack(), mr.positions(), 0, settings.colorMostRecentConsidered.value, settings.fadePath.value, 10, 20);
                drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), Collections.singletonList(mr.getDest()), settings.colorMostRecentConsidered.value);
            });
        });
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0) {
        drawPath(stack, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, 0.5D);
    }

    /**
     * Drops interior points that are collinear with their neighbours (within a hair of angular
     * tolerance): resampled straight runs collapse to their two endpoints, arc samples all survive.
     */
    private static java.util.List<princeps.flownav.math.Vec3> decimateCollinear(java.util.List<princeps.flownav.math.Vec3> line) {
        if (line.size() <= 2) {
            return line;
        }
        java.util.List<princeps.flownav.math.Vec3> out = new java.util.ArrayList<>(line.size() / 4 + 2);
        out.add(line.get(0));
        for (int i = 1; i < line.size() - 1; i++) {
            princeps.flownav.math.Vec3 a = out.get(out.size() - 1);
            princeps.flownav.math.Vec3 b = line.get(i);
            princeps.flownav.math.Vec3 c = line.get(i + 1);
            double d1x = b.x() - a.x(), d1y = b.y() - a.y(), d1z = b.z() - a.z();
            double d2x = c.x() - b.x(), d2y = c.y() - b.y(), d2z = c.z() - b.z();
            // 3D cross product magnitude vs segment lengths ~ sin(angle); keep the point on any bend.
            double cx = d1y * d2z - d1z * d2y;
            double cy = d1z * d2x - d1x * d2z;
            double cz = d1x * d2y - d1y * d2x;
            double crossSq = cx * cx + cy * cy + cz * cz;
            double lenSq = (d1x * d1x + d1y * d1y + d1z * d1z) * (d2x * d2x + d2y * d2y + d2z * d2z);
            if (crossSq > lenSq * 1e-6) { // sin^2(angle) > (0.001)^2 — anything visibly bent survives
                out.add(b);
            }
        }
        out.add(line.get(line.size() - 1));
        return out;
    }

    /** Cached smoothed line of the current path (rebuilt only when the PATH changes, never per frame). */
    private static long smoothSig = Long.MIN_VALUE;
    private static java.util.List<princeps.flownav.math.Vec3> smoothLine = java.util.Collections.emptyList();

    /**
     * Draws the current path as a smooth arc-rounded curve (FlowNav's FlowLine). CRITICAL for FPS: the smooth
     * line is CACHED per PATH — the signature deliberately excludes the executor's progress index, which
     * advances every node (~5x/s at sprint) and would trigger a full O(path) re-smooth + allocation burst on
     * the render thread each time (review-confirmed). The whole path is smoothed once; each frame only trims
     * the tail behind the camera with a cheap scan over the small decimated point list. Smoothing every frame
     * tanked the frame rate; only the executed path is smoothed, calc-debug paths stay the raw polyline.
     */
    private static void drawSmoothCurrentPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, double offset) {
        final int n = positions.size();
        if (n - Math.max(0, startIndex) < 3) {
            drawPath(stack, positions, startIndex, color, settings.fadePath.value, 10, 20, offset);
            return;
        }
        final long sig = ((long) n * 131 + positions.get(0).hashCode()) * 131 + positions.get(n - 1).hashCode();
        if (sig != smoothSig) {
            java.util.List<princeps.flownav.math.Vec3> pts = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                BetterBlockPos p = positions.get(i);
                pts.add(new princeps.flownav.math.Vec3(p.x + 0.5, p.y, p.z + 0.5));
            }
            try {
                // smoothPoints avoids the Trajectory wrapper (its copy + arc-length array would be thrown
                // away). Decimation collapses collinear resampled points so straights emit ONE segment.
                smoothLine = decimateCollinear(princeps.flownav.traj.FlowLine.smoothPoints(pts, 1.0));
            } catch (RuntimeException e) {
                smoothLine = pts; // degenerate geometry: fall back to the raw polyline
            }
            smoothSig = sig;
        }
        final java.util.List<princeps.flownav.math.Vec3> line = smoothLine;
        // Trim the already-walked tail: start at the decimated point nearest the camera, minus a little
        // slack (mirrors the old position-3 behaviour). The decimated list is small, so this per-frame
        // scan is a few hundred float ops — nothing — and it never rebuilds the curve.
        final double camX = posX(), camZ = posZ();
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < line.size(); i++) {
            princeps.flownav.math.Vec3 p = line.get(i);
            double dx = p.x() - 0.5 + offset - camX;
            double dz = p.z() - 0.5 + offset - camZ;
            double d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        int from = nearest;
        double slack = 3.0; // keep ~3 blocks of line visible behind the player
        while (from > 0 && slack > 0) {
            slack -= line.get(from).horizontalDistanceTo(line.get(from - 1));
            from--;
        }
        BufferBuilder bufferBuilder = IRenderer.startLines(color);
        for (int i = from; i + 1 < line.size(); i++) {
            princeps.flownav.math.Vec3 p = line.get(i);
            princeps.flownav.math.Vec3 q = line.get(i + 1);
            // FlowLine points are block-centred (x+0.5); emitPathLine re-adds `offset` (0.5), so pass -0.5.
            emitPathLine(bufferBuilder, stack, p.x() - 0.5, p.y(), p.z() - 0.5, q.x() - 0.5, q.y(), q.z() - 0.5, offset);
        }
        IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0, double offset) {
        BufferBuilder bufferBuilder = IRenderer.startLines(color);

        int fadeStart = fadeStart0 + startIndex;
        int fadeEnd = fadeEnd0 + startIndex;

        for (int i = startIndex, next; i < positions.size() - 1; i = next) {
            BetterBlockPos start = positions.get(i);
            BetterBlockPos end = positions.get(next = i + 1);

            int dirX = end.x - start.x;
            int dirY = end.y - start.y;
            int dirZ = end.z - start.z;

            while (next + 1 < positions.size() && (!fadeOut || next + 1 < fadeStart) &&
                    (dirX == positions.get(next + 1).x - end.x &&
                            dirY == positions.get(next + 1).y - end.y &&
                            dirZ == positions.get(next + 1).z - end.z)) {
                end = positions.get(++next);
            }

            if (fadeOut) {
                float alpha;

                if (i <= fadeStart) {
                    alpha = 0.4F;
                } else {
                    if (i > fadeEnd) {
                        break;
                    }
                    alpha = 0.4F * (1.0F - (float) (i - fadeStart) / (float) (fadeEnd - fadeStart));
                }
                IRenderer.glColor(color, alpha);
            }

            emitPathLine(bufferBuilder, stack, start.x, start.y, start.z, end.x, end.y, end.z, offset);
        }

        IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }

    private static void emitPathLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, double offset) {
        final double extraOffset = offset + 0.03D;

        double vpX = posX();
        double vpY = posY();
        double vpZ = posZ();
        boolean renderPathAsFrickinThingy = !settings.renderPathAsLine.value;

        IRenderer.emitLine(bufferBuilder, stack,
                x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ,
                x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ,
                settings.pathRenderLineWidthPixels.value
        );
        if (renderPathAsFrickinThingy) {
            IRenderer.emitLine(bufferBuilder, stack,
                    x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
            IRenderer.emitLine(bufferBuilder, stack,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
            IRenderer.emitLine(bufferBuilder, stack,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ,
                    x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
        }
    }

    /**
     * Fancy break-target render: bright corner brackets around each block to mine + a centre diamond
     * marker, instead of the classic full outlined box. Two line batches (bracket color + marker color).
     */
    public static void drawBreakTargets(PoseStack stack, Entity player, Collection<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }
        final double rpx = posX(), rpy = posY(), rpz = posZ();
        final float lw = settings.pathRenderLineWidthPixels.value;
        final boolean ignoreDepth = settings.renderSelectionBoxesIgnoreDepth.value;
        final BlockStateInterface bsi = new BlockStateInterface(PrincepsAPI.getProvider().getPrimaryPrinceps().getPlayerContext());

        // corner brackets
        BufferBuilder brackets = IRenderer.startLines(settings.colorBlocksToBreak.value);
        for (BlockPos pos : positions) {
            AABB shape = bsi.get0(pos).getShape(player.level(), pos).isEmpty()
                    ? Shapes.block().bounds() : bsi.get0(pos).getShape(player.level(), pos).bounds();
            shape = shape.move(pos).inflate(0.002);
            emitCornerBrackets(brackets, stack,
                    shape.minX - rpx, shape.minY - rpy, shape.minZ - rpz,
                    shape.maxX - rpx, shape.maxY - rpy, shape.maxZ - rpz, lw);
        }
        IRenderer.endLines(brackets, ignoreDepth);

        // centre diamond marker
        BufferBuilder marker = IRenderer.startLines(settings.colorBreakTargetMarker.value);
        for (BlockPos pos : positions) {
            emitDiamond(marker, stack,
                    pos.getX() + 0.5 - rpx, pos.getY() + 0.5 - rpy, pos.getZ() + 0.5 - rpz, 0.16, lw);
        }
        IRenderer.endLines(marker, ignoreDepth);
    }

    /** L-shaped brackets at each of the 8 box corners (3 short segments per corner, toward the interior). */
    private static void emitCornerBrackets(BufferBuilder bb, PoseStack stack,
                                           double minX, double minY, double minZ,
                                           double maxX, double maxY, double maxZ, float lw) {
        final double len = Math.min(0.28, (maxX - minX) * 0.4);
        for (int cx = 0; cx <= 1; cx++) {
            for (int cy = 0; cy <= 1; cy++) {
                for (int cz = 0; cz <= 1; cz++) {
                    final double x = cx == 0 ? minX : maxX;
                    final double y = cy == 0 ? minY : maxY;
                    final double z = cz == 0 ? minZ : maxZ;
                    final double dx = cx == 0 ? len : -len;
                    final double dy = cy == 0 ? len : -len;
                    final double dz = cz == 0 ? len : -len;
                    IRenderer.emitLine(bb, stack, x, y, z, x + dx, y, z, 1, 0, 0, lw);
                    IRenderer.emitLine(bb, stack, x, y, z, x, y + dy, z, 0, 1, 0, lw);
                    IRenderer.emitLine(bb, stack, x, y, z, x, y, z + dz, 0, 0, 1, lw);
                }
            }
        }
    }

    /** A small 3D octahedron ("diamond") outline centred at (cx,cy,cz) — reads as a diamond from any angle. */
    private static void emitDiamond(BufferBuilder bb, PoseStack stack, double cx, double cy, double cz, double r, float lw) {
        final double[] top = {cx, cy + r, cz}, bot = {cx, cy - r, cz};
        final double[] px = {cx + r, cy, cz}, nx = {cx - r, cy, cz};
        final double[] pz = {cx, cy, cz + r}, nz = {cx, cy, cz - r};
        final double[][] ring = {px, pz, nx, nz};
        for (int i = 0; i < 4; i++) {
            final double[] a = ring[i], b = ring[(i + 1) % 4];
            IRenderer.emitLine(bb, stack, a[0], a[1], a[2], b[0], b[1], b[2], 0, 1, 0, lw);      // equator
            IRenderer.emitLine(bb, stack, a[0], a[1], a[2], top[0], top[1], top[2], 0, 1, 0, lw); // to top
            IRenderer.emitLine(bb, stack, a[0], a[1], a[2], bot[0], bot[1], bot[2], 0, 1, 0, lw); // to bottom
        }
    }

    public static void drawManySelectionBoxes(PoseStack stack, Entity player, Collection<BlockPos> positions, Color color) {
        BufferBuilder bufferBuilder = IRenderer.startLines(color);

        //BlockPos blockpos = movingObjectPositionIn.getBlockPos();
        BlockStateInterface bsi = new BlockStateInterface(PrincepsAPI.getProvider().getPrimaryPrinceps().getPlayerContext()); // TODO this assumes same dimension between primary princeps and render view? is this safe?

        positions.forEach(pos -> {
            BlockState state = bsi.get0(pos);
            VoxelShape shape = state.getShape(player.level(), pos);
            AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
            toDraw = toDraw.move(pos);
            IRenderer.emitAABB(bufferBuilder, stack, toDraw, .002D, settings.pathRenderLineWidthPixels.value);
        });

        IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
    }

    public static void drawGoal(PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color) {
        drawGoal(null, stack, ctx, goal, partialTicks, color, true);
    }

    private static void drawGoal(@Nullable BufferBuilder bufferBuilder, PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color, boolean setupRender) {
        if (!setupRender && bufferBuilder == null) {
            throw new RuntimeException("BufferBuilder must not be null if setupRender is false");
        }
        double renderPosX = posX();
        double renderPosY = posY();
        double renderPosZ = posZ();
        double minX, maxX;
        double minZ, maxZ;
        double minY, maxY;
        double y, y1, y2;
        if (!settings.renderGoalAnimated.value) {
            // y = 1 causes rendering issues when the player is at the same y as the top of a block for some reason
            y = 0.999F;
        } else {
            y = Mth.cos((float) (((float) ((System.nanoTime() / 100000L) % 20000L)) / 20000F * Math.PI * 2));
        }
        if (goal instanceof IGoalRenderPos) {
            BlockPos goalPos = ((IGoalRenderPos) goal).getGoalPos();
            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y /= 2;
            }
            y1 = 1 + y + goalPos.getY() - renderPosY;
            y2 = 1 - y + goalPos.getY() - renderPosY;
            minY = goalPos.getY() - renderPosY;
            maxY = minY + 2;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y1 -= 0.5;
                y2 -= 0.5;
                maxY--;
            }
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        } else if (goal instanceof GoalXZ) {
            GoalXZ goalPos = (GoalXZ) goal;
            minY = ctx.world().getMinY();
            maxY = ctx.world().getMaxY();

            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;

            y1 = 0;
            y2 = 0;
            minY -= renderPosY;
            maxY -= renderPosY;
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
            drawGoalXZBeacon(stack, ctx, (GoalXZ) goal, minY, maxY, partialTicks, color);
        } else if (goal instanceof GoalComposite) {
            // Simple way to determine if goals can be batched, without having some sort of GoalRenderer
            boolean batch = Arrays.stream(((GoalComposite) goal).goals()).allMatch(IGoalRenderPos.class::isInstance);
            BufferBuilder buf = bufferBuilder;
            if (batch) {
                buf = IRenderer.startLines(color, settings.goalRenderLineWidthPixels.value);
            }
            for (Goal g : ((GoalComposite) goal).goals()) {
                drawGoal(buf, stack, ctx, g, partialTicks, color, !batch);
            }
            if (batch) {
                IRenderer.endLines(buf, settings.renderGoalIgnoreDepth.value);
            }
        } else if (goal instanceof GoalInverted) {
            drawGoal(stack, ctx, ((GoalInverted) goal).origin, partialTicks, settings.colorInvertedGoalBox.value);
        } else if (goal instanceof GoalYLevel) {
            GoalYLevel goalpos = (GoalYLevel) goal;
            minX = ctx.player().position().x - settings.yLevelBoxSize.value - renderPosX;
            minZ = ctx.player().position().z - settings.yLevelBoxSize.value - renderPosZ;
            maxX = ctx.player().position().x + settings.yLevelBoxSize.value - renderPosX;
            maxZ = ctx.player().position().z + settings.yLevelBoxSize.value - renderPosZ;
            minY = ((GoalYLevel) goal).level - renderPosY;
            maxY = minY + 2;
            y1 = 1 + y + goalpos.level - renderPosY;
            y2 = 1 - y + goalpos.level - renderPosY;
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        }
    }

    private static void drawDankLitGoalBox(BufferBuilder bufferBuilder, PoseStack stack, Color colorIn, double minX, double maxX, double minZ, double maxZ, double minY, double maxY, double y1, double y2, boolean setupRender) {
        if (setupRender) {
            bufferBuilder = IRenderer.startLines(colorIn);
        }

        renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y1, settings.goalRenderLineWidthPixels.value);
        renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y2, settings.goalRenderLineWidthPixels.value);

        for (double y = minY; y < maxY; y += 16) {
            double max = Math.min(maxY, y + 16);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, minX, max, minZ, 0.0, 1.0, 0.0, settings.goalRenderLineWidthPixels.value);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, max, minZ, 0.0, 1.0, 0.0, settings.goalRenderLineWidthPixels.value);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, maxX, max, maxZ, 0.0, 1.0, 0.0, settings.goalRenderLineWidthPixels.value);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, max, maxZ, 0.0, 1.0, 0.0, settings.goalRenderLineWidthPixels.value);
        }

        if (setupRender) {
            IRenderer.endLines(bufferBuilder, settings.renderGoalIgnoreDepth.value);
        }
    }

    private static void renderHorizontalQuad(BufferBuilder bufferBuilder, PoseStack stack, double minX, double maxX, double minZ, double maxZ, double y, float lineWidth) {
        if (y != 0) {
            IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, maxX, y, minZ, 1.0, 0.0, 0.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, y, maxZ, 0.0, 0.0, 1.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, minX, y, maxZ, -1.0, 0.0, 0.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, y, minZ, 0.0, 0.0, -1.0, lineWidth);
        }
    }

    private static void drawGoalXZBeacon(PoseStack stack, IPlayerContext ctx, GoalXZ goal, double minY, double maxY, float partialTicks, Color color) {
        float time = settings.renderGoalAnimated.value ? (float) ctx.world().getGameTime() + partialTicks : 0.0F;
        int glowColor = (color.getRGB() & 0x00FFFFFF) | GOAL_BEACON_GLOW_ALPHA << 24;
        double height = maxY - minY;

        stack.pushPose();
        stack.translate(goal.getX() - posX(), minY - posY(), goal.getZ() - posZ());
        renderGoalXZBeaconLayer(stack, height, time, color.getRGB(), GOAL_BEACON_INNER_RADIUS, false);
        renderGoalXZBeaconLayer(stack, height, time, glowColor, GOAL_BEACON_GLOW_RADIUS, true);
        stack.popPose();
    }

    private static void renderGoalXZBeaconLayer(PoseStack stack, double height, float time, int color, float radius, boolean translucent) {
        BufferBuilder bufferBuilder = IRenderer.startBlockQuads();
        float scroll = Mth.frac(-time * 0.2F - Mth.floor(-time * 0.1F));

        stack.pushPose();
        stack.translate(0.5D, 0.0D, 0.5D);
        if (!translucent) {
            stack.pushPose();
            stack.mulPose(Axis.YP.rotationDegrees(time * 2.25F - 45.0F));
        }

        float v0 = -1.0F + scroll;
        float v1 = (float) (translucent ? height + v0 : height * (0.5F / radius) + v0);
        PoseStack.Pose pose = stack.last();
        if (translucent) {
            emitBeaconShell(bufferBuilder, pose, color, 0.0F, (float) height, -radius, -radius, radius, -radius, -radius, radius, radius, radius, v0, v1);
        } else {
            emitBeaconShell(bufferBuilder, pose, color, 0.0F, (float) height, 0.0F, radius, radius, 0.0F, -radius, 0.0F, 0.0F, -radius, v0, v1);
        }

        if (!translucent) {
            stack.popPose();
        }
        stack.popPose();

        IRenderer.endBuffer(bufferBuilder, IRenderer.beaconBeam(BeaconRenderer.BEAM_LOCATION, translucent, settings.renderGoalIgnoreDepth.value));
    }

    private static void emitBeaconShell(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float minY, float maxY,
                                        float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4,
                                        float v0, float v1) {
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x1, z1, x2, z2, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x4, z4, x3, z3, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x2, z2, x4, z4, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x3, z3, x1, z1, 0.0F, 1.0F, v0, v1);
    }

    private static void emitBeaconFace(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float minY, float maxY,
                                       float x1, float z1, float x2, float z2, float u0, float u1, float v0, float v1) {
        float nx = z2 - z1;
        float nz = x1 - x2;
        float length = Mth.sqrt(nx * nx + nz * nz);
        if (length != 0.0F) {
            nx /= length;
            nz /= length;
        }

        IRenderer.emitTexturedVertex(bufferBuilder, pose, x1, maxY, z1, color, u1, v0, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x1, minY, z1, color, u1, v1, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x2, minY, z2, color, u0, v1, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x2, maxY, z2, color, u0, v0, nx, 0.0F, nz);
    }
}
