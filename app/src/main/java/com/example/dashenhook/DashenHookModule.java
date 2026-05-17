package com.example.dashenhook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
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
    private static final String HOOK_DIR = "/sdcard/DashenHook";
    private static final String VAULT_FILE = HOOK_DIR + "/dashen_vault.json";
    private static final String LOG_FILE = HOOK_DIR + "/dashen_hook.log";

    private static boolean sHasRoot = false;

    // ==================== Root 工具方法 ====================

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
            while ((line = reader.readLine()) != null) {
                out.append(line).append("\n");
            }
            process.waitFor();
            return out.toString().trim();
        } catch (Exception e) {
            return "[EXCEPTION] " + e.getMessage();
        }
    }

    private static String execRootSilent(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(process.getOutputStream());
            osw.write(command + "\n");
            osw.write("exit\n");
            osw.flush();
            process.waitFor();
            return "";
        } catch (Exception ignored) {
            return "";
        }
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

    // ==================== 日志（带 fallback） ====================

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    private void writeLog(String msg) {
        String line = "[" + timestamp() + "] " + msg;
        // always log to XposedBridge
        XposedBridge.log(TAG + " " + msg);

        // try root write
        boolean done = false;
        if (sHasRoot) {
            try {
                rootAppendLog(LOG_FILE, line);
                done = true;
            } catch (Throwable ignored) {}
        }

        // fallback: try direct File API (可能因 Scoped Storage 失败)
        if (!done) {
            try {
                File dir = new File(HOOK_DIR);
                if (!dir.exists()) dir.mkdirs();
                FileWriter fw = new FileWriter(LOG_FILE, true);
                fw.write(line + "\n");
                fw.close();
            } catch (Throwable ignored) {}
        }
    }

    // ==================== 初始化 ====================

    private void initEnvironment() {
        // 测试 su
        String test = execRoot("id");
        sHasRoot = test.contains("uid=");

        if (sHasRoot) {
            XposedBridge.log(TAG + " [INIT] root 可用！");
            execRootSilent("mkdir -p '" + HOOK_DIR + "'");
            execRootSilent("chmod 0777 '" + HOOK_DIR + "'");
        } else {
            XposedBridge.log(TAG + " [INIT] root 不可用，尝试普通文件 API");
            try {
                File dir = new File(HOOK_DIR);
                boolean ok = dir.exists() || dir.mkdirs();
                XposedBridge.log(TAG + " [INIT] mkdirs() = " + ok);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " [INIT] mkdirs() 异常: " + t.getMessage());
            }
        }

        writeLog("========================================");
        writeLog("[BOOT] 模块已启动！root=" + sHasRoot + " ClassLoader=" + getClass().getClassLoader());
    }

    // ==================== 主入口 ====================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 首次加载时初始化环境
        initEnvironment();

        writeLog("handleLoadPackage() package=" + lpparam.packageName);

        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            writeLog("跳过: " + lpparam.packageName);
            return;
        }

        writeLog(">>> 成功注入网易大神！");

        // 写入一个标记文件，确认模块已加载
        if (sHasRoot) {
            rootWriteFile(HOOK_DIR + "/module_loaded.flag",
                    "loaded_at=" + System.currentTimeMillis() + "\n" +
                    "package=" + lpparam.packageName + "\n" +
                    "processName=" + lpparam.processName);
        }

        // 尝试 Hook RealCall
        String[][] candidates = {
            {"okhttp3.RealCall", "okhttp3.Callback", "okhttp3.Request"},
            {"okhttp3.internal.connection.RealCall", "okhttp3.Callback", "okhttp3.Request"},
            {"okhttp3.RealCall", "okhttp3.Callback", "okhttp3.Request"},
        };

        boolean hooked = false;
        for (String[] cand : candidates) {
            try {
                hookRealCall(cand[0], cand[1], lpparam.classLoader);
                writeLog(">>> 成功 Hook: " + cand[0]);
                hooked = true;
                break;
            } catch (Throwable t) {
                writeLog(">>> " + cand[0] + " 失败: " + t.getMessage());
            }
        }

        // 额外尝试 Hook OkHttpClient
        try {
            XposedHelpers.findAndHookMethod(
                "okhttp3.OkHttpClient",
                lpparam.classLoader,
                "newCall",
                "okhttp3.Request",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object call = param.getResult();
                        if (call != null) {
                            writeLog("newCall() -> " + call.getClass().getName());
                        }
                    }
                }
            );
            writeLog(">>> 成功 Hook: OkHttpClient.newCall()");
        } catch (Throwable t) {
            writeLog(">>> OkHttpClient.newCall() 失败: " + t.getMessage());
        }

        if (!hooked) {
            writeLog(">>> ⚠ 未找到 RealCall！尝试直接 hook Response ...");
            // fallback: hook Response 的 body() 来捕获所有请求
            try {
                hookResponseBody(lpparam.classLoader);
                writeLog(">>> 成功 Hook: Response.body() fallback");
                hooked = true;
            } catch (Throwable t) {
                writeLog(">>> Response.body() fallback 也失败: " + t.getMessage());
            }
        }

        writeLog(">>> 初始化完成，hooked=" + hooked);
        writeLog("========================================");
    }

    // ==================== Hook 方法 ====================

    private void hookRealCall(String realCallClass, String callbackClass, ClassLoader cl) throws Throwable {
        // Hook execute()
        XposedHelpers.findAndHookMethod(
            realCallClass, cl, "execute",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object response = param.getResult();
                    handleResponse(response);
                }
            }
        );

        // Hook enqueue()
        Class<?> cbClass = XposedHelpers.findClass(callbackClass, cl);
        XposedHelpers.findAndHookMethod(
            realCallClass, cl, "enqueue", cbClass,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object originalCallback = param.args[0];
                    if (originalCallback == null) return;

                    param.args[0] = Proxy.newProxyInstance(
                        cl,
                        new Class<?>[]{cbClass},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                if ("onResponse".equals(method.getName())) {
                                    handleResponse(args[1]);
                                }
                                return method.invoke(originalCallback, args);
                            }
                        }
                    );
                }
            }
        );
    }

    /**
     * Fallback: Hook Response.body() 来捕获所有响应
     */
    private void hookResponseBody(ClassLoader cl) throws Throwable {
        XposedHelpers.findAndHookMethod(
            "okhttp3.Response", cl, "body",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // 不直接处理，只是记录
                }
            }
        );

        // Hook Response.peekBody()
        XposedHelpers.findAndHookMethod(
            "okhttp3.Response", cl, "peekBody", long.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log(TAG + " peekBody() called");
                }
            }
        );
    }

    // ==================== 响应处理 ====================

    private void handleResponse(Object response) {
        if (response == null) return;
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            Object urlObj = XposedHelpers.callMethod(request, "url");
            String url = urlObj.toString();

            if (url.contains("/v1/act/common/currency/getCurrencyInfo")) {
                writeLog(">>> ✅ 命中目标 API: " + url);

                String glUid = (String) XposedHelpers.callMethod(request, "header", "GL-Uid");
                String glToken = (String) XposedHelpers.callMethod(request, "header", "GL-Token");
                String glDevice = (String) XposedHelpers.callMethod(request, "header", "GL-DeviceId");
                String glNonce = (String) XposedHelpers.callMethod(request, "header", "GL-Nonce");

                writeLog(">>> Headers: Uid=" + glUid + " Token=" + (glToken != null ? glToken.substring(0, Math.min(10, glToken.length())) + "..." : "null")
                        + " Device=" + glDevice + " Nonce=" + glNonce);

                JSONObject vault = new JSONObject();
                vault.put("url", url);
                vault.put("gl_uid", glUid != null ? glUid : "");
                vault.put("gl_token", glToken != null ? glToken : "");
                vault.put("gl_device_id", glDevice != null ? glDevice : "");
                vault.put("gl_nonce", glNonce != null ? glNonce : "");
                vault.put("timestamp", System.currentTimeMillis());

                String json = vault.toString(2);

                if (sHasRoot) {
                    rootWriteFile(VAULT_FILE, json);
                } else {
                    File dir = new File(HOOK_DIR);
                    if (!dir.exists()) dir.mkdirs();
                    FileWriter fw = new FileWriter(VAULT_FILE);
                    fw.write(json);
                    fw.close();
                }

                writeLog(">>> ✅ 凭证已写入: " + VAULT_FILE);
            }
        } catch (Throwable t) {
            writeLog(">>> ❌ handleResponse 异常: " + t.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            writeLog(">>> StackTrace: " + sw.toString());
        }
    }
}
