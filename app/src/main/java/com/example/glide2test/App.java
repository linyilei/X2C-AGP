package com.example.glide2test;

import android.app.Application;

import io.github.linyilei.x2c.runtime.X2C;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        X2C.setDebugLogging(BuildConfig.DEBUG);
    }
}
