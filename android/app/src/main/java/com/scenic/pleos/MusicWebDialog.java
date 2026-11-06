package com.scenic.pleos;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayInputStream;

/** Visible, app-contained music window. The caller must close it on pause or a vehicle restriction. */
@SuppressWarnings("deprecation")
public final class MusicWebDialog {
    private static final int INK = 0xff17191c, MUTED = 0xff656970, BG = 0xfff5f5f5;
    private static final int ACCENT = 0xff17191c, SOFT = 0xffecedef;
    private final Activity activity;
    private final String initialUrl;
    private final Runnable onDismiss;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private WebView web;
    private LinearLayout root, controls;
    private View activityDecor;
    private FrameLayout page;
    private ScrollView errorPanel;
    private TextView title, address, note, status, errorText;
    private ProgressBar progress;
    private Button back, sizeButton, closeButton;
    private int expandedWebWidth, expandedWebHeight;
    private float expandedPageScale = 1f, currentPageScale = Float.NaN;
    private int zoomPasses;
    private boolean restorePageScale;
    private volatile boolean closed;
    private boolean failed, notified, minimized;
    private final ViewTreeObserver.OnGlobalLayoutListener activityLayout = this::relayout;
    private final Runnable timeout = () -> showError("웹사이트 응답이 늦어지고 있어요. 네트워크를 확인하고 다시 시도해 주세요.");
    private final Runnable viewportZoom = this::applyViewportZoom;

    public MusicWebDialog(Activity activity, String playlistUrl, Runnable onDismiss) {
        if (!YouTubeMusicLauncher.isPlaylistUrl(playlistUrl)) throw new IllegalArgumentException("선택한 플레이리스트 주소를 확인해 주세요.");
        this.activity = activity;
        this.initialUrl = playlistUrl;
        this.onDismiss = onDismiss;
    }

