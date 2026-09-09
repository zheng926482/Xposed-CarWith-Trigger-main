package com.watchhfp.fix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CarWithHfpFix";
    private static final String WATCH_MAC = "04:24:05:2b:3d:1a";
    private static final String TRIGGER_ACTION = "com.watchhfp.fix.TRIGGER_RESTORE";
    private static final int PROFILE_HEADSET = 1;
    private static final int POLICY_ALLOW = 100;

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
        // ========== 1. 注入 com.miui.carlink（CarWith） ==========
        if ("com.miui.carlink".equals(lpparam.packageName)) {
            log("✅ Loaded into com.miui.carlink");
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader,
                    "sendBroadcast",
                    "android.content.Intent",
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Intent intent = (Intent) param.args[0];
                            String action = intent.getAction();
                            if ("com.iccoa.carlink.DISCONNECT".equals(action)) {
                                log("📢 Caught CarWith DISCONNECT, send trigger via root");
                                new Thread(() -> {
                                    try {
                                        // root发送带蓝牙管理员权限的广播，绕过广播白名单
                                        Runtime.getRuntime().exec(new String[]{
                                                "su", "-c",
                                                "am broadcast -a " + TRIGGER_ACTION +
                                                        " --receiver-permission android.permission.BLUETOOTH_ADMIN"
                                        }).waitFor();
                                        log("✅ Trigger broadcast sent (root)");
                                    } catch (Exception e) {
                                        logErr("❌ Failed run su broadcast", e);
                                    }
                                }).start();
                            }
                        }
                    });
            return;
        }

        // ========== 2. 注入 com.android.bluetooth（蓝牙进程） ==========
        if ("com.android.bluetooth".equals(lpparam.packageName)) {
            log("✅ Loaded into com.android.bluetooth");
            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                    "onCreate", new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context btContext = (Context) param.thisObject;
                            IntentFilter filter = new IntentFilter(TRIGGER_ACTION);
                            // 注册接收器，要求发送方拥有 BLUETOOTH_ADMIN 权限
                            btContext.registerReceiver(new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent intent) {
                                    log("🎯 Received trigger action, restoring watch HFP");
                                    restoreWatchHfpPolicy(btContext.getClassLoader());
                                }
                            }, filter, "android.permission.BLUETOOTH_ADMIN", null, Context.RECEIVER_EXPORTED);
                            log("✅ Trigger receiver registered, action=" + TRIGGER_ACTION);
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
            log("✅ SUCCESS setProfileConnectionPolicy, mac=" + WATCH_MAC + ", policy=" + POLICY_ALLOW);
        } catch (Throwable e) {
            logErr("❌ setProfileConnectionPolicy failed", e);
        }
    }
}
