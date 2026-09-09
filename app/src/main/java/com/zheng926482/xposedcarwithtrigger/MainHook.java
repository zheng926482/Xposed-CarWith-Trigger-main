package com.zheng926482.xposedcarwithtrigger;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.widget.Toast;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String CARWITH_PACKAGE = "com.miui.carlink";
    private static final String SALTPLAY_PACKAGE = "com.salt.music";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!CARWITH_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        hookStateEnter(lpparam, "com.miui.carlink.castfwk.CarlinkStateMachine$n", "a");
        hookStateEnter(lpparam, "com.miui.carlink.castfwk.CarlinkStateMachine$m", "a");
    }

    private void hookStateEnter(XC_LoadPackage.LoadPackageParam lpparam, String className, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    lpparam.classLoader,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object outer = XposedHelpers.getObjectField(param.thisObject, "this$0");
                            if (outer == null) {
                                return;
                            }
                            Context context = (Context) XposedHelpers.getObjectField(outer, "f12126c");
                            if (context != null) {
                                triggerSaltPlay(context);
                            }
                        }
                    }
            );
            XposedBridge.log("Hooked " + className + "." + methodName + " success");
        } catch (Throwable t) {
            XposedBridge.log("Hook " + className + "." + methodName + " failed: " + t.getMessage());
        }
    }

    private void triggerSaltPlay(Context context) {
        try {
            Intent keyDown = new Intent(Intent.ACTION_MEDIA_BUTTON);
            keyDown.setPackage(SALTPLAY_PACKAGE);
            keyDown.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            context.sendBroadcast(keyDown);

            Intent keyUp = new Intent(Intent.ACTION_MEDIA_BUTTON);
            keyUp.setPackage(SALTPLAY_PACKAGE);
            keyUp.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            context.sendBroadcast(keyUp);

            XposedBridge.log("Sent MEDIA_PLAY to SaltPlayer");
            Toast.makeText(context, "Playing...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            XposedBridge.log("Trigger SaltPlayer failed: " + e.getMessage());
        }
    }
}
