package com.watchhfp.fix;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CarWithHfpFix";
    private static final String WATCH_MAC_LOWER = "04:24:05:2B:3D:1A";
    private static final String WATCH_MAC = WATCH_MAC_LOWER.toUpperCase();
    private static final String TRIGGER_FILE = "/data/local/tmp/watch_hfp_trigger";
    private static final int PROFILE_HEADSET = 1;
    private static final int POLICY_ALLOW = 100;
    private static final long POLL_INTERVAL_MS = 1000;
    private static final long COOLDOWN_MS = 5000;
    private final AtomicLong lastRunTs = new AtomicLong(0);

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
                                        // 5.5s 后清除触发器
                                        Thread.sleep(5500);
                                        Runtime.getRuntime().exec(new String[]{
                                                "su", "-c",
                                                "rm -f " + TRIGGER_FILE
                                        }).waitFor();
                                        log("✅ Trigger file removed by carlink");
                                    } catch (Exception e) {
                                        logErr("❌ File op failed in carlink", e);
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
                                    long now = System.currentTimeMillis();
                                    if (trigger.exists() && (now - lastRunTs.get()) > COOLDOWN_MS) {
                                        log("🎯 Trigger file detected, restore HFP, MAC=" + WATCH_MAC);
                                        restoreWatchHfpPolicy();
                                        lastRunTs.set(now);
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

    private void restoreWatchHfpPolicy() {
        try {
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            if (btAdapter == null) {
                logErr("btAdapter is null", null);
                return;
            }
            BluetoothDevice device = btAdapter.getRemoteDevice(WATCH_MAC);
            XposedHelpers.callMethod(btAdapter,
                    "setProfileConnectionPolicy",
                    device,
                    PROFILE_HEADSET,
                    POLICY_ALLOW
            );
            log("✅ setProfileConnectionPolicy OK, MAC:" + WATCH_MAC);
        } catch (Throwable e) {
            logErr("❌ setProfileConnectionPolicy failed", e);
        }
    }
}
