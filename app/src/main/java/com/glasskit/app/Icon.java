package com.glasskit.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Ikon ringkas gaya SF Symbols, dilukis sendiri (grid 24x24). */
class Icon extends View {
    static final int PLAY = 1, PAUSE = 2, NEXT = 3, PREV = 4, WIFI = 5, BT = 6, CELL = 7, PLANE = 8,
            TORCH = 9, MOON = 10, SUN = 11, SPEAKER = 12, DARK = 13, ROTATE = 14, BATTERY = 15,
            SHOT = 16, LOCK = 17, CAMERA = 18, BELL = 19, CLOSE = 20, NOTE = 21, GEAR = 22,
            PIN = 23, NFC = 24, BOLT = 25, UNLOCK = 26, PHONE = 27;

    private int kind;
    private final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p = new Path();

    Icon(Context c, int kind, int color) {
        super(c);
        this.kind = kind;
        f.setStyle(Paint.Style.FILL);
        f.setPathEffect(new CornerPathEffect(1.6f));
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
        s.setStrokeWidth(2f);
        setColor(color);
    }

    void setKind(int k) {
        if (k != kind) { kind = k; invalidate(); }
    }

    void setColor(int c) {
        f.setColor(c);
        s.setColor(c);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float sc = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * sc) / 2f, (getHeight() - 24 * sc) / 2f);
        c.scale(sc, sc);
        p.reset();
        switch (kind) {
            case PLAY:
                p.moveTo(7, 4.5f); p.lineTo(19.5f, 12); p.lineTo(7, 19.5f); p.close();
                c.drawPath(p, f);
                break;
            case PAUSE:
                c.drawRoundRect(5.5f, 4.5f, 10, 19.5f, 1.5f, 1.5f, f);
                c.drawRoundRect(14, 4.5f, 18.5f, 19.5f, 1.5f, 1.5f, f);
                break;
            case NEXT:
                p.moveTo(2.5f, 6); p.lineTo(12, 12); p.lineTo(2.5f, 18); p.close();
                p.moveTo(12, 6); p.lineTo(21.5f, 12); p.lineTo(12, 18); p.close();
                c.drawPath(p, f);
                break;
            case PREV:
                p.moveTo(21.5f, 6); p.lineTo(12, 12); p.lineTo(21.5f, 18); p.close();
                p.moveTo(12, 6); p.lineTo(2.5f, 12); p.lineTo(12, 18); p.close();
                c.drawPath(p, f);
                break;
            case WIFI:
                s.setStrokeWidth(2.4f);
                c.drawCircle(12, 18.5f, 1.8f, f);
                c.drawArc(new RectF(7, 11.5f, 17, 21.5f), 225, 90, false, s);
                c.drawArc(new RectF(2.5f, 7, 21.5f, 26), 225, 90, false, s);
                s.setStrokeWidth(2f);
                break;
            case BT:
                p.moveTo(6.5f, 7.5f); p.lineTo(17, 16.5f); p.lineTo(12, 21); p.lineTo(12, 3);
                p.lineTo(17, 7.5f); p.lineTo(6.5f, 16.5f);
                c.drawPath(p, s);
                break;
            case CELL:
                c.drawRoundRect(3, 15, 6, 20, 1, 1, f);
                c.drawRoundRect(8, 12, 11, 20, 1, 1, f);
                c.drawRoundRect(13, 8, 16, 20, 1, 1, f);
                c.drawRoundRect(18, 4, 21, 20, 1, 1, f);
                break;
            case PLANE:
                p.moveTo(12, 2.5f); p.lineTo(13.4f, 9); p.lineTo(21.5f, 13.5f); p.lineTo(21.5f, 15);
                p.lineTo(13.4f, 13); p.lineTo(13, 18.5f); p.lineTo(15.5f, 20.5f); p.lineTo(15.5f, 21.5f);
                p.lineTo(12, 20.5f); p.lineTo(8.5f, 21.5f); p.lineTo(8.5f, 20.5f); p.lineTo(11, 18.5f);
                p.lineTo(10.6f, 13); p.lineTo(2.5f, 15); p.lineTo(2.5f, 13.5f); p.lineTo(10.6f, 9); p.close();
                c.drawPath(p, f);
                break;
            case TORCH:
                p.moveTo(7, 3); p.lineTo(17, 3); p.lineTo(17, 6); p.lineTo(15, 10); p.lineTo(15, 21);
                p.lineTo(9, 21); p.lineTo(9, 10); p.lineTo(7, 6); p.close();
                c.drawPath(p, s);
                c.drawCircle(12, 13.5f, 1.3f, f);
                break;
            case MOON: {
                Path a = new Path();
                a.addCircle(11, 13, 8.5f, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(17, 7.5f, 7.5f, Path.Direction.CW);
                a.op(b, Path.Op.DIFFERENCE);
                c.drawPath(a, f);
                break;
            }
            case SUN:
                c.drawCircle(12, 12, 4.2f, f);
                for (int i = 0; i < 8; i++) {
                    double an = Math.PI / 4 * i;
                    c.drawLine((float) (12 + 7 * Math.cos(an)), (float) (12 + 7 * Math.sin(an)),
                            (float) (12 + 9.5 * Math.cos(an)), (float) (12 + 9.5 * Math.sin(an)), s);
                }
                break;
            case SPEAKER:
                p.moveTo(3, 9); p.lineTo(7, 9); p.lineTo(12, 4.5f); p.lineTo(12, 19.5f); p.lineTo(7, 15);
                p.lineTo(3, 15); p.close();
                c.drawPath(p, f);
                c.drawArc(new RectF(10.5f, 8, 18.5f, 16), -50, 100, false, s);
                c.drawArc(new RectF(9, 4.5f, 22, 19.5f), -50, 100, false, s);
                break;
            case DARK:
                c.drawCircle(12, 12, 8.5f, s);
                c.drawArc(new RectF(3.5f, 3.5f, 20.5f, 20.5f), 90, 180, true, f);
                break;
            case ROTATE:
                c.drawArc(new RectF(3.5f, 3.5f, 20.5f, 20.5f), -60, 300, false, s);
                p.moveTo(17.5f, 2.5f); p.lineTo(17, 6.8f); p.lineTo(21.2f, 7.3f);
                c.drawPath(p, s);
                c.drawRoundRect(9, 11.5f, 15, 16.5f, 1.2f, 1.2f, f);
                c.drawArc(new RectF(10, 8.5f, 14, 13.5f), 180, 180, false, s);
                break;
            case BATTERY:
                c.drawRoundRect(2.5f, 7.5f, 19, 16.5f, 2.5f, 2.5f, s);
                c.drawRoundRect(20.5f, 10.3f, 22, 13.7f, 0.8f, 0.8f, f);
                c.drawRoundRect(4.8f, 9.8f, 12.5f, 14.2f, 1, 1, f);
                break;
            case SHOT:
                p.moveTo(3, 8); p.lineTo(3, 3); p.lineTo(8, 3);
                p.moveTo(16, 3); p.lineTo(21, 3); p.lineTo(21, 8);
                p.moveTo(21, 16); p.lineTo(21, 21); p.lineTo(16, 21);
                p.moveTo(8, 21); p.lineTo(3, 21); p.lineTo(3, 16);
                c.drawPath(p, s);
                c.drawCircle(12, 12, 3, f);
                break;
            case LOCK:
                c.drawRoundRect(5.5f, 10.5f, 18.5f, 21, 2.5f, 2.5f, f);
                p.moveTo(8, 11); p.lineTo(8, 7.5f);
                p.arcTo(new RectF(8, 3.5f, 16, 11.5f), 180, 180, false);
                p.lineTo(16, 11);
                c.drawPath(p, s);
                break;
            case CAMERA:
                p.moveTo(3, 8.5f); p.lineTo(7.5f, 8.5f); p.lineTo(9, 5.5f); p.lineTo(15, 5.5f); p.lineTo(16.5f, 8.5f);
                p.lineTo(21, 8.5f); p.lineTo(21, 19); p.lineTo(3, 19); p.close();
                c.drawPath(p, s);
                c.drawCircle(12, 13.3f, 3.3f, s);
                break;
            case BELL:
                p.moveTo(12, 3.5f);
                p.cubicTo(7.5f, 3.5f, 6.5f, 7.5f, 6.5f, 10.5f);
                p.lineTo(6.5f, 14.5f); p.lineTo(4.5f, 17.5f); p.lineTo(19.5f, 17.5f); p.lineTo(17.5f, 14.5f);
                p.lineTo(17.5f, 10.5f);
                p.cubicTo(17.5f, 7.5f, 16.5f, 3.5f, 12, 3.5f);
                c.drawPath(p, f);
                c.drawCircle(12, 20, 2, f);
                break;
            case CLOSE:
                c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, s);
                c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, s);
                break;
            case NOTE:
                c.drawCircle(8, 17.5f, 3, f);
                c.drawCircle(17, 15.5f, 3, f);
                p.moveTo(10.2f, 17.5f); p.lineTo(10.2f, 5.5f); p.lineTo(19.2f, 3.5f); p.lineTo(19.2f, 15.5f);
                c.drawPath(p, s);
                break;
            case GEAR:
                c.drawCircle(12, 12, 3, s);
                c.drawCircle(12, 12, 7, s);
                for (int i = 0; i < 8; i++) {
                    double an = Math.PI / 4 * i;
                    c.drawLine((float) (12 + 7 * Math.cos(an)), (float) (12 + 7 * Math.sin(an)),
                            (float) (12 + 9.5 * Math.cos(an)), (float) (12 + 9.5 * Math.sin(an)), s);
                }
                break;
            case PHONE:
                p.moveTo(6.6f, 10.8f);
                p.cubicTo(8.0f, 13.6f, 10.4f, 16.0f, 13.2f, 17.4f);
                p.lineTo(15.4f, 15.2f);
                p.cubicTo(15.7f, 14.9f, 16.1f, 14.8f, 16.4f, 15.0f);
                p.cubicTo(17.5f, 15.4f, 18.7f, 15.6f, 20f, 15.6f);
                p.cubicTo(20.6f, 15.6f, 21f, 16f, 21f, 16.6f);
                p.lineTo(21f, 20f);
                p.cubicTo(21f, 20.6f, 20.6f, 21f, 20f, 21f);
                p.cubicTo(10.6f, 21f, 3f, 13.4f, 3f, 4f);
                p.cubicTo(3f, 3.4f, 3.4f, 3f, 4f, 3f);
                p.lineTo(7.5f, 3f);
                p.cubicTo(8.1f, 3f, 8.5f, 3.4f, 8.5f, 4f);
                p.cubicTo(8.5f, 5.2f, 8.7f, 6.4f, 9.1f, 7.6f);
                p.cubicTo(9.2f, 7.9f, 9.1f, 8.3f, 8.8f, 8.6f);
                p.close();
                f.setPathEffect(null);
                c.drawPath(p, f);
                f.setPathEffect(new CornerPathEffect(1.6f));
                break;
            case UNLOCK:
                c.drawRoundRect(5.5f, 10.5f, 18.5f, 21, 2.5f, 2.5f, f);
                p.moveTo(16, 11); p.lineTo(16, 7.5f);
                p.arcTo(new RectF(16, 2.5f, 24, 10.5f), 180, 180, false);
                p.lineTo(24, 8);
                c.drawPath(p, s);
                break;
            case PIN:
                p.moveTo(12, 21.5f);
                p.cubicTo(7, 15.5f, 5, 12.5f, 5, 9.5f);
                p.arcTo(new RectF(5, 2.5f, 19, 16.5f), 180, 180, false);
                p.cubicTo(19, 12.5f, 17, 15.5f, 12, 21.5f);
                p.close();
                p.addCircle(12, 9.5f, 2.6f, Path.Direction.CCW);
                p.setFillType(Path.FillType.EVEN_ODD);
                f.setPathEffect(null);
                c.drawPath(p, f);
                f.setPathEffect(new CornerPathEffect(1.6f));
                break;
            case NFC:
                c.drawRoundRect(4, 4, 20, 20, 4, 4, s);
                p.moveTo(9, 15.5f); p.lineTo(9, 8.5f); p.lineTo(15, 15.5f); p.lineTo(15, 8.5f);
                c.drawPath(p, s);
                break;
            case BOLT:
                p.moveTo(13.5f, 2.5f); p.lineTo(5, 13.5f); p.lineTo(11, 13.5f); p.lineTo(10.5f, 21.5f);
                p.lineTo(19, 10.5f); p.lineTo(13, 10.5f); p.close();
                c.drawPath(p, f);
                break;
            default:
                break;
        }
        c.restore();
    }
}
