package princeps.flownav.traj;

import princeps.flownav.math.Vec3;
import java.util.List;

/**
 * A dense, arc-length-parameterized polyline the motor drives along. Produced from
 * a {@link flownav.core.route.Route} by a {@link TrajectorySmoother}; corners have
 * already been rounded, so following it verbatim yields curved motion.
 *
 * <p>Parameterization: {@code s} is the cumulative <b>horizontal</b> arc length in
 * blocks from the first point, clamped to {@code [0, length()]}. Horizontal
 * parameterization keeps pursuit lookaheads meaningful across step-ups/drops.
 */
public final class Trajectory {

    private final List<Vec3> points;
    private final double[] cumulative;
    private final double length;

    public Trajectory(List<Vec3> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("trajectory needs at least 2 points, got " + points.size());
        }
        this.points = List.copyOf(points);
        this.cumulative = new double[points.size()];
        double sum = 0;
        for (int i = 1; i < points.size(); i++) {
            double seg = points.get(i).horizontalDistanceTo(points.get(i - 1));
            sum += Math.max(seg, 1e-9); // keep parameterization strictly increasing
            cumulative[i] = sum;
        }
        this.length = sum;
    }

    public List<Vec3> points() {
        return points;
    }

    public double length() {
        return length;
    }

    public Vec3 start() {
        return points.get(0);
    }

    public Vec3 end() {
        return points.get(points.size() - 1);
    }

    /** Point at horizontal arc length {@code s}, clamped to the trajectory ends. */
    public Vec3 pointAt(double s) {
        if (s <= 0) {
            return points.get(0);
        }
        if (s >= length) {
            return end();
        }
        int hi = upperSegment(s);
        double segStart = cumulative[hi - 1];
        double segLen = cumulative[hi] - segStart;
        double t = (s - segStart) / segLen;
        return points.get(hi - 1).lerp(points.get(hi), t);
    }

    /**
     * Arc-length parameter of the point on the trajectory nearest to {@code pos}
     * (horizontal distance only). To stay robust against self-near passes, the
     * search is windowed: only parameters in {@code [minS, length]} are considered.
     */
    public double nearestParam(Vec3 pos, double minS) {
        double bestS = Math.max(0, Math.min(minS, length));
        double bestD = Double.MAX_VALUE;
        for (int i = 1; i < points.size(); i++) {
            if (cumulative[i] < minS) {
                continue;
            }
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double ax = a.x(), az = a.z();
            double bx = b.x(), bz = b.z();
            double dx = bx - ax, dz = bz - az;
            double lenSq = dx * dx + dz * dz;
            double t;
            if (lenSq < 1e-12) {
                t = 0;
            } else {
                t = ((pos.x() - ax) * dx + (pos.z() - az) * dz) / lenSq;
                t = Math.max(0, Math.min(1, t));
            }
            double px = ax + dx * t, pz = az + dz * t;
            double ddx = pos.x() - px, ddz = pos.z() - pz;
            double d = ddx * ddx + ddz * ddz;
            double s = cumulative[i - 1] + (cumulative[i] - cumulative[i - 1]) * t;
            if (s < minS) {
                s = minS;
            }
            if (d < bestD - 1e-12) {
                bestD = d;
                bestS = s;
            }
        }
        return bestS;
    }

    /** Horizontal distance from {@code pos} to the trajectory point at parameter {@code s}. */
    public double crossTrackError(Vec3 pos, double s) {
        return pos.horizontalDistanceTo(pointAt(s));
    }

    public double remaining(double s) {
        return Math.max(0, length - s);
    }

    private int upperSegment(double s) {
        int lo = 1;
        int hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < s) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
