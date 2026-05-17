package com.example.dashenhook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
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

    /**
     * 通过 root (su) 执行 shell 命令
     */
    private static String execRoot(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(process.getOutputStream());
            osw.write(command + "\n");
            osw.write("exit\n");
            osw.flush();

            // 读取 stdout
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // 读取 stderr
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder error = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                error.append(line).append("\n");
            }

            process.waitFor();
            String result = output.toString().trim();
            String errStr = error.toString().trim();

            if (!errStr.isEmpty()) {
                return "[ERR] " + errStr;
            }
            return result.isEmpty() ? "[OK]" : result;
        } catch (Exception e) {
            return "[EXCEPTION] " + e.getMessage();
        }
    }

    /**
     * 通过 root 写文件（避免 Android 10+ Scoped Storage 限制）
     */
    private static void rootWriteFile(String filePath, String content) {
        // 先确保目录存在
        String dir = filePath.substring(0, filePath.lastIndexOf('/'));
        execRoot("mkdir -p '" + dir + "'");
        execRoot("chmod 0777 '" + dir + "'");

        // 用 echo 写入文件（转义特殊字符）
        String escaped = content
                .replace("'", "'\\''");
        execRoot("echo '" + escaped + "' > '" + filePath + "'");
        execRoot("chmod 0666 '" + filePath + "'");
    }

    /**
     * 通过 root 追加写日志
     */
    private static void rootAppendLog(String filePath, String line) {
        String dir = filePath.substring(0, filePath.lastIndexOf('/'));
        execRoot("mkdir -p '" + dir + "'");
        execRoot("chmod 0777 '" + dir + "'");

        String escaped = line
                .replace("'", "'\\''");
        execRoot("echo '" + escaped + "' >> '" + filePath + "'");
        execRoot("chmod 0666 '" + filePath + "'");
    }

    /**
     * 写日志（同时写文件 + XposedBridge.log）
     */
    private void writeLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        String line = "[" + sdf.format(new Date()) + "] " + message;
        rootAppendLog(LOG_FILE, line);
        XposedBridge.log(TAG + " " + message);
    }

    /**
     * 初始化（验证 root 可用 + 创建目录）
     */
    private boolean initRoot() {
        // 测试 su 是否可用
        String testResult = execRoot("id");
        writeLog("[INIT] su 测试: " + testResult);

        // 强制创建目录
        String mkdirResult = execRoot("mkdir -p '" + HOOK_DIR + "'");
        writeLog("[INIT] 创建目录: " + mkdirResult);

        String chmodResult = execRoot("chmod 0777 '" + HOOK_DIR + "'");
        writeLog("[INIT] 权限设置: " + chmodResult);

        // 写入启动标记
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        String bootLine = "[" + sdf.format(new Date()) + "] [BOOT] 模块已加载! ClassLoader="
                + DashenHookModule.class.getClassLoader();
        rootAppendLog(LOG_FILE, bootLine);

        return testResult.contains("uid=");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            // 先初始化 root 环境
            initRoot();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [BOOT] root 初始化异常: " + t.getMessage());
        }

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

                // 通过 root 写文件
                rootWriteFile(VAULT_FILE, vault.toString(2));
                writeLog(">>> ✅ 凭证已写入: " + VAULT_FILE);
            }
        } catch (Throwable t) {
            writeLog(">>> ❌ 处理响应异常: " + t.getMessage());
            // 打印详细栈
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            writeLog(">>> StackTrace: " + sw.toString());
        }
    }
}
