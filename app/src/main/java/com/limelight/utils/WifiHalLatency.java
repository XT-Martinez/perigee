package com.limelight.utils;

import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Best-effort request to put the Wi-Fi chip into low-latency mode.
 *
 * Android normally does this through WifiLockManager, but it only calls the HAL when
 * the chip advertises low-latency support. This helper talks to the Wi-Fi HAL chip
 * directly so the actual outcome is visible in logcat. On locked-down TV firmware the
 * SELinux policy may refuse the service lookup; that failure is logged and ignored.
 */
public final class WifiHalLatency {
    private static final String TAG = "WifiHalLatency";

    private static final String WIFI_SERVICE = "android.hardware.wifi.IWifi/default";
    private static final String WIFI_DESCRIPTOR = "android.hardware.wifi.IWifi";
    private static final String CHIP_DESCRIPTOR = "android.hardware.wifi.IWifiChip";

    // Transaction codes from the framework's generated AIDL proxies.
    private static final int TRANSACTION_getChip = 1;           // IWifi
    private static final int TRANSACTION_setLatencyMode = 42;   // IWifiChip

    // The framework maps "low latency enabled" to this enum value.
    private static final int LATENCY_MODE_LOW = 1;

    private WifiHalLatency() {
    }

    /**
     * @return true if the HAL accepted the low-latency request.
     */
    public static boolean requestLowLatency() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "Skipping, SDK " + Build.VERSION.SDK_INT + " has no AIDL Wi-Fi HAL");
            return false;
        }

        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder wifi = (IBinder) getService.invoke(null, WIFI_SERVICE);
            Log.i(TAG, "IWifi binder = " + wifi);
            if (wifi == null) {
                return false;
            }

            IBinder chip = getChip(wifi);
            Log.i(TAG, "IWifiChip binder = " + chip);
            if (chip == null) {
                return false;
            }

            return setLatencyMode(chip, LATENCY_MODE_LOW);
        } catch (Throwable t) {
            Log.w(TAG, "Low-latency HAL request failed: " + t);
            return false;
        }
    }

    private static IBinder getChip(IBinder wifi) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(WIFI_DESCRIPTOR);
            data.writeInt(0);
            if (!wifi.transact(TRANSACTION_getChip, data, reply, 0)) {
                Log.w(TAG, "getChip: HAL did not answer");
                return null;
            }
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static boolean setLatencyMode(IBinder chip, int mode) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CHIP_DESCRIPTOR);
            data.writeInt(mode);
            if (!chip.transact(TRANSACTION_setLatencyMode, data, reply, 0)) {
                Log.w(TAG, "setLatencyMode: HAL did not answer");
                return false;
            }
            reply.readException();
            Log.i(TAG, "setLatencyMode(" + mode + ") accepted");
            return true;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
