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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONObject;

public class DashenHookModule implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String TAG = "DashenPointsHook";
    private static final String TARGET_PACKAGE = "com.netease.gl";
    private static final String HOOK_DIR = "/sdcard/DashenHook";
    private static final String VAULT_FILE = HOOK_DIR + "/dashen_vault.json";
    private static final String LOG_FILE = HOOK_DIR + "/dashen_hook.log";

    private static boolean sHasRoot = false;
    private static boolean sHookedGlobal = false;

    // ==================== Zygote 初始化 ====================

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        // 初始化 root
        try {
            String test = execRoot("id");
            sHasRoot = test.contains("uid=");
        } catch (Throwable ignored) {}

        if (sHasRoot) {
            execRootSilent("mkdir -p '" + HOOK_DIR + "'");
            execRootSilent("chmod 0777 '" + HOOK_DIR + "'");
        }

        writeLog("[ZYGOTE] initZygote() root=" + sHasRoot + " classLoader=" + getClass().getClassLoader());

        // ====== 全局 Hook: ActivityThread.handleBindApplication() ======
        // 任何应用进程启动时都会调用这个方法
        // 我们在里面判断是不是目标包名，如果是就执行 Hook
        // 这完全绕过 LSPosed 的作用域机制
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            XposedHelpers.findAndHookMethod(
                "android.app.ActivityThread", null,
                "handleBindApplication",
                "android.app.ActivityThread.AppBindData",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object bindData = param.args[0];
                            if (bindData == null) return;

                            // 获取进程的包名
                            Object processName = XposedHelpers.getObjectField(bindData, "processName");
                            Object appInfo = XposedHelpers.getObjectField(bindData, "appInfo");
                            String packageName = (String) XposedHelpers.callMethod(appInfo, "getPackageName");
                            String procName = processName != null ? processName.toString() : packageName;

                            if (TARGET_PACKAGE.equals(packageName)) {
                                writeLog("[GLOBAL] 检测到目标进程启动！package=" + packageName + " process=" + procName);

                                // 获取 ClassLoader 并执行 Hook
                                Object loadedApk = XposedHelpers.getObjectField(bindData, "info");
                                if (loadedApk != null) {
                                    try {
                                        ClassLoader cl = (ClassLoader) XposedHelpers.callMethod(loadedApk, "getClassLoader");
                                        if (cl != null) {
                                            writeLog("[GLOBAL] 获取到 ClassLoader，开始 Hook OkHttp...");
                                            hookOkHttpGlobal(cl);
                                            sHookedGlobal = true;
                                            writeLog("[GLOBAL] ✅ 全局 Hook 成功！");
                                        }
                                    } catch (Throwable t) {
                                        writeLog("[GLOBAL] getClassLoader 失败: " + t.getMessage());
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            writeLog("[GLOBAL] handleBindApplication 异常: " + t.getMessage());
                        }
                    }
                }
            );
            writeLog("[ZYGOTE] ✅ ActivityThread.handleBindApplication 全局 Hook 已注册");
        } catch (Throwable t) {
            writeLog("[ZYGOTE] ⚠ handleBindApplication Hook 失败: " + t.getMessage());
        }

        // ====== 备选方案：Hook ActivityThread 的 handleLaunchActivity ======
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ActivityThread", null,
                "handleLaunchActivity",
                "android.app.ActivityThread.ActivityClientRecord",
                "android.content.Intent",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (sHookedGlobal) return;
                        try {
                            Object r = param.args[0];
                            if (r == null) return;
                            Object intent = XposedHelpers.getObjectField(r, "intent");
                            if (intent == null) return;
                            String pkg = (String) XposedHelpers.callMethod(intent, "getComponent");
                            if (pkg != null && pkg.contains(TARGET_PACKAGE)) {
                                writeLog("[LAUNCH] 检测到目标 Activity，尝试获取 ClassLoader");
                                // 获取当前线程的 ClassLoader
                                Object currentActivityThread = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("android.app.ActivityThread", null),
                                    "currentActivityThread");
                                if (currentActivityThread != null) {
                                    Object context = XposedHelpers.callMethod(currentActivityThread, "getSystemContext");
                                    if (context != null) {
                                        ClassLoader cl = (ClassLoader) XposedHelpers.callMethod(context, "getClassLoader");
                                        if (cl != null) {
                                            hookOkHttpGlobal(cl);
                                            sHookedGlobal = true;
                                            writeLog("[LAUNCH] ✅ Hook 成功！");
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            );
        } catch (Throwable ignored) {}
    }

    // ==================== LoadPackage (LSPosed 标准方式) ====================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        writeLog("--------------------------------------------------");
        writeLog("handleLoadPackage() package=" + lpparam.packageName + " process=" + lpparam.processName);

        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            writeLog("跳过: " + lpparam.packageName);
            return;
        }

        writeLog(">>> ✅ LSPosed 注入成功: " + lpparam.packageName);

        // 如果全局 Hook 已经完成，这里就不重复了
        hookOkHttpGlobal(lpparam.classLoader);
        writeLog(">>> ✅ LSPosed Hook 完成");
        writeLog("--------------------------------------------------");
    }

    // ==================== OkHttp Hook (全局，同时处理 Request 和 Response) ====================

    public static void hookOkHttpGlobal(ClassLoader cl) {
        try {
            // ====== Hook Request.body() ======
            // 捕获 POST 请求的 body 数据
            try {
                XposedHelpers.findAndHookMethod(
                    "okhttp3.Request", cl, "body",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            // 检查 URL
                            Object request = param.thisObject;
                            Object urlObj = XposedHelpers.callMethod(request, "url");
                            if (urlObj == null) return;
                            String url = urlObj.toString();

                            if (url.contains("/v1/act/common/currency/getCurrencyInfo")) {
                                Object body = param.getResult();
                                if (body != null) {
                                    writeLog("[BODY] 目标 API 请求 body() 被调用: " + url);
                                    // 尝试读取 body
                                    try {
                                        Object buffer = XposedHelpers.newInstance(
                                            XposedHelpers.findClass("okio.Buffer", cl));
                                        XposedHelpers.callMethod(body, "writeTo", buffer);
                                        String bodyStr = (String) XposedHelpers.callMethod(buffer, "readUtf8");
                                        writeLog("[BODY] POST Body:\n" + bodyStr);
                                    } catch (Throwable t) {
                                        writeLog("[BODY] 读取 body 失败: " + t.getMessage());
                                    }
                                } else {
                                    writeLog("[BODY] body()=null URL=" + url);
                                }
                            }
                        }
                    }
                );
                writeLog(">>> 成功 Hook: Request.body()");
            } catch (Throwable t) {
                writeLog(">>> Request.body() 失败: " + t.getMessage());
            }

            // ====== Hook Request.Builder.build() ======
            // 在请求构建时捕获所有信息
            try {
                XposedHelpers.findAndHookMethod(
                    "okhttp3.Request$Builder", cl, "build",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object request = param.getResult();

                            // 提取 URL
                            Object urlObj = XposedHelpers.callMethod(request, "url");
                            String url = urlObj != null ? urlObj.toString() : "";

                            if (url.contains("/v1/act/common/currency/getCurrencyInfo")) {
                                writeLog("[BUILD] 捕获到目标请求: " + url);

                                // 提取 Headers
                                String glUid = (String) XposedHelpers.callMethod(request, "header", "GL-Uid");
                                String glToken = (String) XposedHelpers.callMethod(request, "header", "GL-Token");
                                String glDevice = (String) XposedHelpers.callMethod(request, "header", "GL-DeviceId");
                                String glNonce = (String) XposedHelpers.callMethod(request, "header", "GL-Nonce");

                                writeLog("[BUILD] Headers: Uid=" + glUid +
                                    " Token=" + (glToken != null ? glToken.substring(0, Math.min(10, glToken.length())) + "..." : "null"));

                                // 获取请求方法
                                String method = (String) XposedHelpers.callMethod(request, "method");
                                writeLog("[BUILD] Method: " + method);

                                // 读取 Body
                                String bodyStr = "null";
                                try {
                                    Object body = XposedHelpers.callMethod(request, "body");
                                    if (body != null) {
                                        Object buffer = XposedHelpers.newInstance(
                                            XposedHelpers.findClass("okio.Buffer", cl));
                                        XposedHelpers.callMethod(body, "writeTo", buffer);
                                        bodyStr = (String) XposedHelpers.callMethod(buffer, "readUtf8");
                                    }
                                } catch (Throwable t) {
                                    bodyStr = "[read error: " + t.getMessage() + "]";
                                }
                                writeLog("[BUILD] Body: " + bodyStr);

                                // 写入 JSON
                                JSONObject vault = new JSONObject();
                                vault.put("url", url);
                                vault.put("method", method);
                                vault.put("gl_uid", glUid != null ? glUid : "");
                                vault.put("gl_token", glToken != null ? glToken : "");
                                vault.put("gl_device_id", glDevice != null ? glDevice : "");
                                vault.put("gl_nonce", glNonce != null ? glNonce : "");
                                vault.put("body", bodyStr);
                                vault.put("timestamp", System.currentTimeMillis());

                                if (sHasRoot) {
                                    rootWriteFile(VAULT_FILE, vault.toString(2));
                                } else {
                                    try {
                                        File dir = new File(HOOK_DIR);
                                        if (!dir.exists()) dir.mkdirs();
                                        FileWriter fw = new FileWriter(VAULT_FILE);
                                        fw.write(vault.toString(2));
                                        fw.close();
                                    } catch (Throwable ignored) {}
                                }
                                writeLog("[BUILD] ✅ 凭证已写入: " + VAULT_FILE);
                            }
                        }
                    }
                );
                writeLog(">>> 成功 Hook: Request.Builder.build()");
            } catch (Throwable t) {
                writeLog(">>> Request.Builder.build() 失败: " + t.getMessage());
            }

            // ====== Hook RealCall.execute() ======
            // 捕获同步请求的 Response（保留原有逻辑）
            try {
                hookRealCall("okhttp3.RealCall", cl);
                writeLog(">>> 成功 Hook: okhttp3.RealCall");
            } catch (Throwable t) {
                writeLog(">>> okhttp3.RealCall 失败: " + t.getMessage());
            }

            try {
                hookRealCall("okhttp3.internal.connection.RealCall", cl);
                writeLog(">>> 成功 Hook: okhttp3.internal.connection.RealCall");
            } catch (Throwable t) {
                writeLog(">>> okhttp3.internal.connection.RealCall 失败: " + t.getMessage());
            }

            // ====== Hook OkHttpClient.newCall() ======
            try {
                XposedHelpers.findAndHookMethod("okhttp3.OkHttpClient", cl, "newCall",
                    "okhttp3.Request",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object call = param.getResult();
                            if (call != null) {
                                writeLog("[newCall()] 新请求 -> " + call.getClass().getName());
                            }
                        }
                    }
                );
                writeLog(">>> 成功 Hook: OkHttpClient.newCall()");
            } catch (Throwable t) {
                writeLog(">>> OkHttpClient.newCall() 失败: " + t.getMessage());
            }

        } catch (Throwable t) {
            writeLog("[hookOkHttpGlobal] 整体异常: " + t.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            writeLog("[hookOkHttpGlobal] StackTrace: " + sw.toString());
        }
    }

    private static void hookRealCall(String className, ClassLoader cl) throws Throwable {
        // Hook execute()
        XposedHelpers.findAndHookMethod(className, cl, "execute",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    handleResponse(param.getResult());
                }
            }
        );

        // Hook enqueue()
        try {
            Class<?> cbClass = XposedHelpers.findClass("okhttp3.Callback", cl);
            XposedHelpers.findAndHookMethod(className, cl, "enqueue", cbClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object originalCallback = param.args[0];
                        if (originalCallback == null) return;
                        param.args[0] = Proxy.newProxyInstance(cl, new Class<?>[]{cbClass},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                    if ("onResponse".equals(method.getName())) handleResponse(args[1]);
                                    return method.invoke(originalCallback, args);
                                }
                            }
                        );
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " enqueue hook 失败: " + t.getMessage());
        }
    }

    // ==================== 响应处理（保留原有逻辑） ====================

    private static void handleResponse(Object response) {
        if (response == null) return;
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            Object urlObj = XposedHelpers.callMethod(request, "url");
            if (urlObj == null) return;
            String url = urlObj.toString();

            if (url.contains("/v1/act/common/currency/getCurrencyInfo")) {
                writeLog("[RESPONSE] ✅ 目标 API 返回: " + url);

                // 尝试读取 Response body
                try {
                    Object body = XposedHelpers.callMethod(response, "body");
                    if (body != null) {
                        String bodyStr = (String) XposedHelpers.callMethod(body, "string");
                        writeLog("[RESPONSE] Body: " + (bodyStr.length() > 500 ? bodyStr.substring(0, 500) + "..." : bodyStr));
                    }
                } catch (Throwable t) {
                    writeLog("[RESPONSE] 读取 body 失败: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            writeLog("[RESPONSE] 异常: " + t.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private static String execRoot(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(process.getOutputStream());
            osw.write(command + "\n");
            osw.write("exit\n");
            osw.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append("\n");
            process.waitFor();
            return out.toString().trim();
        } catch (Exception e) {
            return "[EXCEPTION] " + e.getMessage();
        }
    }

    private static void execRootSilent(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(process.getOutputStream());
            osw.write(command + "\n");
            osw.write("exit\n");
            osw.flush();
            process.waitFor();
        } catch (Exception ignored) {}
    }

    private static void rootWriteFile(String filePath, String content) {
        String dir = filePath.substring(0, filePath.lastIndexOf('/'));
        execRootSilent("mkdir -p '" + dir + "'");
        execRootSilent("chmod 0777 '" + dir + "'");
        String escaped = content.replace("'", "'\\''");
        execRoot("echo '" + escaped + "' > '" + filePath + "'");
        execRootSilent("chmod 0666 '" + filePath + "'");
    }

    private static void rootAppendLog(String filePath, String line) {
        String dir = filePath.substring(0, filePath.lastIndexOf('/'));
        execRootSilent("mkdir -p '" + dir + "'");
        execRootSilent("chmod 0777 '" + dir + "'");
        String escaped = line.replace("'", "'\\''");
        execRoot("echo '" + escaped + "' >> '" + filePath + "'");
        execRootSilent("chmod 0666 '" + filePath + "'");
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    private static void writeLog(String msg) {
        String line = "[" + timestamp() + "] " + msg;
        XposedBridge.log(TAG + " " + msg);
        if (sHasRoot) {
            try { rootAppendLog(LOG_FILE, line); } catch (Throwable ignored) {}
        } else {
            try {
                File dir = new File(HOOK_DIR);
                if (!dir.exists()) dir.mkdirs();
                FileWriter fw = new FileWriter(LOG_FILE, true);
                fw.write(line + "\n");
                fw.close();
            } catch (Throwable ignored) {}
        }
    }
}
