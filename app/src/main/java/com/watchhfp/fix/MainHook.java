package com.watchhfp.fix;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CarWithHfpFix";
    private static final String WATCH_MAC = "04:24:05:2B:3D:1A";
    private static final int PROFILE_HEADSET = 1;
    private static final int POLICY_ALLOW = 100;

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
    private static void logErr(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        XposedBridge.log(TAG + ": ERROR " + msg + " : " + (t != null ? t.getMessage() : ""));
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只注入 CarWith，完全不碰蓝牙进程
        if (!"com.miui.carlink".equals(lpparam.packageName)) {
            return;
        }
        log("✅ Hooked into com.miui.carlink");

        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader,
                "sendBroadcast",
                "android.content.Intent",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object intentObj = param.args[0];
                        String action = (String) XposedHelpers.callMethod(intentObj, "getAction");
                        if ("com.iccoa.carlink.DISCONNECT".equals(action)) {
                            log("📢 Caught ICCOA DISCONNECT, restoring HFP");
                            // 直接在 CarWith 进程内调用蓝牙 API
                            restoreHfp();
                        }
                    }
                });
    }

    private void restoreHfp() {
        try {
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            if (btAdapter == null) {
                logErr("BluetoothAdapter is null", null);
                return;
            }

            // 从已配对列表找到手表
            BluetoothDevice targetDevice = null;
            Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
            for (BluetoothDevice dev : paired) {
                if (WATCH_MAC.equals(dev.getAddress())) {
                    targetDevice = dev;
                    break;
                }
            }
            if (targetDevice == null) {
                logErr("Watch not found: " + WATCH_MAC, null);
                return;
            }
            log("✅ Found watch device");

            // 反射调用 setProfileConnectionPolicy
            int ret = (int) XposedHelpers.callMethod(
                    btAdapter,
                    "setProfileConnectionPolicy",
                    targetDevice,
                    PROFILE_HEADSET,
                    POLICY_ALLOW
            );
            log("✅ setProfileConnectionPolicy success, ret=" + ret);

        } catch (Throwable e) {
            logErr("❌ restoreHfp failed", e);
        }
    }
}
