package net.minecraft.util.math;

public class Vec3i {
    private final int x, y, z;
    public Vec3i(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    public int func_177958_n() { return x; }
    public int func_177956_o() { return y; }
    public int func_177952_p() { return z; }
    @Override public boolean equals(Object o) {
        return o instanceof Vec3i && ((Vec3i) o).func_177958_n() == func_177958_n() && ((Vec3i) o).func_177956_o() == func_177956_o()
                && ((Vec3i) o).func_177952_p() == func_177952_p();
    }
    @Override public int hashCode() { return (func_177956_o() + func_177952_p() * 31) * 31 + func_177958_n(); }
    @Override public String toString() { return func_177958_n() + "," + func_177956_o() + "," + func_177952_p(); }
}
