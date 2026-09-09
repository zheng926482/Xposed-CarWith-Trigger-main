package com.watchhfp.fix;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

        // ========== Hook com.miui.carlink 捕获CarWith断开 ==========
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

        // ========== Hook com.android.bluetooth：直接Hook AdapterService构造函数 ==========
        if ("com.android.bluetooth".equals(lpparam.packageName)) {
            log("✅ Enter com.android.bluetooth");
            Class<?> adapterServiceCls = XposedHelpers.findClass("com.android.bluetooth.btservice.AdapterService", lpparam.classLoader);

            // 🔴 重点：Hook AdapterService构造，蓝牙服务实例化一定会进这里
            XposedHelpers.findAndHookConstructor(adapterServiceCls, Context.class,
                    new de.robv.android.xposed.XC_MethodHook() {
                        final AtomicBoolean running = new AtomicBoolean(true);
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("✅ AdapterService constructor called! Bluetooth service started");

                            // 在这里做dump，此时类已经完全加载，一定可以拿到全部方法
                            if(dumped.compareAndSet(false, true)){
                                log("---------- DUMP AdapterService METHODS ----------");
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
                                log("---------- DUMP END ----------");
                            }

                            // 启动文件轮询线程
                            log("✅ Starting poll thread");
                            new Thread(() -> {
                                while(running.get()) {
                                    File trigger = new File(TRIGGER_FILE);
                                    long now = System.currentTimeMillis();
                                    if (trigger.exists() && (now - lastRunTs.get()) > COOLDOWN_MS) {
                                        log("🎯 Trigger file detected, restore HFP");
                                        restoreWatchHfpPolicy(lpparam.classLoader, adapterServiceCls);
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

    private void restoreWatchHfpPolicy(ClassLoader cl, Class<?> adapterServiceCls) {
        try {
            Object adapterService = XposedHelpers.callStaticMethod(adapterServiceCls, "getAdapterService");
            if (adapterService == null) {
                logErr("AdapterService instance is null", null);
                return;
            }

            Object device = null;
            Object btAdapter = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.bluetooth.BluetoothAdapter", cl),
                    "getDefaultAdapter");
            Set<?> bondedDevices = (Set<?>) XposedHelpers.callMethod(btAdapter, "getBondedDevices");
            for(Object dev : bondedDevices){
                String mac = (String) XposedHelpers.callMethod(dev,"getAddress");
                if(WATCH_MAC.equals(mac)){
                    device = dev;
                    break;
                }
            }
            if(device == null){
                logErr("Paired watch device NOT found: "+WATCH_MAC,null);
                return;
            }
            log("✅ Found watch device: "+WATCH_MAC);

            // 尝试候选方法
            String[] candidates = {
                    "setProfileConnectionPolicy",
                    "setConnectionPolicy"
            };
            boolean ok = false;
            for(String mName : candidates){
                try {
                    int ret = (int) XposedHelpers.callMethod(adapterService,
                            mName,
                            device,
                            PROFILE_HEADSET,
                            POLICY_ALLOW
                    );
                    log("✅ Call "+mName+" success, ret="+ret);
                    ok = true;
                    break;
                }catch (Throwable e){
                    log("⚠️ "+mName+" failed: "+e.getMessage());
                }
            }
            if(!ok){
                logErr("❌ No candidate method succeeded",null);
            }
        } catch (Throwable e) {
            logErr("❌ restoreWatchHfpPolicy exception", e);
        }
    }
}
