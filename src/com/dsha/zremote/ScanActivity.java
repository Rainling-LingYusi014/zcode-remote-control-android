package com.dsha.zremote;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 扫码配对：相机对准桌面端 ZCode 显示的配对二维码，识别成功即返回链接。
 * 解码用 ZXing core（本项目唯一第三方库，Apache-2.0）；相机用旧版 API——
 * 零框架原则下代码量最小，API 29+ 仍完整可用。
 */
public class ScanActivity extends Activity {

    static final String EXTRA_URL = "url";

    private SurfaceView mPreview;
    private Camera mCamera;
    private MultiFormatReader mReader;
    private HandlerThread mDecodeThread;
    private Handler mDecodeHandler;
    private volatile boolean mDone = false;
    private int mScreenW;
    private int mScreenH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission("android.permission.CAMERA")
                != PackageManager.PERMISSION_GRANTED) {
            toast("缺少相机权限");
            finish();
            return;
        }
        getWindow().setStatusBarColor(0xFF0C1013);
        getWindow().setNavigationBarColor(0xFF0C1013);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0C1013);
        mPreview = new SurfaceView(this);
        root.addView(mPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new FrameOverlay(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView cancel = new TextView(this);
        cancel.setText("✕ 取消");
        cancel.setTextColor(0xFFCFD8DC);
        cancel.setTextSize(15);
        cancel.setPadding((int) dp(24), (int) dp(18), (int) dp(24), (int) dp(12));
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);

        mReader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        mReader.setHints(hints);

        mDecodeThread = new HandlerThread("zrc-scan-decode");
        mDecodeThread.start();
        mDecodeHandler = new Handler(mDecodeThread.getLooper());

        SurfaceHolder holder = mPreview.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                openCamera(h);
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                releaseCamera();
            }
        });
    }

    private void openCamera(SurfaceHolder holder) {
        try {
            mCamera = Camera.open();
        } catch (Exception e) {
            toast("相机被占用或不可用");
            finish();
            return;
        }
        try {
            android.view.View rv = mPreview.getRootView();
            mScreenW = rv.getWidth() > 0 ? rv.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            mScreenH = rv.getHeight() > 0 ? rv.getHeight()
                    : getResources().getDisplayMetrics().heightPixels;

            Camera.Parameters p = mCamera.getParameters();
            Camera.Size best = pickPreviewSize(p);
            if (best != null) p.setPreviewSize(best.width, best.height);
            mCamera.setParameters(p);
            mCamera.setDisplayOrientation(displayOrientation());
            mCamera.setPreviewDisplay(holder);

            final Camera.Size sz = mCamera.getParameters().getPreviewSize();
            mCamera.setPreviewCallbackWithBuffer((data, cam) -> {
                if (mDone) return;
                mDecodeHandler.post(() -> decode(data, sz.width, sz.height));
            });
            int bufSize = sz.width * sz.height * 3 / 2;
            for (int i = 0; i < 3; i++) mCamera.addCallbackBuffer(new byte[bufSize]);
            mCamera.startPreview();
        } catch (Exception e) {
            toast("相机启动失败");
            releaseCamera();
            finish();
        }
    }

    /** 预览分辨率：宽高比最接近屏幕、且宽度 ≥640 的档位（预览会被拉伸铺满，比例越近变形越小）。 */
    private Camera.Size pickPreviewSize(Camera.Parameters p) {
        if (mScreenW <= 0 || mScreenH <= 0) return null;
        float target = (float) mScreenW / mScreenH;
        Camera.Size best = null;
        float bestDiff = Float.MAX_VALUE;
        for (Camera.Size s : p.getSupportedPreviewSizes()) {
            if (s.width < 640) continue;
            float diff = Math.abs((float) s.width / s.height - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private int displayOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90
                : rotation == Surface.ROTATION_180 ? 180
                : rotation == Surface.ROTATION_270 ? 270 : 0;
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(0, info);
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    /** 视觉取景框（屏幕中心 min 边 65%，与 FrameOverlay 一致）映射回相机帧坐标：两轴分别按拉伸比例换算。 */
    private int[] cropRect(int fw, int fh) {
        float side = Math.min(mScreenW, mScreenH) * 0.65f;
        int cw, ch;
        if (displayOrientation() % 180 == 90) {
            cw = Math.round(side * fw / mScreenH);
            ch = Math.round(side * fh / mScreenW);
        } else {
            cw = Math.round(side * fw / mScreenW);
            ch = Math.round(side * fh / mScreenH);
        }
        cw = Math.max(16, Math.min(cw, fw));
        ch = Math.max(16, Math.min(ch, fh));
        return new int[]{(fw - cw) / 2, (fh - ch) / 2, cw, ch};
    }

    private void decode(byte[] data, int w, int h) {
        try {
            if (!mDone) {
                int[] r = cropRect(w, h);
                PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                        data, w, h, r[0], r[1], r[2], r[3], false);
                com.google.zxing.Result res =
                        mReader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
                onFound(res.getText());
            }
        } catch (Exception ignored) {
            // NotFoundException / 校验失败：取景框里暂无可识别二维码，继续扫
        } finally {
            mReader.reset();
            try {
                if (mCamera != null && !mDone) mCamera.addCallbackBuffer(data);
            } catch (Exception ignored) {
            }
        }
    }

    private void onFound(String text) {
        if (text == null) return;
        text = text.trim();
        Uri u = Uri.parse(text);
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        String host = u.getHost() == null ? "" : u.getHost();
        boolean pairing = ("http".equals(scheme) || "https".equals(scheme))
                && PanelController.ALLOWED_HOST.equals(host);
        if (!pairing) {
            runOnUiThread(() -> toast("不是 ZCode 配对链接，继续扫描"));
            return;
        }
        mDone = true;
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
        Intent out = new Intent();
        out.putExtra(EXTRA_URL, text);
        setResult(RESULT_OK, out);
        finish();
    }

    private void releaseCamera() {
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                mCamera.setPreviewCallbackWithBuffer(null);
            } catch (Exception ignored) {
            }
            try {
                mCamera.release();
            } catch (Exception ignored) {
            }
            mCamera = null;
        }
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        if (mDecodeThread != null) {
            mDecodeThread.quitSafely();
            mDecodeThread = null;
        }
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 取景框：中央方框描边 + 框外压暗 + 提示文字；框比例与解码裁剪区一致（min 边 65%）。 */
    private static final class FrameOverlay extends android.view.View {
        private final Paint mLine = new Paint();
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

        FrameOverlay(android.content.Context c) {
            super(c);
            mLine.setColor(0xFFE8EDF1);
            mLine.setStyle(Paint.Style.STROKE);
            mLine.setStrokeWidth(dip(2.5f));
            mLine.setAntiAlias(true);
            mText.setColor(0xFFB9C4CB);
            mText.setTextSize(dip(14));
            mText.setTextAlign(Paint.Align.CENTER);
        }

        private float dip(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float side = Math.min(w, h) * 0.65f;
            RectF r = new RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
            c.save();
            c.clipOutRect(r); // 框外压暗
            c.drawColor(0x9E000000);
            c.restore();
            c.drawRoundRect(r, dip(14), dip(14), mLine);
            c.drawText("对准桌面端 ZCode 显示的配对二维码", w / 2f, r.bottom + dip(34), mText);
        }
    }
}
