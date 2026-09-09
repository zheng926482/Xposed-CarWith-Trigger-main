package com.watchhfp.fix;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CarWithHfpFix";
    private static final String WATCH_MAC = "04:24:05:2B:3D:1A";
    private static final String TEST_ACTION = "com.watchhfp.fix.TEST_RESTORE";
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
        // 只注入 CarWith 进程，完全不碰蓝牙进程
        if (!"com.miui.carlink".equals(lpparam.packageName)) {
            return;
        }
        log("✅ Hooked into com.miui.carlink");

        // Hook Application.onCreate 注册调试广播接收器
        XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context ctx = (Context) param.thisObject;
                        IntentFilter filter = new IntentFilter(TEST_ACTION);
                        ctx.registerReceiver(new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                log("🧪 Received test broadcast, run restoreHfp");
                                restoreHfp();
                            }
                        }, filter);
                        log("✅ Test receiver registered");
                    }
                });

        // Hook 断开广播：真实上车场景触发
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
            log("✅ Found watch device: " + WATCH_MAC);

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
