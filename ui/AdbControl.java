package neev.adb;

import android.content.Context;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Manually launched root overlay for Gen5W Android 4.4; no APK or boot entry. */
public final class AdbControl extends Activity {
    private static final String ID = "blackr.wireless.adb";
    private static final String[] ROOTS = {
        "/data/nemesis/apps/" + ID, "/data/nemesis/packages/" + ID
    };
    private static final int OUTPUT_LIMIT = 32768;
    private static final int MAX_LOG_LINES = 200;
    private static final long ACTION_TIMEOUT_MS = 40000L;
    private static final long STATUS_TIMEOUT_MS = 5000L;
    private File root;
    private boolean resumed;
    private boolean wifiLoading;
    private int visibilityGeneration;
    private Handler handler;
    private WindowManager windows;
    private View overlay;
    private FrameLayout screen;
    private View wifiPanel;
    private TextView wifiName;
    private TextView wifiPassword;
    private ImageView wifiQr;
    private TextView wifiQrNote;
    private Bitmap wifiQrBitmap;
    private int wifiQrPixels;
    private WifiInfo wifiInfo;
    private boolean wifiRevealed;
    private TextView status;
    private TextView detail;
    private TextView received;
    private TextView transmitted;
    private TextView logView;
    private ScrollView logScroll;
    private Button scrollMode;
    private boolean compact;
    private boolean autoScroll = true;
    private String displayedLog = "";
    private Button start;
    private Button stop;
    private volatile boolean closed;
    private boolean busy;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new File("/data/nemesis/apps/" + ID);
        handler = new Handler(Looper.getMainLooper());
        windows = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        if (windows == null) { finish(); return; }
        setContentView(buildView(this));
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true;
        if (handler != null) { handler.removeCallbacks(poll); if(!busy)runAction("status",false); handler.postDelayed(poll,2000L); }
    }
    @Override protected void onPause() {
        resumed=false; visibilityGeneration++;
        if(handler!=null)handler.removeCallbacks(poll);
        dismissWifiInfo();
        super.onPause();
    }
    @Override protected void onDestroy() {
        closed=true;
        if(handler!=null)handler.removeCallbacks(poll);
        dismissWifiInfo();
        super.onDestroy();
    }

    private static File localFile(File root, String relative) throws Exception {
        File file = new File(root, relative);
        if (!file.getPath().equals(file.getCanonicalPath())) {
            throw new IllegalStateException("package path must not be a symlink");
        }
        return file;
    }

    private boolean sameInstallation() { return root != null && !closed; }

    private View buildView(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getMetrics(metrics);
        LayoutSpec layout = LayoutSpec.forDisplay(metrics.widthPixels, metrics.heightPixels, metrics.density);
        compact = layout.compact;
        FrameLayout frame = new FrameLayout(context);
        screen = frame;
        frame.setBackgroundColor(0xFF101B28);
        frame.setClickable(true);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, layout.insetX), dp(context, layout.insetY),
            dp(context, layout.insetX), dp(context, layout.insetY));
        frame.addView(card, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text(context, "동글 ADB · 실시간 모니터", compact ? 20 : 25, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, compact ? -1 : -2, 1));
        TextView endpoint = text(context, "192.168.43.1:5555", compact ? 20 : 23, 0xFF8DD8FF);
        endpoint.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        heading.addView(endpoint, new LinearLayout.LayoutParams(-2, compact ? -1 : -2));
        addRow(card, heading, context, layout.headingHeight);
        status = text(context, "상태를 확인하고 있습니다.", compact ? 22 : 29, 0xFFE5EDF5);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(0, dp(context, compact ? 4 : 10), 0, dp(context, compact ? 2 : 4));
        addRow(card, status, context, layout.statusHeight);
        detail = text(context, "2초마다 상태와 기록을 갱신합니다.", compact ? 13 : 16, 0xFFB6C5D5);
        detail.setMaxLines(compact ? 1 : 2);
        addRow(card, detail, context, layout.detailHeight);

        LinearLayout traffic = new LinearLayout(context);
        traffic.setOrientation(LinearLayout.HORIZONTAL);
        traffic.setPadding(0, dp(context, compact ? 4 : 10), 0, dp(context, compact ? 4 : 10));
        received = text(context, "수신 PC→차량 · —", compact ? 17 : 20, 0xFF93E5CF);
        transmitted = text(context, "송신 차량→PC · —", compact ? 17 : 20, 0xFF8DD8FF);
        traffic.addView(received, new LinearLayout.LayoutParams(0, -2, 1));
        traffic.addView(transmitted, new LinearLayout.LayoutParams(0, -2, 1));
        addRow(card, traffic, context, layout.trafficHeight);

        TextView logTitle = text(context, "ADB 연결 및 명령 기록  /  시간: 부팅 후 경과", compact ? 14 : 17, 0xFFB6C5D5);
        logTitle.setPadding(0, 0, 0, dp(context, compact ? 4 : 6));
        addRow(card, logTitle, context, layout.logHeadingHeight);
        logScroll = new ScrollView(context);
        logScroll.setFillViewport(true);
        logScroll.setBackgroundColor(0xFF080F18);
        logView = text(context, "아직 기록이 없습니다.", compact ? 16 : 17, 0xFFE5EDF5);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(context, compact ? 8 : 12), dp(context, compact ? 6 : 10),
            dp(context, compact ? 8 : 12), dp(context, compact ? 6 : 10));
        logScroll.addView(logView);
        card.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView note = text(context,
            "앱을 닫아도 ADB 유지 · 정지는 정지 버튼 · 대화형 셸 입력은 표시하지 않음",
            compact ? 12 : 15, 0xFFB6C5D5);
        note.setPadding(0, dp(context, compact ? 4 : 8), 0, dp(context, compact ? 4 : 10));
        addRow(card, note, context, layout.noteHeight);
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        start = button(context, "시작", 0xFF146A4A);
        stop = button(context, "정지", 0xFF923C46);
        scrollMode = button(context, "자동 스크롤 켜짐", 0xFF285674);
        Button wifi = button(context, "Wi-Fi 접속 정보", 0xFF285674);
        Button[] all = {start, stop, wifi, scrollMode};
        for (int i = 0; i < all.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(context, layout.buttonHeight), 1);
            if (i != 0) lp.leftMargin = dp(context, compact ? 6 : 10);
            if (compact) { all[i].setTextSize(20); all[i].setPadding(0, 0, 0, 0); }
            buttons.addView(all[i], lp);
        }
        if (compact) {
            for (TextView line : new TextView[]{title, status, detail, received, transmitted, logTitle, note}) {
                line.setMaxLines(1);
                line.setEllipsize(TextUtils.TruncateAt.END);
                line.setGravity(Gravity.CENTER_VERTICAL);
            }
        }
        bindAction(start, "start", new Runnable() {
            public void run() { runAction("start", true); }
        });
        bindAction(stop, "stop", new Runnable() {
            public void run() { runAction("stop", true); }
        });
        final Context wifiContext = context;
        bindAction(wifi, "wifi-information", new Runnable() {
            public void run() { showWifiInfo(wifiContext); }
        });
        bindAction(scrollMode, "scroll", new Runnable() {
            public void run() {
                autoScroll = !autoScroll;
                scrollMode.setText(autoScroll ? "자동 스크롤 켜짐" : "자동 스크롤 꺼짐");
                if (autoScroll) scrollLogToEnd();
            }
        });
        card.addView(buttons);
        disableFeedback(frame);
        return frame;
    }

    private final Runnable hideWifiInfo = new Runnable() {
        public void run() { dismissWifiInfo(); }
    };

    private void showWifiInfo(final Context context) {
        if(wifiLoading) return;
        wifiLoading=true;
        final int generation=visibilityGeneration;
        dismissWifiInfo();
        Thread worker = new Thread(new Runnable() { public void run() {
            WifiInfo value = null; String description;
            try { value=WifiInfo.load(); description="CarPlay 연결 전에도 확인할 수 있습니다. 접속하려면 동글 핫스팟이 켜져 있어야 합니다."; }
            catch (WifiInfo.Unavailable unavailable) { description=unavailable.userMessage(); }
            final WifiInfo loaded=value; final String message=description;
            handler.post(new Runnable() { public void run() {
                wifiLoading=false;
                if(closed || !resumed || generation!=visibilityGeneration) { if(loaded!=null)loaded.clear(); return; }
                dismissWifiInfo(); wifiInfo=loaded; showWifiPanel(context,message);
            }});
        }}, "AdbWifiInfo");
        worker.setDaemon(true); worker.start();
    }
    private void showWifiPanel(Context context,String message) {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getMetrics(metrics);
        WifiPanelLayout layout = WifiPanelLayout.forDisplay(metrics.widthPixels, metrics.heightPixels, metrics.density);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF101B28);
        panel.setPadding(dp(context, layout.padding), dp(context, layout.padding), dp(context, layout.padding), dp(context, layout.padding));
        TextView heading = text(context, "Wi-Fi 접속 정보", 24, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(context, layout.heading)));
        TextView explanation = text(context, message, 15, 0xFFB6C5D5);
        explanation.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(explanation, new LinearLayout.LayoutParams(-1, dp(context, layout.message)));
        LinearLayout information = new LinearLayout(context);
        information.setOrientation(LinearLayout.HORIZONTAL);
        ScrollView credentials = new ScrollView(context);
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        wifiName = text(context, wifiInfo == null ? "" : "Wi-Fi 이름: " + wifiInfo.ssid, 22, 0xFF8DD8FF);
        wifiPassword = text(context, wifiInfo == null ? "" : "암호: " + wifiInfo.passwordLabel(false), 22, Color.WHITE);
        wifiPassword.setTypeface(Typeface.MONOSPACE);
        wifiPassword.setPadding(0, dp(context, 12), dp(context, 8), 0);
        fields.addView(wifiName, new LinearLayout.LayoutParams(-1, -2));
        fields.addView(wifiPassword, new LinearLayout.LayoutParams(-1, -2));
        credentials.addView(fields);
        information.addView(credentials, new LinearLayout.LayoutParams(0, -1, 1));
        FrameLayout qrArea = new FrameLayout(context);
        wifiQr = new ImageView(context);
        wifiQr.setScaleType(ImageView.ScaleType.CENTER);
        wifiQr.setVisibility(View.INVISIBLE);
        wifiQr.setContentDescription("Wi-Fi 연결 QR");
        qrArea.addView(wifiQr, new FrameLayout.LayoutParams(-1, -1));
        wifiQrNote = text(context, wifiInfo == null ? "" : "암호 보기를 누르면\nWi-Fi 연결 QR이\n함께 표시됩니다.", 16, 0xFFB6C5D5);
        wifiQrNote.setGravity(Gravity.CENTER);
        qrArea.addView(wifiQrNote, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams qrLayout = new LinearLayout.LayoutParams(dp(context, layout.qr), dp(context, layout.qr));
        qrLayout.leftMargin = dp(context, layout.gap);
        qrLayout.gravity = Gravity.CENTER_VERTICAL;
        information.addView(qrArea, qrLayout);
        wifiQrPixels = dp(context, layout.qr);
        panel.addView(information, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView privacy = text(context,
            "암호·QR은 화면에서만 표시되며, 2분 후 닫힙니다. Wi-Fi 설정은 바뀌지 않습니다.",
            13, 0xFFB6C5D5);
        privacy.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(privacy, new LinearLayout.LayoutParams(-1, dp(context, layout.note)));
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final Button reveal = button(context, "암호 보기", 0xFF285674);
        reveal.setEnabled(wifiInfo != null);
        bindAction(reveal, "wifi-password-toggle", new Runnable() {
            public void run() {
                if (wifiInfo == null) return;
                wifiRevealed = !wifiRevealed;
                wifiPassword.setText("암호: " + wifiInfo.passwordLabel(wifiRevealed));
                if (wifiRevealed) revealWifiQr(); else clearWifiQr();
                reveal.setText(wifiRevealed ? "암호 가리기" : "암호 보기");
            }
        });
        Button back = button(context, "돌아가기", 0xFF38516C);
        bindAction(back, "wifi-information-close", new Runnable() {
            public void run() { dismissWifiInfo(); }
        });
        buttons.addView(reveal, new LinearLayout.LayoutParams(0, dp(context, layout.buttons), 1));
        LinearLayout.LayoutParams backLayout = new LinearLayout.LayoutParams(0, dp(context, layout.buttons), 1);
        backLayout.leftMargin = dp(context, 10);
        buttons.addView(back, backLayout);
        panel.addView(buttons);
        disableFeedback(panel);
        panel.setClickable(true);
        wifiPanel = panel;
        screen.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        handler.postDelayed(hideWifiInfo, 120000L);
    }

    private void revealWifiQr() {
        clearWifiQr();
        if (!wifiRevealed || wifiInfo == null || wifiQr == null) return;
        WifiQr.Image image = null;
        try {
            image = WifiQr.render(wifiInfo.ssid, wifiInfo.passwordLabel(true), wifiQrPixels);
            wifiQrBitmap = Bitmap.createBitmap(image.size, image.size, Bitmap.Config.ARGB_8888);
            wifiQrBitmap.setPixels(image.pixels(), 0, image.size, 0, 0, image.size, image.size);
            wifiQrBitmap.setDensity(Bitmap.DENSITY_NONE);
            wifiQr.setImageBitmap(wifiQrBitmap);
            wifiQrNote.setVisibility(View.INVISIBLE);
            wifiQr.setVisibility(View.VISIBLE);
        } catch (RuntimeException unavailable) {
            // No exception text/cause or payload reaches the generic UI logger.
            clearWifiQr();
            if (wifiQrNote != null) wifiQrNote.setText("QR을 표시하지 못했습니다.\n암호를 직접 입력하세요.");
        } finally { if (image != null) image.clear(); }
    }

    private void clearWifiQr() {
        if (wifiQr != null) { wifiQr.setVisibility(View.INVISIBLE); wifiQr.setImageDrawable(null); }
        if (wifiQrBitmap != null) {
            if (!wifiQrBitmap.isRecycled()) { wifiQrBitmap.eraseColor(Color.WHITE); wifiQrBitmap.recycle(); }
            wifiQrBitmap = null;
        }
        if (wifiQrNote != null) {
            wifiQrNote.setText("암호 보기를 누르면\nWi-Fi 연결 QR이\n함께 표시됩니다.");
            wifiQrNote.setVisibility(View.VISIBLE);
        }
    }

    private void dismissWifiInfo() {
        if (handler != null) handler.removeCallbacks(hideWifiInfo);
        clearWifiQr();
        if (wifiName != null) wifiName.setText("");
        if (wifiPassword != null) wifiPassword.setText("");
        if (wifiInfo != null) wifiInfo.clear();
        wifiInfo = null;
        wifiName = null;
        wifiPassword = null;
        wifiQr = null;
        wifiQrNote = null;
        wifiQrPixels = 0;
        wifiRevealed = false;
        if (wifiPanel != null && screen != null) screen.removeView(wifiPanel);
        wifiPanel = null;
    }

    private static void addRow(LinearLayout parent, View row, Context context, int heightDp) {
        parent.addView(row, new LinearLayout.LayoutParams(-1, heightDp < 0 ? -2 : dp(context, heightDp)));
    }

    static final class LayoutSpec {
        final int widthDp, heightDp;
        final boolean compact;
        final int insetX, insetY, headingHeight, statusHeight, detailHeight;
        final int trafficHeight, logHeadingHeight, noteHeight, buttonHeight;
        private LayoutSpec(int widthDp, int heightDp) {
            this.widthDp = widthDp;
            this.heightDp = heightDp;
            compact = heightDp <= 520;
            insetX = compact ? 10 : 20;
            insetY = compact ? 8 : 20;
            headingHeight = compact ? 26 : -1;
            statusHeight = compact ? 34 : -1;
            detailHeight = compact ? 20 : -1;
            trafficHeight = compact ? 32 : -1;
            logHeadingHeight = compact ? 22 : -1;
            noteHeight = compact ? 24 : -1;
            buttonHeight = compact ? 48 : 60;
        }
        static LayoutSpec forDisplay(int widthPixels, int heightPixels, float density) {
            if (!(density > 0)) density = 1;
            return new LayoutSpec(Math.round(widthPixels / density), Math.round(heightPixels / density));
        }
        int compactLogHeightDp() {
            if (!compact) return -1;
            return heightDp - 2 * insetY - headingHeight - statusHeight - detailHeight
                - trafficHeight - logHeadingHeight - noteHeight - buttonHeight;
        }
    }

    private static void disableFeedback(View view) {
        view.setSoundEffectsEnabled(false);
        view.setHapticFeedbackEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) disableFeedback(group.getChildAt(i));
        }
    }

    private static void disableRootMedia(View view) {
        try {
            Object root = View.class.getMethod("getViewRootImpl").invoke(view);
            if (root == null) return;
            java.lang.reflect.Field media = root.getClass().getDeclaredField("mMediaDisabled");
            media.setAccessible(true);
            media.setBoolean(root, true);
        } catch (Throwable error) {
            // Optional OEM field: consumed touch events and per-view feedback
            // disabling remain the primary protection if it is absent.
            logError("optional root media guard unavailable", error);
        }
    }

    private void bindAction(View view, final String label, final Runnable action) {
        disableFeedback(view);
        view.setClickable(true);
        view.setOnTouchListener(new View.OnTouchListener() {
            private boolean armed;
            public boolean onTouch(View target, MotionEvent event) {
                try {
                    int type = event.getActionMasked();
                    if (!target.isEnabled()) { armed = false; target.setPressed(false); return true; }
                    if (type == MotionEvent.ACTION_DOWN) {
                        armed = true;
                        target.setPressed(true);
                    } else if (type == MotionEvent.ACTION_CANCEL) {
                        armed = false;
                        target.setPressed(false);
                    } else if (type == MotionEvent.ACTION_UP) {
                        boolean activate = armed && event.getX() >= 0 && event.getY() >= 0
                            && event.getX() < target.getWidth() && event.getY() < target.getHeight();
                        armed = false;
                        target.setPressed(false);
                        if (activate) {
                            System.out.println("UI action " + label);
                            action.run();
                        }
                    }
                } catch (Throwable error) {
                    armed = false;
                    showUiError("touch " + label, error);
                }
                // Never fall through to View.performClick(): that calls OEM
                // audio/accessibility code before the application listener.
                return true;
            }
        });
    }

    private static void logError(String where, Throwable error) {
        System.err.println(where + ": " + error);
        error.printStackTrace(System.err);
    }

    private void showUiError(String where, Throwable error) {
        logError(where, error);
        try {
            if (closed) return;
            status.setText("화면 처리 중 오류가 발생했습니다. 다시 눌러 주세요.");
            start.setEnabled(!busy);
            stop.setEnabled(!busy);
        } catch (Throwable displayError) { logError("error display", displayError); }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static TextView text(Context context, String value, int size, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private static Button button(Context context, String label, int color) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(22);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(color);
        return button;
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (closed || !resumed) return;
            // Removal or replacement of the package must not leave an orphan
            // overlay. Nothing outside this package is touched on close.
            try {
                if (!sameInstallation()) { close("installation missing or replaced"); return; }
            } catch (Exception missing) {
                logError("installation check", missing);
                close("installation check failed");
                return;
            }
            if (!busy) runAction("status", false);
            handler.postDelayed(this, 2000L);
        }
    };

    private void runAction(final String action, boolean announce) {
        if (closed || busy) return;
        if (!"start".equals(action) && !"stop".equals(action) && !"status".equals(action)) return;
        busy = true;
        try {
            start.setEnabled(false);
            stop.setEnabled(false);
            if (announce) status.setText("start".equals(action)
                ? "ADB를 시작하고 있습니다. 잠시 기다려 주세요."
                : "ADB를 정지하고 있습니다. 잠시 기다려 주세요.");
            Thread worker = new Thread(new Runnable() {
                public void run() {
                    final Result result = executeSafely(action);
                    handler.post(new Runnable() {
                        public void run() {
                            if (closed) return;
                            busy = false;
                            try {
                                render(result);
                                start.setEnabled(true);
                                stop.setEnabled(true);
                            } catch (Throwable error) { showUiError("result " + action, error); }
                        }
                    });
                }
            }, "AdbControl-" + action);
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable error) {
            busy = false;
            showUiError("start action " + action, error);
        }
    }

    private Result executeSafely(String action) {
        try { return execute(action); }
        catch (Throwable error) {
            logError("control worker " + action, error);
            return Result.error("ADB 제어 중 오류가 발생했습니다. 상태를 확인한 뒤 다시 눌러 주세요.");
        }
    }

    private Result execute(String action) {
        java.lang.Process child = null;
        InputStream stream = null;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            if (!sameInstallation()) throw new IllegalStateException("control closed");
            ProcessBuilder builder = new ProcessBuilder("/system/xbin/nemesis", "root", "-c",
                "/system/bin/sh /data/nemesis/apps/" + ID + "/bin/control.sh " + action);
            builder.redirectErrorStream(true);
            child = builder.start();
            child.getOutputStream().close();
            stream = child.getInputStream();
            final InputStream input = stream;
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    try {
                        byte[] buffer = new byte[1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            synchronized (captured) {
                                int keep = Math.min(count, OUTPUT_LIMIT - captured.size());
                                if (keep > 0) captured.write(buffer, 0, keep);
                            }
                        }
                    } catch (Exception ignored) { }
                }
            }, "AdbControlOutput");
            reader.setDaemon(true);
            reader.start();
            long deadline = android.os.SystemClock.elapsedRealtime()
                + ("status".equals(action) ? STATUS_TIMEOUT_MS : ACTION_TIMEOUT_MS);
            int exit;
            for (;;) {
                try { exit = child.exitValue(); break; }
                catch (IllegalThreadStateException waiting) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        child.destroy();
                        return Result.error("응답 시간이 초과되었습니다. 잠시 후 다시 확인합니다.");
                    }
                    Thread.sleep(100L);
                }
            }
            reader.join(1000L);
            String output;
            synchronized (captured) { output = captured.toString("UTF-8"); }
            return new Result(Snapshot.parse(output), exit == 0 ? "" : "명령을 완료하지 못했습니다.");
        } catch (Exception error) {
            logError("control " + action + " failed", error);
            return Result.error("ADB 제어 명령을 실행하지 못했습니다. 잠시 후 다시 확인합니다.");
        } finally {
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
            if (child != null) try { child.getErrorStream().close(); } catch (Exception ignored) { }
        }
    }

    private void render(Result result) {
        Snapshot value = result.value;
        if (result.error.length() != 0) {
            status.setText("상태 확인 중 오류");
            status.setTextColor(0xFFFFA8AE);
            detail.setText(result.error + " 표시된 데이터와 기록은 마지막 확인 값입니다.");
            return;
        }
        status.setText(result.error.length() == 0 ? value.headline() : "상태 확인 중 오류");
        status.setTextColor(result.error.length() != 0 ? 0xFFFFA8AE
            : value.connected() ? 0xFF93E5CF : 0xFFE5EDF5);
        String summary = result.error.length() == 0 ? value.notice : result.error;
        if (value.detail.length() != 0) summary += (summary.length() == 0 ? "" : "  ·  ") + value.detail;
        if (summary.length() == 0) summary = "2초마다 갱신 · PC 연결됨은 ADB 인증 완료 상태입니다.";
        detail.setText(summary);
        received.setText(compact ? "수신 PC→차량 · " + bytes(value.rxBytes) + " · " + count(value.rxPackets) + " 패킷"
            : "수신 RX  " + bytes(value.rxBytes) + "  ·  " + count(value.rxPackets) + " 패킷\nPC → 차량");
        transmitted.setText(compact ? "송신 차량→PC · " + bytes(value.txBytes) + " · " + count(value.txPackets) + " 패킷"
            : "송신 TX  " + bytes(value.txBytes) + "  ·  " + count(value.txPackets) + " 패킷\n차량 → PC");
        // Replace the bounded controller snapshot, rather than appending every
        // poll and duplicating the same event rows forever.
        if (!displayedLog.equals(value.log)) {
            displayedLog = value.log;
            final int previousY = logScroll.getScrollY();
            logView.setText(value.log.length() == 0 ? "아직 기록이 없습니다." : value.log);
            if (autoScroll) scrollLogToEnd();
            else logScroll.post(new Runnable() {
                public void run() { if (!closed && !autoScroll) logScroll.scrollTo(0, previousY); }
            });
        }
    }

    private void scrollLogToEnd() {
        logScroll.post(new Runnable() {
            public void run() { if (!closed && autoScroll) logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private static String count(long value) {
        return value < 0 ? "—" : java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value);
    }

    private static String bytes(long value) {
        if (value < 0) return "—";
        if (value < 1024) return count(value) + " B";
        double amount = value;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        int unit = 0;
        while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
        return String.format(java.util.Locale.US, "%.1f %s", amount, units[unit]);
    }

    static final class Result {
        final Snapshot value;
        final String error;
        Result(Snapshot value, String error) { this.value = value; this.error = error; }
        static Result error(String message) { return new Result(new Snapshot(), message); }
    }

    static final class Snapshot {
        String state = "UNKNOWN", client = "UNKNOWN", detail = "", notice = "", log = "";
        long rxBytes = -1, txBytes = -1, rxPackets = -1, txPackets = -1;

        boolean connected() { return "LISTENING".equals(state) && "CONNECTED".equals(client); }

        String headline() {
            if (connected()) return "PC 연결됨 · ADB 인증 완료";
            if ("LISTENING".equals(state)) return "WAITING".equals(client)
                ? "ADB 실행 중 · PC 연결 대기" : "ADB 실행 중 · 연결 상태 확인 불가";
            if ("WAIT_DONGLE".equals(state)) return "Wi-Fi 동글 연결 대기";
            if ("STOPPED".equals(state) || "RECOVERED".equals(state)) return "ADB 정지됨";
            if ("STARTING".equals(state)) return "ADB 시작 중";
            if ("STOPPING".equals(state)) return "ADB 정지 중";
            if ("DONGLE_REMOVED".equals(state)) return "ADB 정지됨 · 동글 연결 끊김";
            if ("DONGLE_TIMEOUT".equals(state)) return "ADB 정지됨 · 동글 대기 시간 초과";
            if ("WAIT_IP".equals(state)) return "Wi-Fi 연결 주소 대기";
            return "ADB 상태: " + state;
        }

        static Snapshot parse(String output) {
            Snapshot result = new Snapshot();
            java.util.ArrayList<String> rows = new java.util.ArrayList<String>();
            java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
            boolean inLog = false, inCommands = false;
            if (output == null) return result;
            if (output.length() > OUTPUT_LIMIT) output = output.substring(0, OUTPUT_LIMIT);
            for (String raw : output.split("\n")) {
                String line = raw.trim();
                if ("COMMANDS_BEGIN".equals(line)) { inCommands=true; continue; }
                if ("COMMANDS_END".equals(line)) { inCommands=false; continue; }
                if (inCommands) {
                    if (line.startsWith("C1 ") && line.length() <= 300) rows.add("명령 · " + line.substring(3));
                    if (rows.size() > MAX_LOG_LINES) rows.remove(0);
                    continue;
                }
                if ("LOG_BEGIN".equals(line)) { inLog = true; continue; }
                if ("LOG_END".equals(line)) { inLog = false; continue; }
                if (inLog) {
                    String[] part = line.split("\\s+");
                    if (part.length != 6 || !"E1".equals(part[0]) || !code(part[3])) continue;
                    long sequence = number(part[1]), uptime = number(part[2]);
                    long a = number(part[4]), b = number(part[5]);
                    if (sequence < 0 || uptime < 0 || a < 0 || b < 0 || !seen.add(sequence)) continue;
                    if (part[3].startsWith("RX_") || part[3].startsWith("TX_")) continue;
                    rows.add(event(uptime, part[3], a, b));
                    if (rows.size() > MAX_LOG_LINES) rows.remove(0);
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator >= 0) {
                    String key = line.substring(0, separator), value = line.substring(separator + 1);
                    if ("STATE".equals(key) && code(value)) result.state = value;
                    else if ("DETAIL".equals(key) && code(value)) result.detail = value;
                    else if ("CLIENT".equals(key)) {
                        if ("CONNECTED".equals(value) || "WAITING".equals(value)
                            || "UNKNOWN".equals(value) || "STOPPED".equals(value)) result.client = value;
                    } else if ("RX_BYTES".equals(key)) result.rxBytes = number(value);
                    else if ("TX_BYTES".equals(key)) result.txBytes = number(value);
                    else if ("RX_PACKETS".equals(key)) result.rxPackets = number(value);
                    else if ("TX_PACKETS".equals(key)) result.txPackets = number(value);
                } else if (result.notice.length() == 0 && safeNotice(line)) {
                    result.notice = line.length() <= 160 ? line : line.substring(0, 160);
                }
            }
            StringBuilder log = new StringBuilder();
            for (String row : rows) { if (log.length() != 0) log.append('\n'); log.append(row); }
            result.log = log.toString();
            return result;
        }

        private static boolean code(String text) { return text.matches("[A-Z][A-Z0-9_]{0,63}"); }
        private static long number(String text) {
            if (!text.matches("[0-9]{1,19}")) return -1;
            try { return Long.parseLong(text); } catch (NumberFormatException invalid) { return -1; }
        }
        private static boolean safeNotice(String line) {
            String lower = line.toLowerCase(java.util.Locale.US);
            return line.length() != 0 && !line.matches(".*[\\p{Cntrl}].*")
                && !lower.contains("password") && !lower.contains("passphrase") && !lower.contains("ssid")
                && !lower.contains("psk") && !lower.contains("token")
                && !line.contains("비밀번호") && !line.contains("암호");
        }
        private static String event(long uptime, String type, long a, long b) {
            long seconds = uptime / 1000;
            String time = String.format(java.util.Locale.US, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
            String message = type;
            if ("LISTENING".equals(type)) message = "ADB 실행 · PC 연결 대기";
            else if ("AUTHENTICATED".equals(type)) message = "PC 연결됨 · 인증 완료";
            else if ("DISCONNECTED".equals(type)) message = "PC 연결 종료";
            else if ("AUTH_REJECTED".equals(type)) message = "인증 거부 · " + count(a) + "회";
            else if ("SHELL_OPEN".equals(type)) message = "셸 채널 열림 · " + count(a);
            else if ("SHELL_CLOSE".equals(type)) message = "셸 채널 닫힘 · " + count(a);
            else if ("EXEC_OPEN".equals(type)) message = "명령 채널 열림 · " + count(a);
            else if ("EXEC_CLOSE".equals(type)) message = "명령 채널 닫힘 · " + count(a);
            else if ("SYNC_OPEN".equals(type)) message = "파일 전송 채널 열림 · " + count(a);
            else if ("SYNC_CLOSE".equals(type)) message = "파일 전송 채널 닫힘 · " + count(a);
            else if ("EVENTS_DROPPED".equals(type)) message = "기록 제한으로 생략 · " + count(a) + "건";
            else if ("ROLLOVER".equals(type)) message = "기록 용량 한도 · 새 기록 시작";
            else if (type.matches("(RX|TX)_(CNXN|AUTH|OPEN|OKAY|CLSE|WRTE|OTHER)")) {
                message = (type.startsWith("RX_") ? "수신 RX " : "송신 TX ")
                    + type.substring(3) + " · " + count(a) + " 패킷 · " + bytes(b);
            }
            return "[" + time + "] " + message;
        }
    }

    private void close(String reason) {
        if (closed) return;
        closed=true;
        dismissWifiInfo();
        if(handler!=null)handler.removeCallbacks(poll);
        finish();
    }
}
