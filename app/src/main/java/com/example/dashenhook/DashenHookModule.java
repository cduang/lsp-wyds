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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONObject;

public class DashenHookModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "DashenPointsHook";
    private static final String TARGET_PACKAGE = "com.netease.gl";
    private static final String HOOK_DIR = "/sdcard/DashenHook";
    private static final String VAULT_FILE = HOOK_DIR + "/dashen_vault.json";
    private static final String LOG_FILE = HOOK_DIR + "/dashen_hook.log";

    private static boolean sHasRoot = false;
    private static final StringBuilder sAllPackages = new StringBuilder();

    // ==================== Zygote 初始化 ====================

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        // Zygote 阶段（系统刚启动时执行一次）
        // 在这里尝试初始化 root，这样后续所有进程都能用
        try {
            String test = execRoot("id");
            sHasRoot = test.contains("uid=");
        } catch (Throwable ignored) {}

        if (sHasRoot) {
            execRootSilent("mkdir -p '" + HOOK_DIR + "'");
            execRootSilent("chmod 0777 '" + HOOK_DIR + "'");
        }

        // 写一条 Zygote 日志
        String msg = "[ZYGOTE] initZygote() root=" + sHasRoot;
        XposedBridge.log(TAG + " " + msg);
        if (sHasRoot) {
            rootAppendLog(LOG_FILE, "[" + timestamp() + "] " + msg);
        }
    }

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

    // ==================== 日志 ====================

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    private void writeLog(String msg) {
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

    // ==================== LoadPackage ====================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 记录所有被调用的包名（用于诊断 LSPosed 作用域问题）
        String pkg = lpparam.packageName;
        String proc = lpparam.processName;
        synchronized (sAllPackages) {
            if (sAllPackages.indexOf(pkg) < 0) {
                if (sAllPackages.length() > 0) sAllPackages.append(", ");
                sAllPackages.append(pkg);
            }
        }

        writeLog("--------------------------------------------------");
        writeLog("handleLoadPackage() package=" + pkg + " process=" + proc);

        // 如果 root 未在 Zygote 阶段初始化，在这里初始化
        if (!sHasRoot) {
            String test = execRoot("id");
            sHasRoot = test.contains("uid=");
            writeLog("root init: " + (sHasRoot ? "可用" : "不可用"));
        }

        // 写入已调用列表
        if (sHasRoot) {
            synchronized (sAllPackages) {
                String info = "packages_called=" + sAllPackages.toString() + "\nlast_update=" + System.currentTimeMillis();
                rootWriteFile(HOOK_DIR + "/zygote_packages.log", info);
            }
        }

        // 过滤目标
        if (!pkg.equals(TARGET_PACKAGE)) {
            writeLog("跳过: " + pkg);
            return;
        }

        writeLog(">>> ✅ 成功注入目标应用: " + pkg);

        // 写入注入成功标记
        if (sHasRoot) {
            rootWriteFile(HOOK_DIR + "/injected.flag",
                    "injected_at=" + System.currentTimeMillis() + "\n" +
                    "pid=" + getPid() + "\n" +
                    "package=" + pkg + "\n" +
                    "process=" + proc);
        }

        // 尝试 Hook RealCall
        String[][] candidates = {
            {"okhttp3.RealCall", "okhttp3.Callback", "okhttp3.Request"},
            {"okhttp3.internal.connection.RealCall", "okhttp3.Callback", "okhttp3.Request"},
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

        if (!hooked) {
            writeLog(">>> ⚠ RealCall Hook 全部失败");
            // 尝试 Hook newCall
            try {
                hookNewCall(lpparam.classLoader);
                writeLog(">>> 成功 Hook: OkHttpClient.newCall()");
                hooked = true;
            } catch (Throwable t) {
                writeLog(">>> newCall() 失败: " + t.getMessage());
            }
        }

        writeLog(">>> 完成, hooked=" + hooked);
        writeLog("--------------------------------------------------");
    }

    // ==================== Hook 方法 ====================

    private void hookRealCall(String realCallClass, String callbackClass, ClassLoader cl) throws Throwable {
        XposedHelpers.findAndHookMethod(realCallClass, cl, "execute",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    handleResponse(param.getResult());
                }
            }
        );

        Class<?> cbClass = XposedHelpers.findClass(callbackClass, cl);
        XposedHelpers.findAndHookMethod(realCallClass, cl, "enqueue", cbClass,
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
    }

    private void hookNewCall(ClassLoader cl) throws Throwable {
        XposedHelpers.findAndHookMethod("okhttp3.OkHttpClient", cl, "newCall",
            "okhttp3.Request",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object call = param.getResult();
                    if (call != null) {
                        try {
                            XposedHelpers.findAndHookMethod(call.getClass(), "execute",
                                new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                        handleResponse(p.getResult());
                                    }
                                }
                            );
                            Class<?> cb = XposedHelpers.findClass("okhttp3.Callback", cl);
                            XposedHelpers.findAndHookMethod(call.getClass(), "enqueue", cb,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                        Object orig = p.args[0];
                                        if (orig == null) return;
                                        p.args[0] = Proxy.newProxyInstance(cl, new Class<?>[]{cb},
                                            new InvocationHandler() {
                                                @Override
                                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                                    if ("onResponse".equals(method.getName())) handleResponse(args[1]);
                                                    return method.invoke(orig, args);
                                                }
                                            }
                                        );
                                    }
                                }
                            );
                        } catch (Throwable ignored) {}
                    }
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

                writeLog(">>> Headers: Uid=" + glUid + " Token=" +
                    (glToken != null ? glToken.substring(0, Math.min(10, glToken.length())) + "..." : "null"));

                JSONObject vault = new JSONObject();
                vault.put("url", url);
                vault.put("gl_uid", glUid != null ? glUid : "");
                vault.put("gl_token", glToken != null ? glToken : "");
                vault.put("gl_device_id", glDevice != null ? glDevice : "");
                vault.put("gl_nonce", glNonce != null ? glNonce : "");
                vault.put("timestamp", System.currentTimeMillis());

                if (sHasRoot) {
                    rootWriteFile(VAULT_FILE, vault.toString(2));
                } else {
                    File dir = new File(HOOK_DIR);
                    if (!dir.exists()) dir.mkdirs();
                    FileWriter fw = new FileWriter(VAULT_FILE);
                    fw.write(vault.toString(2));
                    fw.close();
                }
                writeLog(">>> ✅ 凭证已写入: " + VAULT_FILE);
            }
        } catch (Throwable t) {
            writeLog(">>> ❌ 异常: " + t.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            writeLog(">>> StackTrace: " + sw.toString());
        }
    }

    private static int getPid() {
        try {
            return Integer.parseInt(new BufferedReader(new InputStreamReader(
                Runtime.getRuntime().exec("echo $$").getInputStream())).readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
