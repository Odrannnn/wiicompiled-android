package org.wiicompiled.portlab;

final class NativeProbe {
    static { System.loadLibrary("wiicompiled_probe"); }
    static native void configureAndroidPaths(String internal, String external);
    static native void configureCustomVulkanDriver(String directory, String soname, String label,
        String nativeLibraryDirectory, String temporaryDirectory, String filesDirectory);
    static native void configureModOverlays(String overlayRoots);
    static native String run();
}
