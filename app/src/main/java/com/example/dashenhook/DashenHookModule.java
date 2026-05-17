package com.example.dashenhook;

import android.os.Environment;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.json.JSONObject;

public class DashenHookModule implements IXposedHookLoadPackage {
    private static final String TAG = "DashenPointsHook";
    private static final String TARGET_PACKAGE = "com.netease.gl"; 

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log(TAG + " 成功注入网易大神！正在监听网络层...");

        try {
            // Hook 同步请求 execute()
            XposedHelpers.findAndHookMethod(
                "okhttp3.RealCall",
                lpparam.classLoader,
                "execute",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object response = param.getResult();
                        handleOkHttpResponse(response);
                    }
                }
            );

            // Hook 异步请求 enqueue()
            Class<?> callbackClass = XposedHelpers.findClass("okhttp3.Callback", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                "okhttp3.RealCall",
                lpparam.classLoader,
                "enqueue",
                callbackClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final Object originalCallback = param.args[0];
                        if (originalCallback == null) return;

                        param.args[0] = Proxy.newProxyInstance(
                            lpparam.classLoader,
                            new Class<?>[]{callbackClass},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                    if ("onResponse".equals(method.getName())) {
                                        handleOkHttpResponse(args[1]);
                                    }
                                    return method.invoke(originalCallback, args);
                                }
                            }
                        );
                    }
                }
            );

        } catch (Throwable t) {
            XposedBridge.log(TAG + " 初始化 OkHttp Hook 失败: " + t.getMessage());
        }
    }

    private void handleOkHttpResponse(Object response) {
        if (response == null) return;
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            Object urlObj = XposedHelpers.callMethod(request, "url");
            String url = urlObj.toString();

            if (url.contains("/v1/act/common/currency/getCurrencyInfo")) {
                XposedBridge.log(TAG + " 成功捕获积分请求 URL!");

                // 提取 Headers 明文
                String glUid = (String) XposedHelpers.callMethod(request, "header", "GL-Uid");
                String glToken = (String) XposedHelpers.callMethod(request, "header", "GL-Token");
                String glDevice = (String) XposedHelpers.callMethod(request, "header", "GL-DeviceId");
                String glNonce = (String) XposedHelpers.callMethod(request, "header", "GL-Nonce");

                // 创建 JSON 数据结构
                JSONObject vault = new JSONObject();
                vault.put("url", url);
                vault.put("gl_uid", glUid);
                vault.put("gl_token", glToken);
                vault.put("gl_device_id", glDevice);
                vault.put("gl_nonce", glNonce);

                // 写入沙盒外公共目录，不需动态权限申请
                File targetDir = new File("/sdcard/Android/data/com.netease.gl/files");
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                File targetFile = new File(targetDir, "dashen_vault.json");
                
                FileWriter writer = new FileWriter(targetFile);
                writer.write(vault.toString());
                writer.close();

                XposedBridge.log(TAG + " 💾 最新凭证已安全写盘 -> " + targetFile.getAbsolutePath());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 提取网络参数异常: " + t.getMessage());
        }
    }
}