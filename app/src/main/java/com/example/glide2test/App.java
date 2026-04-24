package com.example.glide2test;

import android.app.Application;

import com.alibaba.android.arouter.launcher.ARouter;

import io.github.linyilei.x2c.runtime.X2C;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            ARouter.openLog();
            ARouter.openDebug();
        }
        ARouter.init(this);
        X2C.setDebugLogging(BuildConfig.DEBUG);
    }
}
