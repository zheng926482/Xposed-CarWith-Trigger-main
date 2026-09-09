package com.watchhfp.fix;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CarWithHfpFix";
    private static final String WATCH_MAC = "04:24:05:2B:3D:1A";
    private static final String TRIGGER_FILE = "/data/local/tmp/watch_hfp_trigger";
    private static final int PROFILE_HEADSET = 1;
    private static final int POLICY_ALLOW = 100;
    private static final long POLL_INTERVAL_MS = 1000;
    private static final long COOLDOWN_MS = 5000;
    private final AtomicLong lastRunTs = new AtomicLong(0);
    private final AtomicBoolean threadStarted = new AtomicBoolean(false);

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
        if (!"com.miui.carlink".equals(lpparam.packageName)) {
            return;
        }
        log("✅ Hooked into com.miui.carlink");

        // 启动轮询线程（CarWith进程内）
        XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                        if (threadStarted.compareAndSet(false, true)) {
                            log("✅ Start poll thread inside com.miui.carlink");
                            new Thread(() -> {
                                while (!Thread.currentThread().isInterrupted()) {
                                    File trigger = new File(TRIGGER_FILE);
                                    long now = System.currentTimeMillis();
                                    if (trigger.exists() && (now - lastRunTs.get()) > COOLDOWN_MS) {
                                        log("🧪 Trigger file detected, run restoreHfp");
                                        restoreHfp();
                                        lastRunTs.set(now);
                                        // 自动清理触发器
                                        try {
                                            trigger.delete();
                                            log("✅ Trigger file deleted");
                                        } catch (Exception e) {
                                            logErr("delete trigger fail", e);
                                        }
                                    }
                                    try {
                                        Thread.sleep(POLL_INTERVAL_MS);
                                    } catch (InterruptedException ie) {
                                        break;
                                    }
                                }
                                log("ℹ️ Poll thread exit");
                            }, "WatchHfpPoll").start();
                        }
                    }
                });

        // 真实场景：捕获ICCOA断开广播
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader,
                "sendBroadcast",
                "android.content.Intent",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                        Object intentObj = param.args[0];
                        String action = (String) XposedHelpers.callMethod(intentObj, "getAction");
                        if ("com.iccoa.carlink.DISCONNECT".equals(action)) {
                            log("📢 Caught ICCOA DISCONNECT, run restoreHfp");
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