    public void show() {
        // A single instance owns one window and WebView for its whole lifetime.
        if (closed || dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Material_Light_Dialog_NoActionBar) {
            @Override public void onBackPressed() { handleBack(); }
        };
        dialog.setCanceledOnTouchOutside(false);
        root = column();
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.setBackgroundColor(BG);
        LinearLayout heading = row();
        title = text("YouTube Music · 웹", 20, INK);
        title.setTypeface(ScenicTypography.font(activity,600));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        sizeButton = button("작게 보기", () -> { if (minimized) expand(); else minimize(); });
        closeButton = button("재생 종료", this::close);
        heading.addView(sizeButton);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-2, -2);
        closeParams.setMarginStart(dp(8));
        heading.addView(closeButton, closeParams);
        root.addView(heading);
        address = text("https://music.youtube.com", 14, MUTED);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(address);
        controls = row();
        back = button("뒤로", this::goBack);
        addControl(controls, back, false);
        Button selected = button("선택한 목록", () -> navigate(initialUrl));
        selected.setContentDescription("AI가 선택한 플레이리스트로 돌아가기");
        addControl(controls, selected, true);
        addControl(controls, button("새로고침", () -> navigate(initialUrl)), true);
        root.addView(controls);
        note = text("정차 중 사용 · 로그인은 외부 앱에서", 14, MUTED);
        root.addView(note);
        status = text("공식 웹사이트에 연결 중…", 14, MUTED);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(status);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        page = new FrameLayout(activity);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        errorPanel = new ScrollView(activity);
        errorPanel.setFillViewport(true);
        errorPanel.setBackgroundColor(Color.WHITE);
        LinearLayout errorContent = column();
        errorContent.setGravity(Gravity.CENTER);
        errorContent.setPadding(dp(32), dp(24), dp(32), dp(24));
        errorPanel.addView(errorContent, new ScrollView.LayoutParams(-1, -2));
        errorText = text("", 20, INK);
        errorText.setGravity(Gravity.CENTER);
        errorContent.addView(errorText);
        TextView limitation = text("웹사이트 표시와 재생 가능 여부는 네트워크와 YouTube 정책에 따라 달라질 수 있어요.", 14, MUTED);
        limitation.setGravity(Gravity.CENTER);
        limitation.setPadding(0, dp(16), 0, dp(16));
        errorContent.addView(limitation);
        errorContent.addView(button("다시 시도", () -> navigate(initialUrl)));
        page.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
        errorPanel.setVisibility(View.GONE);
        dialog.setContentView(root);
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) handleBack();
            return true;
        });
        dialog.setOnDismissListener(d -> finishClose());
        try {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                window.setDimAmount(0);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                window.setStatusBarColor(BG);
                window.setNavigationBarColor(BG);
            }
            activityDecor = activity.getWindow().getDecorView();
            activityDecor.getViewTreeObserver().addOnGlobalLayoutListener(activityLayout);
            updatePresentation();
            relayout();
            activityDecor.post(this::relayout);
            navigate(initialUrl);
        } catch (RuntimeException failure) { close(); throw failure; }
    }

    public boolean isShowing() { return !closed && dialog != null && dialog.isShowing(); }

    public boolean isMinimized() { return isShowing() && minimized; }

    public boolean matchesPlaylist(String playlistUrl) { return initialUrl.equals(playlistUrl); }

    private void handleBack() {
        if (!isShowing()) return;
        if (minimized) activity.onBackPressed();
        else minimize();
    }

    /** Resize the visible browser and ask it to zoom out; never reload or pause media. */
    public void minimize() {
        if (!isShowing() || minimized) return;
        if (!restorePageScale) rememberExpandedViewport();
        restorePageScale = false;
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null && web != null) input.hideSoftInputFromWindow(web.getWindowToken(), 0);
        minimized = true;
        updatePresentation();
        relayout();
        requestViewportZoom();
    }

    public void expand() {
        if (!isShowing()) return;
        if (minimized) restorePageScale = true;
        minimized = false;
        updatePresentation();
        relayout();
        if (restorePageScale) requestViewportZoom();
    }

    private void rememberExpandedViewport() {
        int width = web != null && web.getWidth() > 0 ? web.getWidth() : page.getWidth();
        int height = web != null && web.getHeight() > 0 ? web.getHeight() : page.getHeight();
        if (width > 0 && height > 0) {
            expandedWebWidth = width;
            expandedWebHeight = height;
        }
        expandedPageScale = readPageScale();
    }

    private float readPageScale() {
        if (currentPageScale > 0 && !Float.isInfinite(currentPageScale)) return currentPageScale;
        float scale = web == null ? 1f : web.getScale();
        return scale > 0 && !Float.isInfinite(scale) ? scale : 1f;
    }

    private void requestViewportZoom() {
        if (closed || web == null || (!minimized && !restorePageScale)) return;
        main.removeCallbacks(viewportZoom);
        zoomPasses = 2;
        main.post(viewportZoom);
    }

    private void applyViewportZoom() {
        if (closed || web == null || zoomPasses <= 0 || (!minimized && !restorePageScale)) return;
        // The WebView itself remains MATCH_PARENT, so Chromium sees its real window bounds.
        // A changed-size layout callback will reschedule if this request preceded layout.
        if (web.getWidth() <= 0 || web.getHeight() <= 0 || web.isLayoutRequested() || root.isLayoutRequested()) return;
        float targetScale = expandedPageScale;
        if (minimized && expandedWebWidth > 0 && expandedWebHeight > 0) {
            float fit = Math.min(1f, Math.min((float) web.getWidth() / expandedWebWidth,
                    (float) web.getHeight() / expandedWebHeight));
            targetScale *= fit;
        }
        float factor = Math.max(.01f, Math.min(100f, targetScale / readPageScale()));
        zoomPasses--;
        if (Math.abs(factor - 1f) > .02f) web.zoomBy(factor);
        // One settling pass covers Chromium's asynchronous resize/scale report. Site zoom
        // limits may clamp the result; never keep zooming to force it past those limits.
        if (zoomPasses > 0) main.postDelayed(viewportZoom, 120);
        else restorePageScale = false;
    }

    /** Call after activity configuration/layout changes; the page and playback stay intact. */
    public void relayout() {
        if (!isShowing() || activityDecor == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        int width = activityDecor.getWidth(), height = activityDecor.getHeight();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int safeWidth = metrics.getBounds().width() - insets.left - insets.right;
            int safeHeight = metrics.getBounds().height() - insets.top - insets.bottom;
            width = width > 0 ? Math.min(width, safeWidth) : safeWidth;
            height = height > 0 ? Math.min(height, safeHeight) : safeHeight;
        }
        Rect visible = new Rect();
        activityDecor.getWindowVisibleDisplayFrame(visible);
        if (visible.width() > 0) width = width > 0 ? Math.min(width, visible.width()) : visible.width();
        if (visible.height() > 0) height = height > 0 ? Math.min(height, visible.height()) : visible.height();
        if (width <= 0 || height <= 0) return; // The post/layout listener retries once measured.
        MusicWindowLayout layout = MusicWindowLayout.calculate(width, height,
                activity.getResources().getDisplayMetrics().density, minimized);
        WindowManager.LayoutParams params = window.getAttributes();
        int gravity = minimized ? Gravity.BOTTOM | Gravity.RIGHT : Gravity.CENTER;
        int offset = minimized ? layout.margin : 0;
        if (params.width == layout.width && params.height == layout.height
                && params.gravity == gravity && params.x == offset && params.y == offset) return;
        params.width = layout.width;
        params.height = layout.height;
        params.gravity = gravity;
        params.x = offset;
        params.y = offset;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            params.setFitInsetsIgnoringVisibility(true);
        }
        window.setAttributes(params);
    }

    private void updatePresentation() {
        int metadataVisibility = minimized ? View.GONE : View.VISIBLE;
        address.setVisibility(metadataVisibility);
        controls.setVisibility(metadataVisibility);
        note.setVisibility(metadataVisibility);
        status.setVisibility(metadataVisibility);
        root.setPadding(dp(minimized ? 8 : 16), dp(8), dp(minimized ? 8 : 16), dp(8));
        title.setText(minimized ? "YouTube Music" : "YouTube Music · 웹");
        title.setTextSize(minimized ? 14 : 20);
        sizeButton.setText(minimized ? "크게 보기" : "작게 보기");
        sizeButton.setContentDescription(minimized ? "음악 창 크게 보기" : "음악을 재생하며 SCENIC 둘러보기");
        closeButton.setText(minimized ? "종료" : "재생 종료");
        closeButton.setContentDescription("음악 재생 종료");
        for (Button button : new Button[] {sizeButton, closeButton}) {
            button.setTextSize(minimized ? 14 : 16);
            button.setMinHeight(dp(minimized ? 48 : 56));
            button.setMinimumHeight(dp(minimized ? 48 : 56));
            button.setPadding(dp(minimized ? 8 : 12), dp(8), dp(minimized ? 8 : 12), dp(8));
        }
    }

    public void close() {
        if (closed) return;
        // Stop web content before dismissing the window, including on a vehicle-state change.
        finishClose();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void finishClose() {
        if (!closed) {
            closed = true;
            main.removeCallbacksAndMessages(null);
            if (activityDecor != null && activityDecor.getViewTreeObserver().isAlive())
                activityDecor.getViewTreeObserver().removeOnGlobalLayoutListener(activityLayout);
            destroyWeb();
        }
        if (!notified) {
            notified = true;
            if (onDismiss != null) onDismiss.run();
        }
    }

    private void createWeb() {
        web = new WebView(activity);
        currentPageScale = Float.NaN;
        web.setBackgroundColor(Color.WHITE);
        web.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (view == web && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) requestViewportZoom();
        });
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true); // onCreateWindow rejects every popup.
        settings.setGeolocationEnabled(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        // Keep the real WebView UA. No authentication workarounds or native JS interface.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setDownloadListener((url, ua, disposition, mime, length) -> notice("이 화면에서는 파일을 다운로드하지 않습니다."));
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (!closed && view == web && !failed) progress.setProgress(value);
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                notice("새 창은 열 수 없어요. 로그인은 외부 앱이나 브라우저를 이용해 주세요."); return false;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                callback.onReceiveValue(null); notice("이 화면에서는 기기 파일을 업로드하지 않습니다."); return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onScaleChanged(WebView view, float oldScale, float newScale) {
                // Observe only: scheduling another zoom here could loop against site limits.
                if (!closed && view == web && newScale > 0 && !Float.isInfinite(newScale)) currentPageScale = newScale;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (closed) return true;
                if (request.isForMainFrame()) return blockNavigation(request.getUrl().toString());
                return !MusicWebPolicy.allowsResource(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return closed || blockNavigation(url); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                boolean blocked = closed || !MusicWebPolicy.allowsResource(url)
                        || (request.isForMainFrame() && !MusicWebPolicy.allowsNavigation(url));
                if (!blocked) return null;
                if (!closed && request.isForMainFrame()) main.post(() -> navigationNotice(url));
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (closed || view != web) return;
                if (!MusicWebPolicy.allowsNavigation(url)) {
                    view.stopLoading();
                    showError(MusicWebPolicy.isAuthentication(url) ? loginNotice() : "공식 음악 웹사이트 밖으로 이동할 수 없습니다.");
                    return;
                }
                address.setText("https://" + MusicWebPolicy.secureHost(url));
                failed = false;
                errorPanel.setVisibility(View.GONE);
                view.setVisibility(View.VISIBLE);
                progress.setVisibility(View.VISIBLE);
                notice("공식 웹사이트에 연결 중…");
                main.removeCallbacks(timeout);
                main.postDelayed(timeout, 30000);
                updateBack();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (closed || view != web || failed || !MusicWebPolicy.allowsNavigation(url)) return;
                main.removeCallbacks(timeout);
                progress.setVisibility(View.INVISIBLE);
                notice("선택한 플레이리스트에서 재생을 눌러 주세요. 로그인과 재생은 웹사이트에서 제한될 수 있어요.");
                updateBack();
                if (minimized) requestViewportZoom();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == web && request.isForMainFrame()) showError("음악 웹사이트에 연결하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view == web && request.isForMainFrame()) showError("음악 웹사이트에서 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (view == web) showError("웹사이트의 보안 연결을 확인하지 못해 열기를 중단했습니다.");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == web) {
                    // The dead renderer cannot handle stop/pause; only detach and destroy it.
                    web = null;
                    if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                    view.destroy();
                    showError("웹 화면이 종료됐어요. 다시 시도하면 새로 열 수 있습니다.");
                }
                return true;
            }
        });
        page.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private void navigate(String url) {
        if (closed) return;
        if (!MusicWebPolicy.allowsNavigation(url)) url = initialUrl;
        failed = false;
        try {
            if (web == null) createWeb();
            errorPanel.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            web.onResume();
            web.stopLoading();
            web.loadUrl(url);
            notice("공식 웹사이트에 연결 중…");
            progress.setProgress(0);
            progress.setVisibility(View.VISIBLE);
            main.removeCallbacks(timeout);
            main.postDelayed(timeout, 30000);
        } catch (RuntimeException unavailable) {
            destroyWeb();
            showError("이 기기에서 웹 화면을 시작할 수 없어요. 시스템 WebView 지원을 확인해 주세요.");
        }
    }

    private boolean blockNavigation(String url) {
        if (MusicWebPolicy.allowsNavigation(url)) return false;
        navigationNotice(url);
        return true;
    }
    private void navigationNotice(String url) {
        notice(MusicWebPolicy.isAuthentication(url) ? loginNotice() : "이 화면에서는 공식 음악 웹사이트만 열 수 있어요.");
    }
    private String loginNotice() { return "Google 로그인은 외부 YouTube Music 앱이나 지원 브라우저에서 진행해 주세요. 이 웹 화면에서는 로그인할 수 없습니다."; }
    private void notice(String message) { if (!closed && status != null) status.setText(message); }
    private void showError(String message) {
        if (closed || errorPanel == null) return;
        failed = true;
        main.removeCallbacks(timeout);
        // A hidden paused WebView may still have media; release it before showing the error.
        destroyWeb();
        progress.setVisibility(View.INVISIBLE);
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
        notice("웹사이트를 열지 못했습니다.");
        updateBack();
    }
    private void goBack() {
        if (closed || web == null || !web.canGoBack()) return;
        failed = false;
        errorPanel.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.onResume();
        web.goBack();
    }
    private void updateBack() { if (back != null) back.setEnabled(web != null && web.canGoBack()); }
    private void destroyWeb() {
        main.removeCallbacks(viewportZoom);
        zoomPasses = 0;
        restorePageScale = false;
        currentPageScale = Float.NaN;
        WebView old = web;
        web = null;
        if (old == null) return;
        // onPause alone does not reliably stop media; destruction releases the page too.
        old.stopLoading();
        old.onPause();
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        old.setWebChromeClient(null);
        old.setWebViewClient(new WebViewClient());
        old.destroy();
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String message, int size, int color) {
        TextView v = new TextView(activity); v.setTypeface(ScenicTypography.font(activity,400)); v.setText(message); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(4), 0, dp(4)); return v;
    }
    private Button button(String label, Runnable action) {
        Button v = new Button(activity);
        v.setText(label); v.setTextSize(16); v.setAllCaps(false);
        v.setMinWidth(0); v.setMinimumWidth(0); v.setMinHeight(dp(56));
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        v.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,ACCENT}));
        GradientDrawable surface = new GradientDrawable(); surface.setColor(SOFT); surface.setCornerRadius(dp(8));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1817191c), surface, null));
        v.setTypeface(ScenicTypography.font(activity,600));
        v.setOnClickListener(clicked -> action.run()); return v;
    }
    private void addControl(LinearLayout row, Button button, boolean spaced) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (spaced) params.setMarginStart(dp(8));
        row.addView(button, params);
    }
}
