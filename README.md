# Xposed-CarWith-Trigger

Xposed 模块骨架：当 CarWith 连接成功后，自动触发 SaltPlayer 播放。

## 结构
- Android application module under app/
- Xposed hook entry in app/src/main/java/com/yourname/xposedcarwith/MainHook.java
- GitHub Actions workflow in .github/workflows/build.yml

## 构建
1. Install Android SDK Platform 34 and build-tools.
2. Set sdk.dir in local.properties to your Android SDK path.
3. Run:
   ```bash
   ./gradlew assembleDebug
   ```

## 说明
- This project is a starting point for an Xposed module.
- The hook logic targets CarWith package com.miui.carlink and sends a media play broadcast to com.salt.music.
