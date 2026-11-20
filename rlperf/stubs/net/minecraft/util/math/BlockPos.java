package net.minecraft.util.math;

public class BlockPos extends Vec3i {
    public BlockPos(int x, int y, int z) { super(x, y, z); }

    /** add(Vec3i): like the real one, returns this when the offset is zero. */
    public BlockPos func_177971_a(Vec3i v) {
        return v.func_177958_n() == 0 && v.func_177956_o() == 0 && v.func_177952_p() == 0 ? this
                : new BlockPos(func_177958_n() + v.func_177958_n(), func_177956_o() + v.func_177956_o(), func_177952_p() + v.func_177952_p());
    }

    public static class MutableBlockPos extends BlockPos {
        private int x, y, z;
        public MutableBlockPos() { super(0, 0, 0); }
        public MutableBlockPos func_181079_c(int x, int y, int z) { this.x = x; this.y = y; this.z = z; return this; }
        @Override public int func_177958_n() { return x; }
        @Override public int func_177956_o() { return y; }
        @Override public int func_177952_p() { return z; }
    }
}
