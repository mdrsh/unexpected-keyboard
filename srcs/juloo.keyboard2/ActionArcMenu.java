package juloo.keyboard2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

public final class ActionArcMenu
{
  public enum ActionType
  {
    HOME,
    UNDO,
    REDO,
    SELECT_ALL,
    CUT,
    COPY,
    PASTE,
    END,
    LANGUAGE_SWITCH,
    CLIPBOARD
  }

  public static final int INDEX_NONE = -1;
  public static final int INDEX_CENTER = -2;

  public static class Sector
  {
    public final ActionType action;
    public final String label;
    public final float startAngle;
    public final float sweepAngle;
    public final float midAngle;

    public Sector(ActionType action, String label, float startAngle, float sweepAngle)
    {
      this.action = action;
      this.label = label;
      this.startAngle = startAngle;
      this.sweepAngle = sweepAngle;
      this.midAngle = startAngle + sweepAngle / 2f;
    }
  }

  private boolean _isActive = false;
  private float _anchorX = 0f;
  private float _anchorY = 0f;
  private float _centerArcX = 0f;
  private float _centerArcY = 0f;
  private float _centerRadius = 0f;
  private float _sectorInnerRadius = 0f;
  private float _sectorOuterRadius = 0f;
  private int _hoveredIndex = INDEX_NONE;

  private int _viewWidth = 0;
  private int _viewHeight = 0;
  private float _density = 1f;

  private final Paint _dimPaint = new Paint();
  private final Paint _fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _centerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _clipboardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final Path _sectorPath = new Path();
  private final RectF _sectorRectOuter = new RectF();
  private final RectF _sectorRectInner = new RectF();
  private Typeface _keyFont = null;

  private static final float[] DIVIDER_ANGLES = new float[]{
    155f, 180f, 204f, 218f, 250f, 270f, 315f, 360f, 385f
  };

  // 8 action sectors:
  // - Sector 0: Home (<), 155° to 180° (25°)
  // - Sector 1: Undo (Z), 180° to 204° (24°)
  // - Sector 2: Redo (Y), 204° to 218° (14°)
  // - Sector 3: Select All (A), 218° to 250° (32°)
  // - Sector 4: Cut (X), 250° to 270° (20°)
  // - Sector 5: Copy (C), 270° to 315° (45°, equal split)
  // - Sector 6: Paste (V), 315° to 360° (45°, equal split)
  // - Sector 7: End (>), 360° to 385° (25°)
  private final Sector[] _sectors = new Sector[]{
    new Sector(ActionType.HOME, "home", 155f, 25f),
    new Sector(ActionType.UNDO, "Z", 180f, 24f),
    new Sector(ActionType.REDO, "Y", 204f, 14f),
    new Sector(ActionType.SELECT_ALL, "A", 218f, 32f),
    new Sector(ActionType.CUT, "X", 250f, 20f),
    new Sector(ActionType.COPY, "C", 270f, 45f),
    new Sector(ActionType.PASTE, "V", 315f, 45f),
    new Sector(ActionType.END, "end", 360f, 25f)
  };

  public ActionArcMenu()
  {
    _dimPaint.setStyle(Paint.Style.FILL);

    _fillPaint.setStyle(Paint.Style.FILL);
    _glowPaint.setStyle(Paint.Style.FILL);

    _dividerPaint.setStyle(Paint.Style.STROKE);
    _dividerPaint.setStrokeCap(Paint.Cap.ROUND);

    _centerStrokePaint.setStyle(Paint.Style.STROKE);

    _textPaint.setTextAlign(Paint.Align.CENTER);
    _textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

    _clipboardPaint.setTextAlign(Paint.Align.CENTER);
  }

  public boolean isActive()
  {
    return _isActive;
  }

