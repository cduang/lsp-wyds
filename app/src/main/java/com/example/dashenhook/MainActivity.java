package com.example.dashenhook;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

public class MainActivity extends Activity {

    private TextView tvProcessStatus, tvProcessPid, tvModuleStatus, tvHookStatus, tvLog;
    private Button btnRefresh, btnClear;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvProcessStatus = findViewById(R.id.tvProcessStatus);
        tvProcessPid = findViewById(R.id.tvProcessPid);
        tvModuleStatus = findViewById(R.id.tvModuleStatus);
        tvHookStatus = findViewById(R.id.tvHookStatus);
        tvLog = findViewById(R.id.tvLog);
        btnRefresh = findViewById(R.id.btnRefreshLog);
        btnClear = findViewById(R.id.btnClearLog);

        btnRefresh.setOnClickListener(v -> refreshAll());
        btnClear.setOnClickListener(v -> {
            tvLog.setText("");
            // 也清空日志文件
            execRoot("echo '' > /sdcard/DashenHook/dashen_hook.log");
        });

        // 每 3 秒自动刷新
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshAll();
                handler.postDelayed(this, 3000);
            }
        };
        handler.post(refreshRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (refreshRunnable != null) {
            handler.removeCallbacks(refreshRunnable);
        }
    }

    private void refreshAll() {
        refreshProcessStatus();
        refreshModuleStatus();
        refreshLog();
    }

    /**
     * 检测网易大神进程状态
     */
    private void refreshProcessStatus() {
        String pidInfo = execRoot("ps -A | grep com.netease.gl | grep -v grep");
        if (pidInfo == null || pidInfo.isEmpty() || pidInfo.startsWith("[ERR]") || pidInfo.startsWith("[EXCEPTION]")) {
            // 尝试 busybox ps
            pidInfo = execRoot("ps | grep com.netease.gl | grep -v grep");
        }

        if (pidInfo != null && !pidInfo.isEmpty() && !pidInfo.startsWith("[")) {
            String[] parts = pidInfo.trim().split("\\s+");
            String pid = "未知";
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].matches("\\d+")) {
                    pid = parts[i];
                    break;
                }
            }
            String finalPid = pid;
            runOnUiThread(() -> {
                tvProcessStatus.setText("● 运行中");
                tvProcessStatus.setTextColor(0xFF4CAF50);
                tvProcessPid.setText("PID: " + finalPid);
            });
        } else {
            runOnUiThread(() -> {
                tvProcessStatus.setText("○ 未运行");
                tvProcessStatus.setTextColor(0xFFF44336);
                tvProcessPid.setText("PID: -");
            });
        }
    }

    /**
     * 检测模块状态（读取日志文件）
     */
    private void refreshModuleStatus() {
        String logContent = readFile("/sdcard/DashenHook/dashen_hook.log");
        if (logContent == null || logContent.isEmpty()) {
            runOnUiThread(() -> {
                tvModuleStatus.setText("未运行");
                tvModuleStatus.setTextColor(0xFFF44336);
                tvHookStatus.setText("Hook: 无数据");
                tvHookStatus.setTextColor(0xFF999999);
            });
            return;
        }

        // 检查是否包含注入成功或 Hook 成功的信息
        boolean injected = logContent.contains("成功注入") || logContent.contains("handleLoadPackage");
        boolean hooked = logContent.contains("成功 Hook");

        runOnUiThread(() -> {
            if (injected) {
                tvModuleStatus.setText("已注入 ✓");
                tvModuleStatus.setTextColor(0xFF4CAF50);
            } else {
                tvModuleStatus.setText("等待注入...");
                tvModuleStatus.setTextColor(0xFFFF9800);
            }

            if (hooked) {
                tvHookStatus.setText("Hook: 已就绪 ✓");
                tvHookStatus.setTextColor(0xFF4CAF50);
            } else {
                tvHookStatus.setText("Hook: 未就绪");
                tvHookStatus.setTextColor(0xFFF44336);
            }
        });
    }

    /**
     * 读取日志文件显示
     */
    private void refreshLog() {
        String content = readFile("/sdcard/DashenHook/dashen_hook.log");
        if (content != null && !content.isEmpty()) {
            // 只显示最后 100 行
            String[] lines = content.split("\n");
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, lines.length - 100);
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            String finalContent = sb.toString();
            runOnUiThread(() -> tvLog.setText(finalContent));
        } else {
            // 尝试读取 Xposed 日志
            String xposedLog = execRoot("logcat -d -s DashenPointsHook:D 2>/dev/null | tail -50");
            if (xposedLog != null && !xposedLog.startsWith("[") && !xposedLog.isEmpty()) {
                String finalLog = xposedLog;
                runOnUiThread(() -> tvLog.setText(finalLog));
            } else {
                runOnUiThread(() -> tvLog.setText("等待模块启动...\n(root结果: " + (xposedLog != null ? xposedLog : "null") + ")"));
            }
        }
    }

    /**
     * 通过 root 执行命令
     */
    private String execRoot(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(process.getOutputStream());
            osw.write(command + "\n");
            osw.write("exit\n");
            osw.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            String result = output.toString().trim();
            return result.isEmpty() ? "[EMPTY]" : result;
        } catch (Exception e) {
            return "[EXCEPTION] " + e.getMessage();
        }
    }

    /**
     * 直接读文件（作为 fallback）
     */
    private String readFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            // fallback: 用 root 读
            return execRoot("cat '" + path + "' 2>/dev/null");
        }
    }
}
