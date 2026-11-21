package com.granmaestro.officeportable;

import android.app.ActivityManager;
import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/** Memory-related tuning, picked once from how much RAM the phone has. */
final class DeviceProfile {
    /** Phones with 3 GB of RAM or less (Android reports ~2.7-3.4 GB for "3 GB" phones). */
    private static final long LOW_RAM_BYTES = 3_600L * 1024 * 1024;

    final boolean lowMemory;
    final long totalRamBytes;
    /** How long the 300 MB+ file converter may sit idle before it is shut down. */
    final int converterIdleMs;
    /** Longest edge for embedded photos in a "lighter copy". */
    final int photoMaxDim;
    /** Offer a lighter copy when oversized photos add up to more than this. */
    final long offerLighterCopyBytes;

    private DeviceProfile(boolean lowMemory, long totalRamBytes) {
        this.lowMemory = lowMemory;
        this.totalRamBytes = totalRamBytes;
        this.converterIdleMs = lowMemory ? 1500 : 4000;
        this.photoMaxDim = lowMemory ? 1600 : 2048;
        this.offerLighterCopyBytes = (lowMemory ? 4L : 10L) * 1024 * 1024;
    }

    static DeviceProfile detect(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        long total = 0;
        boolean low = false;
        if (am != null) {
            am.getMemoryInfo(mi);
            total = mi.totalMem;
            low = am.isLowRamDevice() || (total > 0 && total < LOW_RAM_BYTES);
        }
        return new DeviceProfile(low, total);
    }

    /** Settings handed to the editor pages as window.__OP_CONFIG. */
    String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("lowMemory", lowMemory);
            o.put("x2tIdleMs", converterIdleMs);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }
}
