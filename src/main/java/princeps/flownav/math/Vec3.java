package princeps.flownav.math;

/**
 * Immutable 3D double vector.
 *
 * <p>Coordinate conventions follow Minecraft: X east, Y up, Z south. All of
 * flownav-core speaks this frame so the Minecraft adapter is a pure pass-through.
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(double f) {
        return new Vec3(x * f, y * f, z * f);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSq());
    }

    public double horizontalLengthSq() {
        return x * x + z * z;
    }

    public double horizontalLength() {
        return Math.sqrt(horizontalLengthSq());
    }

    public double distanceTo(Vec3 o) {
        return sub(o).length();
    }

    public double distanceSqTo(Vec3 o) {
        return sub(o).lengthSq();
    }

    public double horizontalDistanceTo(Vec3 o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Returns the normalized vector, or {@link #ZERO} if shorter than 1e-9. */
    public Vec3 normalize() {
        double len = length();
        return len < 1e-9 ? ZERO : scale(1.0 / len);
    }

    /** Linear interpolation: {@code this + (to - this) * t}. */
    public Vec3 lerp(Vec3 to, double t) {
        return new Vec3(x + (to.x - x) * t, y + (to.y - y) * t, z + (to.z - z) * t);
    }

    public Vec3 withY(double newY) {
        return new Vec3(x, newY, z);
    }

    /** Projection onto the ground plane (X/Z), dropping Y. */
    public Vec2 xz() {
        return new Vec2(x, z);
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
