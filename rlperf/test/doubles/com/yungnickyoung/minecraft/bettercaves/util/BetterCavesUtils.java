package com.yungnickyoung.minecraft.bettercaves.util;
/** Test double: the real class drags in half of Minecraft. getLocal is the same one-liner. */
public class BetterCavesUtils { public static int getLocal(int coordinate) { return coordinate & 0xF; } }
