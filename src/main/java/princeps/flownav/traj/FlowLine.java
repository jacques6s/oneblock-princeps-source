package princeps.flownav.traj;

import java.util.ArrayList;
import java.util.List;
import princeps.flownav.math.Vec2;
import princeps.flownav.math.Vec3;

/**
 * Decoupled corner-arc smoother — a port of FlowNav's {@code CornerSmoother} that
 * operates on a raw flat waypoint polyline instead of a planner {@code Route}/{@code NavSpace}.
 *
 * <p>It rounds every interior corner with a circular fillet arc and resamples the result
 * densely ({@code <= 0.25} blocks apart, horizontally), yielding the curved line FlowNav's
 * pursuit rides. The NavSpace clearance check of the original is intentionally dropped: the
 * caller ({@code MovementHelper.moveAlongPath}) reverts to an exact local aim the instant the
 * player touches a wall, so the collision-recovery is the safety net for the rare tight-corner
 * clip — exactly as FlowNav documents it.
 */
public final class FlowLine {

    /** Maximum horizontal spacing between consecutive trajectory points, in blocks. */
    private static final double MAX_SPACING = 0.25;
    /** Fillet radii below this are not worth an arc; the sharp corner is kept. */
    private static final double MIN_RADIUS = 0.1;
    /** Corners turning less than this are treated as straight and not rounded. */
    private static final double MIN_TURN_DEG = 10.0;
    /** Fraction of the shorter adjacent segment a fillet may consume on each side. */
    private static final double MAX_SEGMENT_FRACTION = 0.45;
    private static final double EPS = 1e-9;

    private FlowLine() {
    }

    /** Builds an arc-rounded, densely resampled trajectory from flat waypoint centres. */
    public static Trajectory smooth(List<Vec3> waypoints, double cornerRadius) {
        return new Trajectory(smoothPoints(waypoints, cornerRadius));
    }

    /**
     * The arc-rounded, resampled point list without the {@link Trajectory} wrapper — for consumers
     * (like the path renderer) that only draw the points: skips Trajectory's defensive copy and
     * cumulative arc-length array, which would be allocated and thrown away.
     */
    public static List<Vec3> smoothPoints(List<Vec3> waypoints, double cornerRadius) {
        List<Vec3> pts = dedup(waypoints);
        if (pts.size() < 2) {
            List<Vec3> two = new ArrayList<>(pts);
            if (two.isEmpty()) {
                throw new IllegalArgumentException("cannot smooth an empty line");
            }
            two.add(two.get(0)); // degenerate single point: zero-length trajectory
            return two;
        }
        List<Vec3> polyline = new ArrayList<>();
        polyline.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            List<Vec3> arc = tryRoundCorner(pts.get(i - 1), pts.get(i), pts.get(i + 1), cornerRadius);
            if (arc != null) {
                polyline.addAll(arc);
            } else {
                polyline.add(pts.get(i));
            }
        }
        polyline.add(pts.get(pts.size() - 1));
        polyline = dedup(polyline);
        if (polyline.size() < 2) {
            polyline.add(polyline.get(0));
        }
        return resample(polyline);
    }

    private static List<Vec3> tryRoundCorner(Vec3 prev, Vec3 corner, Vec3 next, double cornerRadius) {
        if (Math.abs(prev.y() - corner.y()) > EPS || Math.abs(next.y() - corner.y()) > EPS) {
            return null; // only flat corners are rounded
        }
        Vec2 in = corner.xz().sub(prev.xz());
        Vec2 out = next.xz().sub(corner.xz());
        double lenIn = in.length();
        double lenOut = out.length();
        if (lenIn < EPS || lenOut < EPS) {
            return null;
        }
        Vec2 u = in.scale(1.0 / lenIn);
        Vec2 v = out.scale(1.0 / lenOut);
        double turn = Math.acos(clamp(u.dot(v), -1.0, 1.0));
        if (Math.toDegrees(turn) <= MIN_TURN_DEG) {
            return null; // effectively straight
        }
        double cross = u.cross(v);
        if (Math.abs(cross) < EPS) {
            return null; // exact reversal: no consistent turn side
        }
        double tanHalf = Math.tan(turn * 0.5);
        double maxTangent = MAX_SEGMENT_FRACTION * Math.min(lenIn, lenOut);
        double radius = Math.min(cornerRadius, Math.min(maxTangent, maxTangent / tanHalf));
        if (radius < MIN_RADIUS - EPS) {
            return null;
        }
        double side = cross > 0 ? 1.0 : -1.0;
        return sampleArc(corner, u, v, side, turn, tanHalf, radius);
    }

    private static List<Vec3> sampleArc(Vec3 corner, Vec2 u, Vec2 v, double side, double turn,
            double tanHalf, double radius) {
        double tangent = radius * tanHalf;
        Vec2 p = corner.xz();
        Vec2 entry = p.sub(u.scale(tangent));
        Vec2 exit = p.add(v.scale(tangent));
        Vec2 toCenter = new Vec2(-u.z() * side, u.x() * side);
        Vec2 center = entry.add(toCenter.scale(radius));
        Vec2 spoke = entry.sub(center);
        int samples = Math.max(1, (int) Math.ceil(radius * turn / MAX_SPACING));
        List<Vec3> arc = new ArrayList<>(samples + 1);
        double y = corner.y();
        for (int i = 0; i < samples; i++) {
            double phi = side * turn * i / samples;
            double cos = Math.cos(phi);
            double sin = Math.sin(phi);
            Vec2 rotated = new Vec2(spoke.x() * cos - spoke.z() * sin, spoke.x() * sin + spoke.z() * cos);
            arc.add(center.add(rotated).at(y));
        }
        arc.add(exit.at(y));
        return arc;
    }

    private static List<Vec3> dedup(List<Vec3> points) {
        List<Vec3> kept = new ArrayList<>(points.size());
        for (Vec3 point : points) {
            if (!kept.isEmpty() && point.distanceSqTo(kept.get(kept.size() - 1)) < EPS * EPS) {
                continue;
            }
            kept.add(point);
        }
        return kept;
    }

    private static List<Vec3> resample(List<Vec3> points) {
        List<Vec3> out = new ArrayList<>();
        out.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double gap = a.horizontalDistanceTo(b);
            int pieces = Math.max(1, (int) Math.ceil(gap / MAX_SPACING - EPS));
            for (int j = 1; j < pieces; j++) {
                out.add(a.lerp(b, (double) j / pieces));
            }
            out.add(b);
        }
        return out;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
