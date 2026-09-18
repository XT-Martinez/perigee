package com.limelight.utils;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

/**
 * Workaround for a periodic 5 GHz latency stall on TCL/MediaTek televisions.
 *
 * The TV keeps a background Wi-Fi Direct (P2P) listen operation running even when no
 * peer or group is active. Every ~1.15 s the radio leaves the router's channel for that
 * operation, which produces an 80-100 ms outage of the infrastructure link and stutters
 * game streaming. Stopping the listen operation removes the stall. The stock
 * wireless-display stack re-enables listening when it is actually used.
 *
 * Verified on a TCL G08 (AU04, mt5896): the measured 1.149 s / 52-per-minute spike train
 * went to zero events over 3 minutes (maximum RTT 19 ms). This is public framework API,
 * no root or special permissions are required.
 */
public final class WifiDirectLatencyFix {
    private static final String TAG = "WifiDirectFix";

    private static WifiP2pManager manager;
    private static WifiP2pManager.Channel channel;

    private WifiDirectLatencyFix() {
    }

    /**
     * @return true if this device is a MediaTek television SoC, the only hardware where
     *         this workaround is currently known to be needed.
     */
    public static boolean isSupported() {
        return Build.HARDWARE != null && Build.HARDWARE.startsWith("mt5");
    }

    /**
     * Requests a single stop of background Wi-Fi Direct listening at stream startup.
     */
    public static void stopAtStreamStart(Context context) {
        if (!isSupported()) {
            Log.i(TAG, "Skipping: not a MediaTek TV (" + Build.HARDWARE + ")");
            return;
        }

        stopListening(context);
    }

    private static void stopListening(Context context) {
        try {
            ensureChannel(context);
            if (manager == null || channel == null) {
                Log.w(TAG, "Wi-Fi Direct service unavailable");
                return;
            }
            manager.stopListening(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "Background Wi-Fi Direct listening stopped");
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "Could not stop Wi-Fi Direct listening, reason=" + reason);
                }
            });
        } catch (Throwable t) {
            // A latency tweak must never take down the stream.
            Log.w(TAG, "Wi-Fi Direct stop failed", t);
        }
    }

    private static void ensureChannel(Context context) {
        if (channel != null) {
            return;
        }
        Context app = context.getApplicationContext();
        manager = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            return;
        }
        channel = manager.initialize(app, Looper.getMainLooper(), () -> {
            Log.w(TAG, "Wi-Fi Direct channel disconnected");
            channel = null;
        });
    }
}
