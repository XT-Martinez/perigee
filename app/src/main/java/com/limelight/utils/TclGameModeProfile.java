package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Switches a TCL/MediaTek TV to its Game picture mode while a stream runs and puts the
 * previous picture mode back when the stream ends.
 *
 * <p>The mode can also be selected by writing the hidden
 * {@code Settings.System.tcl_sys_tmms_special_mode} key, but the settings provider
 * rejects that key for normal apps (it is not in the public system settings list and
 * the enforcement ignores app permissions). TCL's own display service has no such
 * restriction, so this talks to it over Binder instead: picture mode GAME(5) with the
 * GLOBAL field replicates exactly what TCL's mode manager does for {@code game}, and
 * GLOBAL_OFF restores the TV's underlying picture mode.
 *
 * <p>Verified on a TCL G08 (mt5896, AU04) from a normal app UID: the service is
 * reachable, the calls are accepted and the reported picture mode goes 0 -> 5 -> 0.
 * No root, no permissions.
 */
public final class TclGameModeProfile {
    private static final String TAG = "TclGameMode";

    // TCL display service; codes verified against the installed
    // /system_ext/framework/com.tcl.tv.display.jar Stub$Proxy.
    private static final String SERVICE = "tcl_tv_display";
    private static final String DESCRIPTOR = "com.tcl.tv.display.ITvDisplayManagerService";
    private static final int TX_SET_PICTURE_MODE = 26; // setPictureMode(window, mode, field, act)
    private static final int TX_GET_PICTURE_MODE = 27; // getPictureMode() -> int

    private static final int MODE_GAME = 5;
    private static final int FIELD_GLOBAL = 0;     // EN_PICTURE_MODE_FIELD_GLOBAL
    private static final int FIELD_GLOBAL_OFF = 1; // EN_PICTURE_MODE_FIELD_GLOBAL_OFF
    private static final int ACT_EXEC_SAVE = 2;    // EN_TCL_ACT_EXEC_SAVE

    /** Window 0 is TCL's "current input" convention; the service resolves it itself. */
    private static final int CURRENT_WINDOW = 0;

    private static final String PREFS_NAME = "TclGameModeProfile";
    private static final String PREF_APPLIED = "applied";

    /**
     * Number of live stream activities. Game mode is applied when the first one starts
     * and restored when the last one ends, so activity recreation or overlapping
     * instances cannot have an older instance restore while a newer stream is running.
     */
    private static int activeSessions = 0;

    private TclGameModeProfile() {
    }

    /**
     * @return true if this device is a MediaTek television SoC, the only hardware where
     *         this profile is currently known to be meaningful.
     */
    public static boolean isSupported() {
        return Build.HARDWARE != null && Build.HARDWARE.startsWith("mt5");
    }

    /**
     * Called from the stream activity's onCreate. Applies the profile only for the first
     * live stream; later calls just register the session.
     */
    public static synchronized void onStreamStart(Context context) {
        if (activeSessions == 0) {
            apply(context);
        }
        activeSessions++;
    }

    /**
     * Called from the stream activity's onDestroy. Restores the pre-stream picture mode
     * only after the last live stream ends.
     */
    public static synchronized void onStreamEnd(Context context) {
        if (activeSessions > 0) {
            activeSessions--;
        }
        if (activeSessions == 0) {
            restore(context);
        }
    }

    /**
     * Recovery hook for the PC list: puts the picture mode back if the app died while a
     * stream had it applied, but never touches a live stream's state.
     */
    public static synchronized void restoreIfIdle(Context context) {
        if (activeSessions == 0) {
            restore(context);
        }
    }

    private static void apply(Context context) {
        if (!isSupported()) {
            Log.i(TAG, "Skipping: not a MediaTek TV (" + Build.HARDWARE + ")");
            return;
        }

        final IBinder binder = findDisplayService();
        if (binder == null) {
            Log.w(TAG, "Skipping: TCL display service is not reachable");
            return;
        }

        final int current = getPictureMode(binder);
        if (current == MODE_GAME) {
            Log.i(TAG, "Game picture mode is already active");
            return;
        }

        if (!setPictureMode(binder, MODE_GAME, FIELD_GLOBAL)) {
            Log.w(TAG, "TCL display service rejected the Game picture mode");
            return;
        }

        // Persist only after the TV accepted the change, so a crash cannot leave a
        // marker that makes the next launch "restore" a mode it never applied.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_APPLIED, true)
                .commit();
        Log.i(TAG, "Game picture mode applied (previous mode was "
                + (current < 0 ? "unknown" : String.valueOf(current)) + ")");
    }

    private static void restore(Context context) {
        if (!isSupported()) {
            return;
        }

        final SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_APPLIED, false)) {
            return;
        }

        final IBinder binder = findDisplayService();
        if (binder == null) {
            Log.w(TAG, "Cannot restore picture mode: TCL display service is not reachable");
            return;
        }

        if (!setPictureMode(binder, 0, FIELD_GLOBAL_OFF)) {
            Log.w(TAG, "TCL display service rejected the restore");
            return;
        }

        prefs.edit().clear().apply();
        Log.i(TAG, "Restored the TV's previous picture mode");
    }

    private static IBinder findDisplayService() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            Object service = getService.invoke(null, SERVICE);
            return (service instanceof IBinder) ? (IBinder) service : null;
        } catch (Throwable t) {
            Log.w(TAG, "TCL display service lookup failed", t);
            return null;
        }
    }

    /** @return the effective picture mode, or -1 if it could not be read. */
    private static int getPictureMode(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(TX_GET_PICTURE_MODE, data, reply, 0)) {
                return -1;
            }
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "getPictureMode failed", t);
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static boolean setPictureMode(IBinder binder, int mode, int field) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(CURRENT_WINDOW);
            data.writeInt(mode);
            data.writeInt(field);
            data.writeInt(ACT_EXEC_SAVE);
            if (!binder.transact(TX_SET_PICTURE_MODE, data, reply, 0)) {
                return false;
            }
            reply.readException();
            return reply.readInt() != 0;
        } catch (Throwable t) {
            Log.w(TAG, "setPictureMode failed", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