  public void start(float touchX, float touchY, float rowHeight, float centerArcY, boolean hasLanguageSwitch,
                    String nextLangBadge, Context context, int viewWidth, int viewHeight)
  {
    _isActive = true;
    _anchorX = touchX;
    _anchorY = touchY;
    _viewWidth = viewWidth;
    _viewHeight = viewHeight;
    _hoveredIndex = INDEX_CENTER;

    _density = context.getResources().getDisplayMetrics().density;
    if (_density <= 0f)
      _density = 1f;

    _dividerPaint.setStrokeWidth(1f * _density);

    // Center clipboard circle: 31.7dp
    _centerRadius = 31.7f * _density;

    // Visual gap between center circle and sectors: increased 3x (from 8.4dp -> 25.2dp)
    float gapFromCircle = 25.2f * _density;
    _sectorInnerRadius = _centerRadius + gapFromCircle; // ~56.9dp
    // Outer boundary (145dp)
    _sectorOuterRadius = 145f * _density;

    _centerArcX = touchX;
    _centerArcY = centerArcY;

    // Horizontal clamping to keep full semicircle on screen
    _keyFont = Theme.getKeyFont(context);
    float minMargin = 8f * _density;
    if (_centerArcX - _sectorOuterRadius < minMargin)
      _centerArcX = _sectorOuterRadius + minMargin;
    else if (_centerArcX + _sectorOuterRadius > viewWidth - minMargin)
      _centerArcX = viewWidth - _sectorOuterRadius - minMargin;
  }

  public boolean updateTouch(float touchX, float touchY)
  {
    if (!_isActive)
      return false;

    int prevHovered = _hoveredIndex;
    _hoveredIndex = INDEX_NONE;

    float dx = touchX - _centerArcX;
    float dy = touchY - _centerArcY;
    float dist = (float)Math.hypot(dx, dy);

    // 1. Center circle zone (Clipboard Manager):
    // Radius of ~24dp leaves the entire gap and outer circle sensitive to sectors
    if (dist <= _centerRadius * 0.75f)
    {
      _hoveredIndex = INDEX_CENTER;
    }
    else
    {
      // 2. Outside center circle: radial sectors
      double angleRad = Math.atan2(dy, dx);
      float angleDeg = (float)Math.toDegrees(angleRad);
      if (angleDeg < 0f)
        angleDeg += 360f;

      // Normalize angle: 0°..45° becomes 360°..405° for continuous right wing
      float testAngle = (angleDeg < 45f) ? angleDeg + 360f : angleDeg;

      // Angular span from 140° to 400° covers Home (<), 6 commands, and End (>)
      if (testAngle >= 140f && testAngle <= 400f)
      {
        for (int i = 0; i < _sectors.length; i++)
        {
          Sector s = _sectors[i];
          if (testAngle >= s.startAngle && testAngle < s.startAngle + s.sweepAngle)
          {
            _hoveredIndex = i;
            break;
          }
        }
        if (_hoveredIndex == INDEX_NONE)
        {
          if (testAngle < 180f)
            _hoveredIndex = 0;
          else if (testAngle >= 360f)
            _hoveredIndex = 7;
        }
      }
      else
      {
        // Dragging down below 140° or above 400° (back towards spacebar) cancels action
        _hoveredIndex = INDEX_NONE;
      }
    }

    return (_hoveredIndex != prevHovered);
  }

  public ActionType finishTouch(float touchX, float touchY)
  {
    if (!_isActive)
      return null;

    updateTouch(touchX, touchY);
    ActionType action = null;

    if (_hoveredIndex == INDEX_CENTER)
    {
      action = ActionType.CLIPBOARD;
    }
    else if (_hoveredIndex >= 0 && _hoveredIndex < _sectors.length)
    {
      action = _sectors[_hoveredIndex].action;
    }

    _isActive = false;
    _hoveredIndex = INDEX_NONE;
    return action;
  }

  public void cancel()
  {
    _isActive = false;
    _hoveredIndex = INDEX_NONE;
  }

