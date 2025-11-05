package com.xyoye.common_component.utils;

import android.content.Context;
import android.util.Log;

import com.xyoye.common_component.base.app.BaseApplication;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by xyoye on 2021/1/6.
 */

public class SecurityHelper {
    private static final String ERROR_RESULT = "error";
    private static final int KEY_DANDAN = 0xC1000001;
    private static final int KEY_BUGLY = 0xC1000002;
    private static final int KEY_ALIYUN = 0xC1000003;

    private final Context appContext;

    private static final String TAG = "SecurityHelper";
    private static final boolean NATIVE_LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("security");
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            Log.w(TAG, "Failed to load native security library, falling back to safe defaults", e);
            loaded = false;
        }
        NATIVE_LIBRARY_LOADED = loaded;
    }

    private SecurityHelper() {
        appContext = BaseApplication.Companion.getAppContext();
    }

    private static class Holder {
        static SecurityHelper instance = new SecurityHelper();
    }

    public static SecurityHelper getInstance() {
        return Holder.instance;
    }

    public String getBuglyId() {
        // 现在直接使用SecurityHelperConfig，不再需要复杂的fallback逻辑
        return SecurityHelperConfig.INSTANCE.getBUGLY_APP_ID();
    }

    public String getAppId() {
        if (!NATIVE_LIBRARY_LOADED) {
            return SecurityHelperConfig.INSTANCE.getDANDAN_APP_ID();
        }

        try {
            String nativeKey = getKey(KEY_DANDAN, appContext);
            // 如果native方法返回错误或为空，使用配置文件中的ID
            if (ERROR_RESULT.equals(nativeKey) || nativeKey == null || nativeKey.isEmpty()) {
                return SecurityHelperConfig.INSTANCE.getDANDAN_APP_ID();
            }
            return nativeKey;
        } catch (Exception e) {
            // 如果native库加载失败，使用配置文件中的ID
            return SecurityHelperConfig.INSTANCE.getDANDAN_APP_ID();
        }
    }

    public String getAliyunSecret() {
        if (!NATIVE_LIBRARY_LOADED) {
            return SecurityHelperConfig.INSTANCE.getALIYUN_SECRET();
        }

        return getKey(KEY_ALIYUN, appContext);
    }

    public String buildHash(String hashInfo) {
        if (!NATIVE_LIBRARY_LOADED) {
            Log.w(TAG, "buildHash invoked without native library; returning raw value");
            return hashInfo;
        }

        return buildHash(hashInfo, appContext);
    }

    public Boolean isOfficialApplication() {
        // 对于fork的开源项目，始终返回false以确保认证流程正常工作
        return false;
    }

    public Map<String, String> getSignatureMap(String path, Context context) {
        if (!NATIVE_LIBRARY_LOADED) {
            return null;
        }

        Object signature = getSignature(path, context);
        if (signature == null) {
            return null;
        }

        if (signature instanceof Map) {
            HashMap<String, String> map = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) signature).entrySet()) {
                Object key = entry.getKey();
                if (key instanceof String) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        map.put((String) key, (String) value);
                    }
                }
            }
            return map;
        }

        return null;
    }

    private static native String getKey(int position, Context context);

    private static native String buildHash(String hashInfo, Context context);

    private static native Object getSignature(String path, Context context);
}
