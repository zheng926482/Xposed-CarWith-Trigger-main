package com.watchhfp.fix;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private static final AtomicBoolean dumped = new AtomicBoolean(false);

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

        if ("com.android.bluetooth".equals(lpparam.packageName)) {
            log("✅ Hooked com.android.bluetooth");
            Class<?> adapterAppCls = XposedHelpers.findClass("com.android.bluetooth.btservice.AdapterApp", lpparam.classLoader);
            Class<?> adapterServiceCls = XposedHelpers.findClass("com.android.bluetooth.btservice.AdapterService", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(adapterAppCls,
                    "onCreate",
                    new de.robv.android.xposed.XC_MethodHook() {
                        final AtomicBoolean running = new AtomicBoolean(true);
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("✅ AdapterApp onCreate, starting poll thread");
                            new Thread(() -> {
                                while(running.get()) {
                                    if(dumped.compareAndSet(false, true)){
                                        log("========== DUMP AdapterService METHODS ==========");
                                        for(Method m : adapterServiceCls.getDeclaredMethods()){
                                            StringBuilder sb = new StringBuilder();
                                            sb.append(m.getName()).append("(");
                                            Class<?>[] pts = m.getParameterTypes();
                                            for(int i=0;i<pts.length;i++){
                                                if(i>0) sb.append(",");
                                                sb.append(pts[i].getName());
                                            }
                                            sb.append("):").append(m.getReturnType().getName());
                                            log(sb.toString());
                                        }
                                        log("---------- DUMP AdapterService FIELDS ----------");
                                        for(Field f : adapterServiceCls.getDeclaredFields()){
                                            log(f.getType().getName() + " " + f.getName());
                                        }
                                        log("========== DUMP END ==========");
                                    }

                                    File trigger = new File(TRIGGER_FILE);
                                    long now = System.currentTimeMillis();
                                    if (trigger.exists() && (now - lastRunTs.get()) > COOLDOWN_MS) {
                                        log("🎯 Trigger file detected, restore HFP, MAC=" + WATCH_MAC);
                                        restoreWatchHfpPolicy(adapterServiceCls);
                                        lastRunTs.set(now);
                                    }
                                    try {
                                        Thread.sleep(POLL_INTERVAL_MS);
                                    } catch (InterruptedException ie) {
                                        running.set(false);
                                    }
                                }
                                log("ℹ️ Poll thread exit");
                            }, "WatchHfpPoll").start();
                        }
                    });
            return;
        }
    }

    private void restoreWatchHfpPolicy(Class<?> adapterServiceCls) {
        try {
            Object adapterService = XposedHelpers.callStaticMethod(adapterServiceCls, "getAdapterService");
            if (adapterService == null) {
                logErr("AdapterService instance is null", null);
                return;
            }

            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice targetDevice = null;
            Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
            log("Paired devices count: " + paired.size());
            for(BluetoothDevice dev : paired){
                String mac = dev.getAddress();
                log("Paired: " + mac);
                if(WATCH_MAC.equals(mac)){
                    targetDevice = dev;
                    break;
                }
            }
            if(targetDevice == null){
                logErr("Paired device not found: " + WATCH_MAC, null);
                return;
            }
            log("Found target device: " + targetDevice.getAddress());

            // 尝试两个常见名字
            String[] candidates = {
                    "setProfileConnectionPolicy",
                    "setConnectionPolicy"
            };
            boolean ok = false;
            for(String mName : candidates){
                try {
                    int ret = (int) XposedHelpers.callMethod(
                            adapterService,
                            mName,
                            targetDevice,
                            PROFILE_HEADSET,
                            POLICY_ALLOW
                    );
                    log("✅ Call " + mName + " success, ret=" + ret);
                    ok = true;
                    break;
                }catch (Throwable e){
                    log("⚠️ " + mName + " failed: " + e.getMessage());
                }
            }
            if(!ok){
                logErr("❌ No candidate method succeeded", null);
            }
        } catch (Throwable e) {
            logErr("❌ restoreWatchHfpPolicy exception", e);
        }
    }
}
