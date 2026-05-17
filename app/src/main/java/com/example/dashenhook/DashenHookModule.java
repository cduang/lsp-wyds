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
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class DashenHookModule implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String TAG = "DashenPointsHook";
    private static final String TARGET_PACKAGE = "com.netease.gl";
    private static final String HOOK_DIR = "/sdcard/DashenHook";
    private static final String VAULT_FILE = HOOK_DIR + "/dashen_vault.json";
    private static final String LOG_FILE = HOOK_DIR + "/dashen_hook.log";
    // 匹配域名前缀，不包含后面变化的参数
    private static final String TARGET_URL_KEYWORD = "god-act.gameyw.netease.com/v1/act/common/currency/getCurrencyInfo";

    private static boolean sHasRoot = false;
    private static boolean sHookedGlobal = false;

    // ==================== Zygote ====================

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        try {
            String test = execRoot("id");
            sHasRoot = test.contains("uid=");
        } catch (Throwable ignored) {}

        if (sHasRoot) {
            execRootSilent("mkdir -p '" + HOOK_DIR + "'");
            execRootSilent("chmod 0777 '" + HOOK_DIR + "'");
        }

        writeLog("[ZYGOTE] initZygote() root=" + sHasRoot);

        // 全局 Hook 每个 APP 启动
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ActivityThread", null,
                "handleBindApplication",
                "android.app.ActivityThread.AppBindData",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object bindData = param.args[0];
                            Object appInfo = XposedHelpers.getObjectField(bindData, "appInfo");
                            // packageName 是 ApplicationInfo 的字段，不是方法
                            String packageName = (String) XposedHelpers.getObjectField(appInfo, "packageName");
                            Object processName = XposedHelpers.getObjectField(bindData, "processName");

                            if (TARGET_PACKAGE.equals(packageName)) {
                                writeLog("[GLOBAL] 目标进程启动！package=" + packageName + " process=" + processName);

                                // 延迟一点等 ClassLoader 就绪
                                try { Thread.sleep(200); } catch (InterruptedException ignored) {}

                                Object loadedApk = XposedHelpers.getObjectField(bindData, "info");
                                if (loadedApk != null) {
                                    try {
                                        ClassLoader cl = (ClassLoader) XposedHelpers.callMethod(loadedApk, "getClassLoader");
                                        if (cl != null) {
                                            writeLog("[GLOBAL] ClassLoader 获取成功！尝试 Hook OkHttp...");
                                            hookOkHttpGlobal(cl);
                                            sHookedGlobal = true;
                                            writeLog("[GLOBAL] ✅ Hook 完成！");
                                        } else {
                                            writeLog("[GLOBAL] ClassLoader 为 null");
                                        }
                                    } catch (Throwable t) {
                                        writeLog("[GLOBAL] getClassLoader 失败: " + t.getMessage());
                                    }
                                } else {
                                    writeLog("[GLOBAL] loadedApk 为 null");
                                }
                            }
                        } catch (Throwable t) {
                            writeLog("[GLOBAL] 异常: " + t.getMessage());
                        }
                    }
                }
            );
            writeLog("[ZYGOTE] ✅ handleBindApplication 全局 Hook 已注册");
        } catch (Throwable t) {
            writeLog("[ZYGOTE] ⚠ handleBindApplication Hook 失败: " + t.getMessage());
        }
    }

    // ==================== LoadPackage (备用) ====================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;
        writeLog("[LSPosed] LSPosed 注入成功: " + lpparam.packageName);
        hookOkHttpGlobal(lpparam.classLoader);
    }

    // ==================== 核心 Hook ====================

    public static void hookOkHttpGlobal(ClassLoader cl) {
        try {
            // ====== 1. Hook Request.Builder.build() ======
            // 在请求构建时捕获所有信息（最可靠）
            try {
                XposedHelpers.findAndHookMethod(
                    "okhttp3.Request$Builder", cl, "build",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object request = param.getResult();
                            if (!isTargetRequest(request)) return;

                            writeLog("========================================");
                            writeLog("[CAPTURE] 捕获到目标请求！");

                            JSONObject data = new JSONObject();
                            long startTime = System.currentTimeMillis();

                            // ---- URL ----
                            Object urlObj = XposedHelpers.callMethod(request, "url");
                            String urlStr = urlObj != null ? urlObj.toString() : "";
                            data.put("url", urlStr);
                            data.put("base_url", urlStr.contains("?") ? urlStr.substring(0, urlStr.indexOf("?")) : urlStr);
                            writeLog("[URL] " + urlStr);

                            // ---- URL 查询参数 ----
                            try {
                                JSONObject queryParams = new JSONObject();
                                // 通过 okhttp3.HttpUrl 的方法获取参数
                                if (urlObj != null) {
                                    int size = (int) XposedHelpers.callMethod(urlObj, "querySize");
                                    for (int i = 0; i < size; i++) {
                                        String name = (String) XposedHelpers.callMethod(urlObj, "queryParameterName", i);
                                        String value = (String) XposedHelpers.callMethod(urlObj, "queryParameterValue", i);
                                        queryParams.put(name, value);
                                    }
                                }
                                data.put("query_params", queryParams);
                                writeLog("[QUERY] " + queryParams.toString());
                            } catch (Throwable t) {
                                writeLog("[QUERY] 解析失败: " + t.getMessage());
                            }

                            // ---- 方法 ----
                            String method = (String) XposedHelpers.callMethod(request, "method");
                            data.put("method", method);
                            writeLog("[METHOD] " + method);

                            // ---- 所有请求头 ----
                            try {
                                JSONObject headers = new JSONObject();
                                Object headersObj = XposedHelpers.callMethod(request, "headers");
                                // okhttp3.Headers 的 names() 和 values()
                                if (headersObj != null) {
                                    int len = (int) XposedHelpers.callMethod(headersObj, "size");
                                    for (int i = 0; i < len; i++) {
                                        String name = (String) XposedHelpers.callMethod(headersObj, "name", i);
                                        String value = (String) XposedHelpers.callMethod(headersObj, "value", i);
                                        headers.put(name, value);
                                    }
                                }
                                data.put("headers", headers);
                                writeLog("[HEADERS] " + headers.toString());
                            } catch (Throwable t) {
                                writeLog("[HEADERS] 解析失败: " + t.getMessage());
                            }

                            // ---- 请求体 (POST Body) ----
                            String bodyStr = "";
                            try {
                                Object body = XposedHelpers.callMethod(request, "body");
                                if (body != null) {
                                    Object contentType = XposedHelpers.callMethod(body, "contentType");
                                    if (contentType != null) {
                                        data.put("content_type", contentType.toString());
                                    }
                                    // 通过 okio.Buffer 读取
                                    Object buffer = XposedHelpers.newInstance(
                                        XposedHelpers.findClass("okio.Buffer", cl));
                                    XposedHelpers.callMethod(body, "writeTo", buffer);
                                    bodyStr = (String) XposedHelpers.callMethod(buffer, "readUtf8");
                                    data.put("body", bodyStr);
                                    writeLog("[BODY] " + bodyStr);
                                }
                            } catch (Throwable t) {
                                bodyStr = "[read error: " + t.getMessage() + "]";
                                data.put("body", bodyStr);
                            }

                            data.put("timestamp", startTime);
                            data.put("capture_time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                                Locale.getDefault()).format(new Date(startTime)));

                            // ---- 写入 JSON 文件 ----
                            writeJson(data.toString(2));
                            writeLog("[SAVE] ✅ 已写入: " + VAULT_FILE);
                            writeLog("========================================");
                        }
                    }
                );
                writeLog(">>> ✅ Request.Builder.build() Hook 成功");
            } catch (Throwable t) {
                writeLog(">>> ❌ Request.Builder.build() Hook 失败: " + t.getMessage());
            }

            // ====== 2. Hook Response.body().string() ======
            // 捕获响应内容
            try {
                XposedHelpers.findAndHookMethod(
                    "okhttp3.ResponseBody", cl, "string",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            // 通过 context 判断是否目标请求
                            // ResponseBody 本身不知道 Request，所以我们只记录
                            String body = (String) param.getResult();
                            if (body != null && body.contains("currency")) {
                                writeLog("[RESPONSE] 捕获到可能相关的响应，长度=" + body.length());
                                writeLog("[RESPONSE] 前200字符: " + (body.length() > 200 ? body.substring(0, 200) : body));

                                // 追加写入响应到单独文件
                                JSONObject respData = new JSONObject();
                                respData.put("response_body", body);
                                respData.put("capture_time", System.currentTimeMillis());
                                String respFile = HOOK_DIR + "/dashen_response.json";
                                if (sHasRoot) {
                                    rootWriteFile(respFile, respData.toString(2));
                                }
                                writeLog("[RESPONSE] ✅ 响应已写入: " + respFile);
                            }
                        }
                    }
                );
                writeLog(">>> ✅ ResponseBody.string() Hook 成功");
            } catch (Throwable t) {
                writeLog(">>> ❌ ResponseBody.string() Hook 失败: " + t.getMessage());
            }

        } catch (Throwable t) {
            writeLog("[hookOkHttpGlobal] 整体异常: " + t.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            writeLog("[hookOkHttpGlobal] StackTrace: " + sw.toString());
        }
    }

    /**
     * 判断是否是目标请求（匹配域名关键字）
     */
    private static boolean isTargetRequest(Object request) {
        try {
            Object urlObj = XposedHelpers.callMethod(request, "url");
            if (urlObj == null) return false;
            String url = urlObj.toString();
            return url.contains(TARGET_URL_KEYWORD);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 保存 JSON 数据到文件（追加模式，保留历史）
     */
    private static void writeJson(String jsonContent) {
        if (sHasRoot) {
            // 写主文件（覆盖）
            rootWriteFile(VAULT_FILE, jsonContent);

            // 追加到历史文件
            String historyFile = HOOK_DIR + "/dashen_history.jsonl";
            String line = jsonContent.replace("\n", " ") + "\n";
            String dir = historyFile.substring(0, historyFile.lastIndexOf('/'));
            execRootSilent("mkdir -p '" + dir + "'");
            execRootSilent("chmod 0777 '" + dir + "'");
            String escaped = line.replace("'", "'\\''");
            execRoot("echo '" + escaped + "' >> '" + historyFile + "'");
        } else {
            try {
                File dir = new File(HOOK_DIR);
                if (!dir.exists()) dir.mkdirs();
                FileWriter fw = new FileWriter(VAULT_FILE);
                fw.write(jsonContent);
                fw.close();
            } catch (Throwable ignored) {}
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