  public void draw(Canvas canvas, Theme theme)
  {
    if (!_isActive)
      return;

    // 1. Resolve colors strictly from theme
    int baseBg = (theme.colorKeyboard != 0) ? theme.colorKeyboard :
                 (theme.colorKey != 0 ? theme.colorKey : theme.colorKeyAction);
    int dimColor = (baseBg & 0x00FFFFFF) | 0xD8000000;
    _dimPaint.setColor(dimColor);
    canvas.drawRect(0, 0, _viewWidth, _viewHeight, _dimPaint);

    int activeColor;
    if (theme.colorKeyActivated != 0 && theme.colorKeyActivated != theme.colorKeyboard)
      activeColor = theme.colorKeyActivated;
    else if (theme.activatedColor != 0)
      activeColor = theme.activatedColor;
    else if (theme.colorKeyAction != 0)
      activeColor = theme.colorKeyAction;
    else
      activeColor = theme.colorKey;

    int dividerColor = (theme.subLabelNumberRowColor != 0) ? theme.subLabelNumberRowColor :
                       (theme.subLabelColor != 0 ? theme.subLabelColor : theme.labelColor);

    int textColor = (theme.labelColor != 0) ? theme.labelColor :
                    (theme.actionLabelColor != 0 ? theme.actionLabelColor : theme.pressedColor);

    int centerNormalColor = (theme.colorKey != 0) ? theme.colorKey :
                            (theme.colorKeyAction != 0 ? theme.colorKeyAction : baseBg);

    _sectorRectOuter.set(_centerArcX - _sectorOuterRadius, _centerArcY - _sectorOuterRadius,
                         _centerArcX + _sectorOuterRadius, _centerArcY + _sectorOuterRadius);
    _sectorRectInner.set(_centerArcX - _sectorInnerRadius, _centerArcY - _sectorInnerRadius,
                         _centerArcX + _sectorInnerRadius, _centerArcY + _sectorInnerRadius);

    // 2. Active hovered sector
    if (_hoveredIndex >= 0 && _hoveredIndex < _sectors.length)
    {
      Sector s = _sectors[_hoveredIndex];
      _sectorPath.reset();
      _sectorPath.arcTo(_sectorRectOuter, s.startAngle, s.sweepAngle, true);
      _sectorPath.arcTo(_sectorRectInner, s.startAngle + s.sweepAngle, -s.sweepAngle, false);
      _sectorPath.close();

      _glowPaint.setColor((activeColor & 0x00FFFFFF) | 0x40000000);
      canvas.drawPath(_sectorPath, _glowPaint);

      _fillPaint.setColor(activeColor);
      canvas.drawPath(_sectorPath, _fillPaint);
    }

    // 3. Radial divider lines (spokes) between sectors
    _dividerPaint.setColor(dividerColor);
    for (float angle : DIVIDER_ANGLES)
    {
      double rad = Math.toRadians(angle);
      float cos = (float)Math.cos(rad);
      float sin = (float)Math.sin(rad);
      float x1 = _centerArcX + _sectorInnerRadius * cos;
      float y1 = _centerArcY + _sectorInnerRadius * sin;
      float x2 = _centerArcX + _sectorOuterRadius * cos;
      float y2 = _centerArcY + _sectorOuterRadius * sin;
      canvas.drawLine(x1, y1, x2, y2, _dividerPaint);
    }

    // 4. Draw sector labels near the outer edge
    float rLabel = _sectorOuterRadius - 18f * _density;
    _textPaint.setColor(textColor);
    for (int i = 0; i < _sectors.length; i++)
    {
      Sector s = _sectors[i];
      double midRad = Math.toRadians(s.midAngle);
      float lx = _centerArcX + (float)(rLabel * Math.cos(midRad));
      float ly = _centerArcY + (float)(rLabel * Math.sin(midRad));

      if (s.action == ActionType.HOME)
      {
        float distY = ly - _centerArcY;
        ly -= distY * 0.30f;
        lx += 10f * _density;
      }
      else if (s.action == ActionType.END)
      {
        float distY = ly - _centerArcY;
        ly -= distY * 0.30f;
        lx -= 10f * _density;
      }

      float baseSize;
      if (s.action == ActionType.HOME || s.action == ActionType.END)
        baseSize = 14f * _density;
      else if (s.action == ActionType.REDO)
        baseSize = 19.5f * _density;
      else
        baseSize = 22f * _density;

      _textPaint.setTextSize(baseSize);
      float textY = ly - (_textPaint.ascent() + _textPaint.descent()) / 2f;
      canvas.drawText(s.label, lx, textY, _textPaint);
    }

    // 5. Center circle (Clipboard Manager)
    boolean isCenterHovered = (_hoveredIndex == INDEX_CENTER);
    float r = _centerRadius;

    _fillPaint.setColor(isCenterHovered ? activeColor : centerNormalColor);
    canvas.drawCircle(_centerArcX, _centerArcY, r, _fillPaint);

    _clipboardPaint.setTypeface(_keyFont != null ? _keyFont : Typeface.DEFAULT);
    _clipboardPaint.setTextSize(_centerRadius * 0.70f);
    _clipboardPaint.setColor(textColor);
    float textY = _centerArcY - (_clipboardPaint.ascent() + _clipboardPaint.descent()) / 2f;
    canvas.drawText(_keyFont != null ? "\uE017" : "📋", _centerArcX, textY, _clipboardPaint);
  }
}
