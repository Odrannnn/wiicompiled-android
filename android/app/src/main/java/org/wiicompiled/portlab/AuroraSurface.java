package org.wiicompiled.portlab;

import android.content.Context;
import android.view.SurfaceHolder;
import org.libsdl.app.SDLSurface;

/** Connects SDL's real Surface lifecycle to Aurora's swapchain guard. */
public final class AuroraSurface extends SDLSurface {
    public AuroraSurface(Context context) { super(context); }

    private static native void nativeSetSurfaceReady(boolean ready);

    @Override public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        nativeSetSurfaceReady(true);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSetSurfaceReady(false);
        super.surfaceDestroyed(holder);
    }
}
