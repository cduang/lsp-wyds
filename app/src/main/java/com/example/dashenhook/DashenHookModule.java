package com.example.dashenhook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONObject;

public class DashenHookModule implements IXposedHookLoadPackage {
    private static final String TAG = "DashenPointsHook";
    private static final String TARGET_PACKAGE = "com.netease.gl";
    private static final String HOOK_DIR = "/sdcard/DashenHook/";
    private static final String VAULT_FILE = HOOK_DIR + "dashen_vault.json";
    private static final String LOG_FILE = HOOK_DIR + "dashen_hook.log";

    private static boolean initialized = false;
    private static boolean firstWrite = true;

    /**
     * 初始化时立即创建目录，验证模块是否真的被加载
     */
    private static synchronized void ensureDir() {
        if (firstWrite) {
            firstWrite = false;
            try {
                File dir = new File(HOOK_DIR);
                if (!dir.exists()) {
                    boolean created = dir.mkdirs();
                    // 立即写一条日志验证
                    FileWriter fw = new FileWriter(LOG_FILE, false);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                    fw.write("[" + sdf.format(new Date()) + "] [BOOT] 模块已加载! dir.mkdirs()=" + created + "\n");
                    fw.write("[" + sdf.format(new Date()) + "] [BOOT] ClassLoader=" + DashenHookModule.class.getClassLoader() + "\n");
                    fw.close();
                }
            } catch (Throwable t) {
                // 能写 XposedBridge 日志也行
                XposedBridge.log(TAG + " [BOOT] 初始化失败: " + t.getMessage());
            }
        }
    }

    private void writeLog(String message) {
        try {
            ensureDir();
            FileWriter fw = new FileWriter(LOG_FILE, true);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
            fw.write("[" + sdf.format(new Date()) + "] " + message + "\n");
            fw.close();
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + " " + message);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 每次 handleLoadPackage 被调用都写日志
        writeLog("========================================");
        writeLog("handleLoadPackage() 被调用! package=" + lpparam.packageName);

        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            writeLog("跳过非目标应用: " + lpparam.packageName);
            return;
        }

        writeLog("成功注入网易大神！正在监听网络层...");

        // 尝试多个 OkHttp 版本中 RealCall 的可能类名
        String[] realCallCandidates = {
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall"
        };

        boolean hooked = false;
        for (String clazz : realCallCandidates) {
            try {
                hookRealCall(clazz, lpparam.classLoader);
                writeLog(">>> 成功 Hook: " + clazz);
                hooked = true;
                break;
            } catch (Throwable t) {
                writeLog(">>> " + clazz + " 不存在: " + t.getMessage());
            }
        }

        // 额外 Hook OkHttpClient.newCall() 以捕获所有 RealCall 的创建
        try {
            XposedHelpers.findAndHookMethod(
                "okhttp3.OkHttpClient",
                lpparam.classLoader,
                "newCall",
                "okhttp3.Request",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object realCall = param.getResult();
                        if (realCall != null) {
                            writeLog("newCall() 被调用 -> 实际类型: " + realCall.getClass().getName());
                        }
                    }
                }
            );
            writeLog(">>> 成功 Hook: okhttp3.OkHttpClient.newCall()");
        } catch (Throwable t) {
            writeLog(">>> OkHttpClient.newCall() Hook 失败: " + t.getMessage());
        }

        if (!hooked) {
            writeLog(">>> 警告: 未找到任何 RealCall 类！请检查 OkHttp 版本");
        }

        initialized = true;
        writeLog("========================================");
    }

    private void hookRealCall(String className, ClassLoader classLoader) throws Throwable {
        // Hook 同步请求 execute()
        XposedHelpers.findAndHookMethod(
            className, classLoader, "execute",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object response = param.getResult();
                    handleOkHttpResponse(response);
                }
            }
        );

        // Hook 异步请求 enqueue()
        Class<?> callbackClass = XposedHelpers.findClass("okhttp3.Callback", classLoader);
        XposedHelpers.findAndHookMethod(
            className, classLoader, "enqueue", callbackClass,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    final Object originalCallback = param.args[0];
                    if (originalCallback == null) return;

                    param.args[0] = Proxy.newProxyInstance(
                            classLoader,
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
    }

    private void handleOkHttpResponse(Object response) {
        if (response == null) return;
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            Object urlObj = XposedHelpers.callMethod(request, "url");
            String url = urlObj.toString();

            if (url.contains("/v1/act/common/currency/getCurrencyInfo")) {
                writeLog(">>> 命中目标 API: " + url);

                String glUid = (String) XposedHelpers.callMethod(request, "header", "GL-Uid");
                String glToken = (String) XposedHelpers.callMethod(request, "header", "GL-Token");
                String glDevice = (String) XposedHelpers.callMethod(request, "header", "GL-DeviceId");
                String glNonce = (String) XposedHelpers.callMethod(request, "header", "GL-Nonce");

                writeLog(">>> Headers: GL-Uid=" + glUid
                        + " GL-Token=" + glToken
                        + " GL-DeviceId=" + glDevice
                        + " GL-Nonce=" + glNonce);

                JSONObject vault = new JSONObject();
                vault.put("url", url);
                vault.put("gl_uid", glUid != null ? glUid : "");
                vault.put("gl_token", glToken != null ? glToken : "");
                vault.put("gl_device_id", glDevice != null ? glDevice : "");
                vault.put("gl_nonce", glNonce != null ? glNonce : "");
                vault.put("timestamp", System.currentTimeMillis());

                File dir = new File(HOOK_DIR);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File targetFile = new File(VAULT_FILE);
                FileWriter writer = new FileWriter(targetFile);
                writer.write(vault.toString(2));
                writer.close();

                writeLog(">>> ✅ 凭证已写入: " + targetFile.getAbsolutePath());
            }
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            writeLog(">>> ❌ 处理响应异常: " + t.getMessage());
            writeLog(">>> StackTrace: " + sw.toString());
        }
    }
}
