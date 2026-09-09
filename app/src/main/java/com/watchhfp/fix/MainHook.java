package com.watchhfp.fix;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CarWithHfpFix";
    private static final String WATCH_MAC = "04:24:05:2b:3d:1a";
    private static final String TRIGGER_FILE = "/data/local/tmp/watch_hfp_trigger";
    private static final int PROFILE_HEADSET = 1;
    private static final int POLICY_ALLOW = 100;
    private static final long POLL_INTERVAL_MS = 500;

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
    private static void logErr(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        XposedBridge.log(TAG + ": ERROR " + msg + " : " + t.getMessage());
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        log("ℹ️ Package loaded: " + lpparam.packageName);

        // ========== 1. Hook com.miui.carlink ==========
        if ("com.miui.carlink".equals(lpparam.packageName)) {
            log("✅ Hooked com.miui.carlink");
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader,
                    "sendBroadcast",
                    "android.content.Intent",
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object intentObj = param.args[0];
                            String action = (String) XposedHelpers.callMethod(intentObj, "getAction");
                            if ("com.iccoa.carlink.DISCONNECT".equals(action)) {
                                log("📢 Caught ICCOA DISCONNECT, write trigger file");
                                new Thread(() -> {
                                    try {
                                        Runtime.getRuntime().exec(new String[]{
                                                "su", "-c",
                                                "touch " + TRIGGER_FILE
                                        }).waitFor();
                                        log("✅ Trigger file created");
                                    } catch (Exception e) {
                                        logErr("❌ Failed to create trigger file", e);
                                    }
                                }).start();
                            }
                        }
                    });
            return;
        }

        // ========== 2. Hook com.android.bluetooth.AdapterApp ==========
        if ("com.android.bluetooth".equals(lpparam.packageName)) {
            log("✅ Hooked com.android.bluetooth");
            Class<?> adapterAppCls = XposedHelpers.findClass("com.android.bluetooth.btservice.AdapterApp", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(adapterAppCls,
                    "onCreate",
                    new de.robv.android.xposed.XC_MethodHook() {
                        final AtomicBoolean running = new AtomicBoolean(true);
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("✅ AdapterApp onCreate, starting poll thread");
                            new Thread(() -> {
                                while(running.get()) {
                                    File trigger = new File(TRIGGER_FILE);
                                    if (trigger.exists()) {
                                        log("🎯 Trigger file detected, restore HFP");
                                        restoreWatchHfpPolicy();
                                        // 不再在这里su删除！删除交给carlink或者外部
                                    }
                                    try {
                                        Thread.sleep(POLL_INTERVAL_MS);
                                    } catch (InterruptedException ie) {
                                        running.set(false);
                                    }
                                }
                                log("ℹ️ Poll thread exit");
                            },"WatchHfpPoll").start();
                        }
                    });
            return;
        }
    }

    /**
     * 使用公开API BluetoothAdapter.setProfileConnectionPolicy
     * 不需要内部BluetoothDatabaseManager
     */
    private void restoreWatchHfpPolicy() {
        try {
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            if (btAdapter == null) {
                logErr("btAdapter is null", null);
                return;
            }
            BluetoothDevice device = btAdapter.getRemoteDevice(WATCH_MAC);
            // 反射调用 setProfileConnectionPolicy，该方法是 @hide
            XposedHelpers.callMethod(btAdapter,
                    "setProfileConnectionPolicy",
                    device,
                    PROFILE_HEADSET,
                    POLICY_ALLOW
            );
            log("✅ setProfileConnectionPolicy OK via BluetoothAdapter, MAC:" + WATCH_MAC);
        } catch (Throwable e) {
            logErr("❌ setProfileConnectionPolicy failed", e);
        }
    }
}
