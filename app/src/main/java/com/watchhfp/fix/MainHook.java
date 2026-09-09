package com.watchhfp.fix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CarWithHfpFix";
    private static final String WATCH_MAC = "04:24:05:2b:3d:1a";
    private static final String CARWITH_DISCONNECT_ACTION = "com.iccoa.carlink.DISCONNECT";
    private static final int PROFILE_HEADSET = 1;
    private static final int POLICY_ALLOW = 100;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.bluetooth".equals(lpparam.packageName)) {
            return;
        }
        Log.i(TAG,"✅ Module loaded into com.android.bluetooth");

        XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate", new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context btContext = (Context) param.thisObject;
                        IntentFilter filter = new IntentFilter(CARWITH_DISCONNECT_ACTION);
                        btContext.registerReceiver(new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                Log.i(TAG,"📢 Receive CarWith disconnect broadcast, restore watch HFP");
                                restoreWatchHfpPolicy(btContext.getClassLoader());
                            }
                        }, filter, Context.RECEIVER_EXPORTED);
                        Log.i(TAG,"✅ Broadcast receiver registered, action="+CARWITH_DISCONNECT_ACTION);
                    }
                });
    }

    private void restoreWatchHfpPolicy(ClassLoader classLoader) {
        try {
            Class<?> dbManagerCls = XposedHelpers.findClass("com.android.bluetooth.BluetoothDatabaseManager", classLoader);
            Object dbInstance = XposedHelpers.callStaticMethod(dbManagerCls, "getInstance");
            Method setPolicyMethod = dbManagerCls.getDeclaredMethod(
                    "setProfileConnectionPolicy",
                    String.class,
                    int.class,
                    int.class
            );
            setPolicyMethod.invoke(dbInstance, WATCH_MAC, PROFILE_HEADSET, POLICY_ALLOW);
            Log.i(TAG,"✅ SUCCESS setProfileConnectionPolicy mac="+WATCH_MAC+" policy="+POLICY_ALLOW);
        } catch (Throwable e) {
            Log.e(TAG,"❌ FAILED invoke setProfileConnectionPolicy", e);
        }
    }
}
