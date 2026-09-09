package com.watchhfp.fix;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

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
                                        // root 创建触发文件
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
                                        restoreWatchHfpPolicy(lpparam.classLoader);
                                        try {
                                            Runtime.getRuntime().exec(new String[]{"su","-c","rm -f "+TRIGGER_FILE}).waitFor();
                                            log("✅ Trigger file removed");
                                        } catch (Exception e) {
                                            logErr("❌ Cannot delete trigger file", e);
                                        }
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

    private void restoreWatchHfpPolicy(ClassLoader classLoader) {
        try {
            Class<?> dbManagerCls = XposedHelpers.findClass("com.android.bluetooth.BluetoothDatabaseManager", classLoader);
            Object dbInstance = XposedHelpers.callStaticMethod(dbManagerCls, "getInstance");
            Method setPolicyMethod = dbManagerCls.getDeclaredMethod(
                    "setProfileConnectionPolicy",
                    String.class, int.class, int.class
            );
            setPolicyMethod.invoke(dbInstance, WATCH_MAC, PROFILE_HEADSET, POLICY_ALLOW);
            log("✅ setProfileConnectionPolicy OK. MAC:" + WATCH_MAC);
        } catch (Throwable e) {
            logErr("❌ setProfileConnectionPolicy failed", e);
        }
    }
}
