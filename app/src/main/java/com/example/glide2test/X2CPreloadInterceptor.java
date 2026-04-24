package com.example.glide2test;

import android.content.Context;

import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.annotation.Interceptor;
import com.alibaba.android.arouter.facade.callback.InterceptorCallback;
import com.alibaba.android.arouter.facade.template.IInterceptor;
import com.example.featuredemo.FeatureRoutes;

import io.github.linyilei.x2c.runtime.X2C;

@Interceptor(priority = 1, name = "X2C preload")
public final class X2CPreloadInterceptor implements IInterceptor {

    private Context applicationContext;

    @Override
    public void init(Context context) {
        applicationContext = context.getApplicationContext();
    }

    @Override
    public void process(Postcard postcard, InterceptorCallback callback) {
        if (FeatureRoutes.FEATURE_DEMO.equals(postcard.getPath())) {
            Context preloadContext = X2C.withTheme(applicationContext, R.style.Theme_X2cDemo);
            X2C.preload(preloadContext, com.example.featuredemo.R.layout.activity_feature_demo);
        }
        callback.onContinue(postcard);
    }
}
