package princeps.flownav.math;

/**
 * Immutable 2D double vector on the ground plane. Components are named
 * {@code x}/{@code z} to match the Minecraft horizontal axes.
 */
public record Vec2(double x, double z) {

    public static final Vec2 ZERO = new Vec2(0, 0);

    public Vec2 add(Vec2 o) {
        return new Vec2(x + o.x, z + o.z);
    }

    public Vec2 sub(Vec2 o) {
        return new Vec2(x - o.x, z - o.z);
    }

    public Vec2 scale(double f) {
        return new Vec2(x * f, z * f);
    }

    public double dot(Vec2 o) {
        return x * o.x + z * o.z;
    }

    /** 2D cross product (signed area); positive when {@code o} lies counter-clockwise of this (viewed from +Y). */
    public double cross(Vec2 o) {
        return x * o.z - z * o.x;
    }

    public double lengthSq() {
        return x * x + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSq());
    }

    public double distanceTo(Vec2 o) {
        return sub(o).length();
    }

    /** Returns the normalized vector, or {@link #ZERO} if shorter than 1e-9. */
    public Vec2 normalize() {
        double len = length();
        return len < 1e-9 ? ZERO : scale(1.0 / len);
    }

    public Vec3 at(double y) {
        return new Vec3(x, y, z);
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f)", x, z);
    }
}
