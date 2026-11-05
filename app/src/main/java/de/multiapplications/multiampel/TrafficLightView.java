package de.multiapplications.multiampel;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class TrafficLightView extends View {

    private static final int OFF_COLOR = 0xFF050505;
    private static final int BORDER_COLOR = 0x9A1A1A1A;
    private static final float GLOW_SCALE = 1.6f;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator evaluator = new ArgbEvaluator();
    private final float borderWidth;

    private int lightColor = Color.RED;
    private float amount;
    private ValueAnimator animator;
    private Shader glowShader;
    private Shader highlightShader;

    public TrafficLightView(Context context) {
        this(context, null);
    }

    public TrafficLightView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TrafficLightView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        borderWidth = getResources().getDisplayMetrics().density;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidth);
        borderPaint.setColor(BORDER_COLOR);
    }

    public void setLightColor(int color) {
        lightColor = color;
        glowShader = null;
        highlightShader = null;
        invalidate();
    }

    public void setOn(boolean on) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(amount, on ? 1f : 0f);
        animator.setDuration(200L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            amount = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb((int) (alpha * 255f), Color.red(color), Color.green(color), Color.blue(color));
    }

    private void buildShaders(float radius) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        glowShader = new RadialGradient(
                cx,
                cy,
                radius * GLOW_SCALE,
                new int[]{
                        withAlpha(lightColor, 0.40f),
                        withAlpha(lightColor, 0.18f),
                        withAlpha(lightColor, 0.06f),
                        withAlpha(lightColor, 0f)
                },
                new float[]{0.52f, 0.70f, 0.86f, 1f},
                Shader.TileMode.CLAMP);
        highlightShader = new RadialGradient(
                cx - radius * 0.3f,
                cy - radius * 0.45f,
                radius * 1.15f,
                new int[]{
                        withAlpha(Color.WHITE, 0.42f),
                        withAlpha(Color.WHITE, 0f)
                },
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        glowShader = null;
        highlightShader = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = Math.min(getWidth(), getHeight()) / 2f - borderWidth / 2f;
        if (radius <= 0f) {
            return;
        }
        if (glowShader == null || highlightShader == null) {
            buildShaders(radius);
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        if (amount > 0.001f) {
            glowPaint.setShader(glowShader);
            glowPaint.setAlpha((int) (255f * amount));
            canvas.drawCircle(cx, cy, radius * GLOW_SCALE, glowPaint);
        }

        lightPaint.setColor((int) evaluator.evaluate(amount, OFF_COLOR, lightColor));
        canvas.drawCircle(cx, cy, radius, lightPaint);

        if (amount > 0.001f) {
            highlightPaint.setShader(highlightShader);
            highlightPaint.setAlpha((int) (255f * amount));
            canvas.drawCircle(cx, cy, radius, highlightPaint);
        }

        canvas.drawCircle(cx, cy, radius, borderPaint);
    }
}