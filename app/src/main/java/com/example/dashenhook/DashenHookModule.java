package com.example.dashenhook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONObject;

public class DashenHookModule implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String TAG = "DashenPointsHook";
    private static final String TARGET_PKG = "com.netease.gl";
    private static final String DIR = "/sdcard/DashenHook";
    private static final String FILE_VAULT = DIR + "/dashen_vault.json";
    private static final String FILE_LOG = DIR + "/dashen_hook.log";

    private static boolean hasRoot = false;

    // ======================== 工具方法 ========================

    private static void su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(p.getOutputStream());
            w.write(cmd + "\nexit\n");
            w.flush();
            p.waitFor();
        } catch (Exception ignored) {}
    }

    private static String suOut(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(p.getOutputStream());
            w.write(cmd + "\nexit\n");
            w.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "[E] " + e.getMessage();
        }
    }

    private static String ts() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    private static void log(String msg) {
        String line = "[" + ts() + "] " + msg;
        XposedBridge.log(TAG + " " + msg);
        if (hasRoot) {
            su("mkdir -p '" + DIR + "'");
            su("chmod 0777 '" + DIR + "'");
            String escaped = line.replace("'", "'\\''");
            su("echo '" + escaped + "' >> '" + FILE_LOG + "'");
        }
    }

    private static void saveJson(String json) {
        if (hasRoot) {
            String escaped = json.replace("'", "'\\''");
            su("echo '" + escaped + "' > '" + FILE_VAULT + "'");
            su("chmod 0666 '" + FILE_VAULT + "'");
        }
    }

    // ======================== Zygote ========================

    @Override
    public void initZygote(StartupParam sp) throws Throwable {
        // 初始化 root
        String id = suOut("id");
        hasRoot = id.contains("uid=");
        if (hasRoot) su("mkdir -p '" + DIR + "'");

        log("[ZYGOTE] root=" + hasRoot);

        // ===== 方案1: Hook Instrumentation.callActivityOnCreate =====
        // 每个 Activity 创建时都会调用，可以拿到 Activity 对象和 ClassLoader
        // 这是 Xposed 模块中最常用的全局拦截方式
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", null,
                "callActivityOnCreate",
                "android.app.Activity",
                "android.os.Bundle",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object activity = param.args[0];
                            if (activity == null) return;
                            String pkgName = activity.getClass().getName();
                            // Activity 类名通常包含包名
                            if (pkgName.startsWith(TARGET_PKG) || pkgName.contains("netease")) {
                                log("[ACT] 目标 Activity 启动: " + pkgName);
                                ClassLoader cl = activity.getClass().getClassLoader();
                                if (cl != null) {
                                    doHook(cl);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            );
            log("[ZYGOTE] ✅ callActivityOnCreate Hook 成功");
        } catch (Throwable t) {
            log("[ZYGOTE] ❌ callActivityOnCreate Hook 失败: " + t.getMessage());
        }

        // ===== 方案2: Hook ActivityThread.performLaunchActivity =====
        // 比 handleBindApplication 更底层更可靠
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ActivityThread", null,
                "performLaunchActivity",
                "android.app.ActivityThread.ActivityClientRecord",
                "android.content.Intent",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object activity = param.getResult();
                            if (activity == null) return;
                            String pkgName = activity.getClass().getName();
                            if (pkgName.startsWith(TARGET_PKG) || pkgName.contains("netease")) {
                                log("[LAUNCH] performLaunchActivity: " + pkgName);
                                ClassLoader cl = activity.getClass().getClassLoader();
                                if (cl != null) doHook(cl);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            );
            log("[ZYGOTE] ✅ performLaunchActivity Hook 成功");
        } catch (Throwable t) {
            log("[ZYGOTE] ❌ performLaunchActivity Hook 失败: " + t.getMessage());
        }
    }

    // ======================== LSPosed 备用 ========================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (!p.packageName.equals(TARGET_PKG)) return;
        log("[LSP] LSPosed 注入成功！");
        doHook(p.classLoader);
    }

    // ======================== 核心 Hook ========================

    private static boolean hooked = false;

    public static synchronized void doHook(ClassLoader cl) {
        if (hooked) return;
        hooked = true;

        log("========================================");
        log("[HOOK] 开始 Hook OkHttp...");

        // 1. Hook Request.Builder.build() — 捕获所有请求
        try {
            XposedHelpers.findAndHookMethod(
                "okhttp3.Request$Builder", cl, "build",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        captureRequest(param.getResult(), cl);
                    }
                }
            );
            log("[HOOK] ✅ Request.Builder.build()");
        } catch (Throwable t) {
            log("[HOOK] ❌ Request.Builder.build(): " + t.getMessage());
        }

        // 2. Hook RealCall.execute() — 同步请求响应
        try {
            XposedHelpers.findAndHookMethod(
                "okhttp3.RealCall", cl, "execute",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        captureResponse(param.getResult(), cl);
                    }
                }
            );
            log("[HOOK] ✅ RealCall.execute()");
        } catch (Throwable t) {
            log("[HOOK] ❌ RealCall.execute(): " + t.getMessage());
        }

        // 3. Hook RealCall.enqueue() — 异步请求响应
        try {
            final Class<?> cbClass = XposedHelpers.findClass("okhttp3.Callback", cl);
            XposedHelpers.findAndHookMethod(
                "okhttp3.RealCall", cl, "enqueue", cbClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final Object orig = param.args[0];
                        if (orig == null) return;
                        param.args[0] = java.lang.reflect.Proxy.newProxyInstance(
                            cl,
                            new Class<?>[]{cbClass},
                            (proxy, method, args) -> {
                                if ("onResponse".equals(method.getName())) {
                                    captureResponse(args[1], cl);
                                }
                                return method.invoke(orig, args);
                            }
                        );
                    }
                }
            );
            log("[HOOK] ✅ RealCall.enqueue()");
        } catch (Throwable t) {
            log("[HOOK] ❌ RealCall.enqueue(): " + t.getMessage());
        }

        log("[HOOK] 🎉 所有 Hook 注册完成");
        log("========================================");
    }

    // ======================== 捕获逻辑 ========================

    private static void captureRequest(Object request, ClassLoader cl) {
        try {
            // 提取 URL
            Object urlObj = XposedHelpers.callMethod(request, "url");
            if (urlObj == null) return;
            String url = urlObj.toString();

            // 匹配目标
            if (!url.contains("/v1/act/common/currency/getCurrencyInfo")) return;

            log("========================================");
            log("[REQ] ✅ 命中目标请求!");
            log("[REQ] URL: " + url);

            JSONObject data = new JSONObject();
            data.put("type", "request");
            data.put("url", url);
            data.put("base_url", url.contains("?") ? url.substring(0, url.indexOf("?")) : url);

            // 方法
            String method = (String) XposedHelpers.callMethod(request, "method");
            data.put("method", method);
            log("[REQ] Method: " + method);

            // URL 参数
            try {
                JSONObject qp = new JSONObject();
                int size = (int) XposedHelpers.callMethod(urlObj, "querySize");
                for (int i = 0; i < size; i++) {
                    String n = (String) XposedHelpers.callMethod(urlObj, "queryParameterName", i);
                    String v = (String) XposedHelpers.callMethod(urlObj, "queryParameterValue", i);
                    qp.put(n, v);
                }
                data.put("query_params", qp);
                log("[REQ] Query: " + qp);
            } catch (Throwable e) {
                data.put("query_params", "parse_error");
            }

            // 请求头
            try {
                JSONObject hs = new JSONObject();
                Object headers = XposedHelpers.callMethod(request, "headers");
                int len = (int) XposedHelpers.callMethod(headers, "size");
                for (int i = 0; i < len; i++) {
                    String n = (String) XposedHelpers.callMethod(headers, "name", i);
                    String v = (String) XposedHelpers.callMethod(headers, "value", i);
                    // 只保留想看的头
                    if (n.startsWith("GL-")) {
                        hs.put(n, v);
                    }
                }
                data.put("headers", hs);
                log("[REQ] Headers: " + hs);
            } catch (Throwable e) {
                data.put("headers", "parse_error");
            }

            // 请求体
            try {
                Object body = XposedHelpers.callMethod(request, "body");
                if (body != null) {
                    Object buf = XposedHelpers.newInstance(
                        XposedHelpers.findClass("okio.Buffer", cl));
                    XposedHelpers.callMethod(body, "writeTo", buf);
                    String bodyStr = (String) XposedHelpers.callMethod(buf, "readUtf8");
                    data.put("body", bodyStr);
                    log("[REQ] Body: " + bodyStr);
                }
            } catch (Throwable e) {
                data.put("body", "read_error: " + e.getMessage());
            }

            data.put("timestamp", System.currentTimeMillis());
            saveJson(data.toString(2));
            log("[REQ] ✅ 已保存");
            log("========================================");
        } catch (Throwable t) {
            log("[REQ] ❌ 异常: " + t.getMessage());
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            log(sw.toString());
        }
    }

    private static void captureResponse(Object response, ClassLoader cl) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            Object urlObj = XposedHelpers.callMethod(request, "url");
            if (urlObj == null) return;
            String url = urlObj.toString();

            if (!url.contains("/v1/act/common/currency/getCurrencyInfo")) return;

            log("[RES] ✅ 收到响应!");

            // 读响应体
            try {
                Object body = XposedHelpers.callMethod(response, "body");
                if (body != null) {
                    String bodyStr = (String) XposedHelpers.callMethod(body, "string");
                    log("[RES] Body(" + bodyStr.length() + "字符): " +
                        (bodyStr.length() > 300 ? bodyStr.substring(0, 300) + "..." : bodyStr));

                    // 保存响应到单独文件
                    JSONObject resp = new JSONObject();
                    resp.put("type", "response");
                    resp.put("url", url);
                    resp.put("body", bodyStr);
                    resp.put("timestamp", System.currentTimeMillis());

                    if (hasRoot) {
                        String escaped = resp.toString(2).replace("'", "'\\''");
                        su("echo '" + escaped + "' > '" + DIR + "/dashen_response.json'");
                    }
                    log("[RES] ✅ 响应已保存");
                }
            } catch (Throwable e) {
                log("[RES] 读 body 失败: " + e.getMessage());
            }
        } catch (Throwable t) {
            log("[RES] ❌ 异常: " + t.getMessage());
        }
    }
}
