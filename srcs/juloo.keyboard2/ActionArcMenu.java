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
    CUT_ALL,
    COPY,
    COPY_WORD,
    COPY_ALL,
    PASTE,
    END,
    LANGUAGE_SWITCH,
    CLIPBOARD
  }

  public static final int INDEX_NONE = -1;
  public static final int SECTOR_HOME = 0;
  public static final int SECTOR_UNDO = 1;
  public static final int SECTOR_SELECT_ALL = 2;
  public static final int SECTOR_CUT = 3;
  public static final int SECTOR_COPY = 4;
  public static final int SECTOR_PASTE = 5;
  public static final int SECTOR_END = 6;

  public static final int SUB_C_NONE = 0;
  public static final int SUB_C_WORD = 1;
  public static final int SUB_C_ALL = 2;

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
  private float _centerArcX = 0f;
  private float _centerArcY = 0f;
  private float _sectorInnerRadius = 0f;
  private float _sectorOuterRadius = 0f;
  private float _splitRadius = 0f;
  private float _deadZoneRadius = 0f;
  private int _hoveredIndex = INDEX_NONE;

  private boolean _visitedOuterV = false;
  private boolean _isClipboardInV = false;
  private boolean _visitedOuterZ = false;
  private boolean _isRedoInZ = false;
  private boolean _visitedOuterX = false;
  private boolean _isCutAllInX = false;
  private boolean _visitedOuterC = false;
  private int _subModeC = SUB_C_NONE;
  private boolean _hapticTickRequested = false;

  private int _viewWidth = 0;
  private int _viewHeight = 0;
  private float _density = 1f;

  private final Paint _dimPaint = new Paint();
  private final Paint _fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _clipboardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final Path _sectorPath = new Path();
  private final RectF _sectorRectOuter = new RectF();
  private final RectF _sectorRectSplit = new RectF();
  private final RectF _sectorRectInner = new RectF();
  private Typeface _keyFont = null;

  private static final float[] DIVIDER_ANGLES = new float[]{
    180f, 192f, 216f, 241f, 263f, 305.5f, 348f, 360f
  };

  // 7 action sectors strictly above horizontal (180° to 360°):
  // - Sector 0: Home (<), 180° to 192° (12°, 2x thinner, lifted above horizontal)
  // - Sector 1: Undo (Z), 192° to 216° (24°, contains inner Redo Y)
  // - Sector 2: Select All (A), 216° to 241° (25°)
  // - Sector 3: Cut (X), 241° to 263° (22°, increased by ~30%, contains inner Cut All A)
  // - Sector 4: Copy (C), 263° to 305.5° (42.5°, contains inner Left W: Copy Word, Right A: Copy All)
  // - Sector 5: Paste (V), 305.5° to 348° (42.5°, contains inner Clipboard)
  // - Sector 6: End (>), 348° to 360° (12°, 2x thinner, lifted above horizontal)
  private final Sector[] _sectors = new Sector[]{
    new Sector(ActionType.HOME, "|◀", 180f, 12f),
    new Sector(ActionType.UNDO, "Z", 192f, 24f),
    new Sector(ActionType.SELECT_ALL, "A", 216f, 25f),
    new Sector(ActionType.CUT, "X", 241f, 22f),
    new Sector(ActionType.COPY, "C", 263f, 42.5f),
    new Sector(ActionType.PASTE, "V", 305.5f, 42.5f),
    new Sector(ActionType.END, "▶|", 348f, 12f)
  };

  public ActionArcMenu()
  {
    _dimPaint.setStyle(Paint.Style.FILL);

    _fillPaint.setStyle(Paint.Style.FILL);
    _glowPaint.setStyle(Paint.Style.FILL);

    _dividerPaint.setStyle(Paint.Style.STROKE);
    _dividerPaint.setStrokeCap(Paint.Cap.ROUND);

    _textPaint.setTextAlign(Paint.Align.CENTER);
    _textPaint.setTypeface(Typeface.DEFAULT);

    _clipboardPaint.setTextAlign(Paint.Align.CENTER);
  }

  public boolean isActive()
  {
    return _isActive;
  }

  public boolean checkAndClearHapticTick()
  {
    boolean tick = _hapticTickRequested;
    _hapticTickRequested = false;
    return tick;
  }

  public void start(float touchX, float touchY, float rowHeight, float centerArcY, boolean hasLanguageSwitch,
                    String nextLangBadge, Context context, int viewWidth, int viewHeight)
  {
    _isActive = true;
    _viewWidth = viewWidth;
    _viewHeight = viewHeight;
    _hoveredIndex = INDEX_NONE;
    _visitedOuterV = false;
    _isClipboardInV = false;
    _visitedOuterZ = false;
    _isRedoInZ = false;
    _visitedOuterX = false;
    _isCutAllInX = false;
    _visitedOuterC = false;
    _subModeC = SUB_C_NONE;
    _hapticTickRequested = false;

    _density = context.getResources().getDisplayMetrics().density;
    if (_density <= 0f)
      _density = 1f;

    _dividerPaint.setStrokeWidth(1f * _density);

    _deadZoneRadius = 9.5f * _density;
    _sectorInnerRadius = 70f * _density;
    _sectorOuterRadius = 158f * _density;
    _splitRadius = _sectorInnerRadius + 0.40f * (_sectorOuterRadius - _sectorInnerRadius);

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
    boolean prevClipboard = _isClipboardInV;
    boolean prevRedo = _isRedoInZ;
    boolean prevCutAll = _isCutAllInX;
    int prevSubC = _subModeC;

    _hoveredIndex = INDEX_NONE;

    float dx = touchX - _centerArcX;
    float dy = touchY - _centerArcY;
    float dist = (float)Math.hypot(dx, dy);

    // Center dead zone: acts as safe cancel
    if (dist <= _deadZoneRadius)
    {
      _hoveredIndex = INDEX_NONE;
      _visitedOuterV = false;
      _isClipboardInV = false;
      _visitedOuterZ = false;
      _isRedoInZ = false;
      _visitedOuterX = false;
      _isCutAllInX = false;
      _visitedOuterC = false;
      _subModeC = SUB_C_NONE;
    }
    else
    {
      double angleRad = Math.atan2(dy, dx);
      float angleDeg = (float)Math.toDegrees(angleRad);
      if (angleDeg < 0f)
        angleDeg += 360f;

      // Normalize angle: 0°..45° becomes 360°..405° for continuous right wing
      float testAngle = (angleDeg < 45f) ? angleDeg + 360f : angleDeg;

      // Angular span from 170° to 370° covers above-horizontal semicircle [180°, 360°] with 10° margin
      if (testAngle >= 170f && testAngle <= 370f)
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
            _hoveredIndex = SECTOR_HOME;
          else if (testAngle >= 360f)
            _hoveredIndex = SECTOR_END;
        }

        // Sector Z: Outer Undo (Z), Inner Redo (Y)
        if (_hoveredIndex == SECTOR_UNDO)
        {
          if (dist > _splitRadius)
          {
            _visitedOuterZ = true;
            _isRedoInZ = false;
          }
          else
          {
            if (_visitedOuterZ)
              _isRedoInZ = true;
            else
              _isRedoInZ = false;
          }
        }
        else
        {
          _visitedOuterZ = false;
          _isRedoInZ = false;
        }

        // Sector X: Outer Cut (X), Inner Cut All (A)
        if (_hoveredIndex == SECTOR_CUT)
        {
          if (dist > _splitRadius)
          {
            _visitedOuterX = true;
            _isCutAllInX = false;
          }
          else
          {
            if (_visitedOuterX)
              _isCutAllInX = true;
            else
              _isCutAllInX = false;
          }
        }
        else
        {
          _visitedOuterX = false;
          _isCutAllInX = false;
        }

        // Sector C: Outer Copy (C), Inner Left Copy Word (W), Inner Right Copy All (A)
        if (_hoveredIndex == SECTOR_COPY)
        {
          if (dist > _splitRadius)
          {
            _visitedOuterC = true;
            _subModeC = SUB_C_NONE;
          }
          else
          {
            if (_visitedOuterC)
            {
              if (testAngle < 284.25f)
                _subModeC = SUB_C_WORD;
              else
                _subModeC = SUB_C_ALL;
            }
            else
            {
              _subModeC = SUB_C_NONE;
            }
          }
        }
        else
        {
          _visitedOuterC = false;
          _subModeC = SUB_C_NONE;
        }

        // Sector V: Outer Paste (V), Inner Clipboard Manager
        if (_hoveredIndex == SECTOR_PASTE)
        {
          if (dist > _splitRadius)
          {
            _visitedOuterV = true;
            _isClipboardInV = false;
          }
          else
          {
            if (_visitedOuterV)
              _isClipboardInV = true;
            else
              _isClipboardInV = false;
          }
        }
        else
        {
          _visitedOuterV = false;
          _isClipboardInV = false;
        }
      }
      else
      {
        // Dragging down below 170° or above 370° (back towards spacebar) cancels action
        _hoveredIndex = INDEX_NONE;
        _visitedOuterV = false;
        _isClipboardInV = false;
        _visitedOuterZ = false;
        _isRedoInZ = false;
        _visitedOuterX = false;
        _isCutAllInX = false;
        _visitedOuterC = false;
        _subModeC = SUB_C_NONE;
      }
    }

    boolean stateChanged = (_hoveredIndex != prevHovered ||
                            _isClipboardInV != prevClipboard ||
                            _isRedoInZ != prevRedo ||
                            _isCutAllInX != prevCutAll ||
                            _subModeC != prevSubC);
    // Haptic tick on activation of any inner subsector or switching between inner subsectors
    if ((_hoveredIndex == SECTOR_PASTE && !prevClipboard && _isClipboardInV) ||
        (_hoveredIndex == SECTOR_UNDO && !prevRedo && _isRedoInZ) ||
        (_hoveredIndex == SECTOR_CUT && !prevCutAll && _isCutAllInX) ||
        (_hoveredIndex == SECTOR_COPY && _subModeC != SUB_C_NONE && _subModeC != prevSubC))
    {
      _hapticTickRequested = true;
    }

    return stateChanged;
  }

  public ActionType finishTouch(float touchX, float touchY)
  {
    if (!_isActive)
      return null;

    updateTouch(touchX, touchY);
    ActionType action = null;

    if (_hoveredIndex == SECTOR_UNDO)
    {
      action = _isRedoInZ ? ActionType.REDO : ActionType.UNDO;
    }
    else if (_hoveredIndex == SECTOR_CUT)
    {
      action = _isCutAllInX ? ActionType.CUT_ALL : ActionType.CUT;
    }
    else if (_hoveredIndex == SECTOR_COPY)
    {
      if (_subModeC == SUB_C_WORD)
        action = ActionType.COPY_WORD;
      else if (_subModeC == SUB_C_ALL)
        action = ActionType.COPY_ALL;
      else
        action = ActionType.COPY;
    }
    else if (_hoveredIndex == SECTOR_PASTE)
    {
      action = _isClipboardInV ? ActionType.CLIPBOARD : ActionType.PASTE;
    }
    else if (_hoveredIndex >= 0 && _hoveredIndex < _sectors.length)
    {
      action = _sectors[_hoveredIndex].action;
    }

    _isActive = false;
    _hoveredIndex = INDEX_NONE;
    _visitedOuterV = false;
    _isClipboardInV = false;
    _visitedOuterZ = false;
    _isRedoInZ = false;
    _visitedOuterX = false;
    _isCutAllInX = false;
    _visitedOuterC = false;
    _subModeC = SUB_C_NONE;
    _hapticTickRequested = false;
    return action;
  }

  public void cancel()
  {
    _isActive = false;
    _hoveredIndex = INDEX_NONE;
    _visitedOuterV = false;
    _isClipboardInV = false;
    _visitedOuterZ = false;
    _isRedoInZ = false;
    _visitedOuterX = false;
    _isCutAllInX = false;
    _visitedOuterC = false;
    _subModeC = SUB_C_NONE;
    _hapticTickRequested = false;
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

    _sectorRectOuter.set(_centerArcX - _sectorOuterRadius, _centerArcY - _sectorOuterRadius,
                         _centerArcX + _sectorOuterRadius, _centerArcY + _sectorOuterRadius);
    _sectorRectSplit.set(_centerArcX - _splitRadius, _centerArcY - _splitRadius,
                         _centerArcX + _splitRadius, _centerArcY + _splitRadius);
    _sectorRectInner.set(_centerArcX - _sectorInnerRadius, _centerArcY - _sectorInnerRadius,
                         _centerArcX + _sectorInnerRadius, _centerArcY + _sectorInnerRadius);

    // 2. Active hovered sector
    if (_hoveredIndex >= 0 && _hoveredIndex < _sectors.length)
    {
      Sector s = _sectors[_hoveredIndex];
      _sectorPath.reset();

      if (_hoveredIndex == SECTOR_PASTE && _isClipboardInV)
      {
        // Highlight only the inner 40% (clipboard manager)
        _sectorPath.arcTo(_sectorRectSplit, s.startAngle, s.sweepAngle, true);
        _sectorPath.arcTo(_sectorRectInner, s.startAngle + s.sweepAngle, -s.sweepAngle, false);
      }
      else if (_hoveredIndex == SECTOR_UNDO && _isRedoInZ)
      {
        // Highlight only the inner 40% (redo)
        _sectorPath.arcTo(_sectorRectSplit, s.startAngle, s.sweepAngle, true);
        _sectorPath.arcTo(_sectorRectInner, s.startAngle + s.sweepAngle, -s.sweepAngle, false);
      }
      else if (_hoveredIndex == SECTOR_CUT && _isCutAllInX)
      {
        // Highlight only the inner 40% (cut all 'A')
        _sectorPath.arcTo(_sectorRectSplit, s.startAngle, s.sweepAngle, true);
        _sectorPath.arcTo(_sectorRectInner, s.startAngle + s.sweepAngle, -s.sweepAngle, false);
      }
      else if (_hoveredIndex == SECTOR_COPY && _subModeC == SUB_C_WORD)
      {
        // Highlight inner left subsector: 263° to 284.25° (sweep 21.25°)
        _sectorPath.arcTo(_sectorRectSplit, 263f, 21.25f, true);
        _sectorPath.arcTo(_sectorRectInner, 263f + 21.25f, -21.25f, false);
      }
      else if (_hoveredIndex == SECTOR_COPY && _subModeC == SUB_C_ALL)
      {
        // Highlight inner right subsector: 284.25° to 305.5° (sweep 21.25°)
        _sectorPath.arcTo(_sectorRectSplit, 284.25f, 21.25f, true);
        _sectorPath.arcTo(_sectorRectInner, 284.25f + 21.25f, -21.25f, false);
      }
      else
      {
        // Highlight entire sector (when moving outward to V / Z / X / C, or for other sectors)
        _sectorPath.arcTo(_sectorRectOuter, s.startAngle, s.sweepAngle, true);
        _sectorPath.arcTo(_sectorRectInner, s.startAngle + s.sweepAngle, -s.sweepAngle, false);
      }
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


    // Inner radial divider between W and A inside Sector C
    double midCRad = Math.toRadians(284.25);
    float cosMC = (float)Math.cos(midCRad);
    float sinMC = (float)Math.sin(midCRad);
    canvas.drawLine(_centerArcX + _sectorInnerRadius * cosMC,
                    _centerArcY + _sectorInnerRadius * sinMC,
                    _centerArcX + _splitRadius * cosMC,
                    _centerArcY + _splitRadius * sinMC,
                    _dividerPaint);

    // 4. Draw sector labels (excluding combo sectors Z, X, C, V which are rendered individually below)
    float rLabel = _splitRadius + (_sectorOuterRadius - _splitRadius) * 0.70f;
    float rInner = _sectorInnerRadius + (_splitRadius - _sectorInnerRadius) * 0.50f;
    _textPaint.setColor(textColor);
    for (int i = 0; i < _sectors.length; i++)
    {
      if (i == SECTOR_UNDO || i == SECTOR_CUT || i == SECTOR_COPY || i == SECTOR_PASTE)
        continue;

      Sector s = _sectors[i];
      double midRad = Math.toRadians(s.midAngle);
      float lx = _centerArcX + (float)(rLabel * Math.cos(midRad));
      float ly = _centerArcY + (float)(rLabel * Math.sin(midRad));

      float baseSize;
      if (s.action == ActionType.HOME || s.action == ActionType.END)
        baseSize = 15f * _density;
      else
        baseSize = 24f * _density;

      _textPaint.setTextSize(baseSize);
      float textY = ly - (_textPaint.ascent() + _textPaint.descent()) / 2f;
      canvas.drawText(s.label, lx, textY, _textPaint);
    }

    // Sector Z: Outer "Z" (Undo) and Inner "Y" (Redo)
    Sector sz = _sectors[SECTOR_UNDO];
    double midRadZ = Math.toRadians(sz.midAngle);
    float cosZ = (float)Math.cos(midRadZ);
    float sinZ = (float)Math.sin(midRadZ);

    // Outer "Z"
    float zx = _centerArcX + rLabel * cosZ;
    float zy = _centerArcY + rLabel * sinZ;
    _textPaint.setTextSize(24f * _density);
    float zTextY = zy - (_textPaint.ascent() + _textPaint.descent()) / 2f;
    canvas.drawText("Z", zx, zTextY, _textPaint);

    // Inner "Y"
    float yx = _centerArcX + rInner * cosZ;
    float yy = _centerArcY + rInner * sinZ;
    _textPaint.setTextSize(18f * _density);
    float yTextY = yy - (_textPaint.ascent() + _textPaint.descent()) / 2f;
    canvas.drawText("Y", yx, yTextY, _textPaint);

    // Sector X: Outer "X" (Cut) and Inner "A" (Cut All)
    Sector sx = _sectors[SECTOR_CUT];
    double midRadX = Math.toRadians(sx.midAngle);
    float cosX = (float)Math.cos(midRadX);
    float sinX = (float)Math.sin(midRadX);

    // Outer "X"
    float xx = _centerArcX + rLabel * cosX;
    float xy = _centerArcY + rLabel * sinX;
    _textPaint.setTextSize(24f * _density);
    float xTextY = xy - (_textPaint.ascent() + _textPaint.descent()) / 2f;
    canvas.drawText("X", xx, xTextY, _textPaint);

    // Inner "A"
    float ax = _centerArcX + rInner * cosX;
    float ay = _centerArcY + rInner * sinX;
    _textPaint.setTextSize(18f * _density);
    float aTextY = ay - (_textPaint.ascent() + _textPaint.descent()) / 2f;
    canvas.drawText("A", ax, aTextY, _textPaint);

    // Sector C: Outer "C" (Copy), Inner Left "W" (Copy Word), Inner Right "A" (Copy All)
    Sector sc = _sectors[SECTOR_COPY];
    double midRadC = Math.toRadians(sc.midAngle);
    float cosC = (float)Math.cos(midRadC);
    float sinC = (float)Math.sin(midRadC);

    // Outer "C"
    float cx = _centerArcX + rLabel * cosC;
    float cy = _centerArcY + rLabel * sinC;
    _textPaint.setTextSize(24f * _density);
    float cTextY = cy - (_textPaint.ascent() + _textPaint.descent()) / 2f;
    canvas.drawText("C", cx, cTextY, _textPaint);

    // Inner Left "W" (mid angle: 263° + 21.25°/2 = 273.625°)
    double midRadCW = Math.toRadians(273.625);
    float cosCW = (float)Math.cos(midRadCW);
    float sinCW = (float)Math.sin(midRadCW);
    float wx = _centerArcX + rInner * cosCW;
    float wy = _centerArcY + rInner * sinCW;
    _textPaint.setTextSize(18f * _density);
    float wTextY = wy - (_textPaint.ascent() + _textPaint.descent()) / 2f;
    canvas.drawText("W", wx, wTextY, _textPaint);

    // Inner Right "A" (mid angle: 284.25° + 21.25°/2 = 294.875°)
    double midRadCA = Math.toRadians(294.875);
    float cosCA = (float)Math.cos(midRadCA);
    float sinCA = (float)Math.sin(midRadCA);
    float cax = _centerArcX + rInner * cosCA;
    float cay = _centerArcY + rInner * sinCA;
    _textPaint.setTextSize(18f * _density);
    float caTextY = cay - (_textPaint.ascent() + _textPaint.descent()) / 2f;
    canvas.drawText("A", cax, caTextY, _textPaint);

    // Sector V: Outer "V" and Inner Clipboard Manager icon
    Sector sv = _sectors[SECTOR_PASTE];
    double midRadV = Math.toRadians(sv.midAngle);
    float cosV = (float)Math.cos(midRadV);
    float sinV = (float)Math.sin(midRadV);

    // Outer "V"
    float vx = _centerArcX + rLabel * cosV;
    float vy = _centerArcY + rLabel * sinV;
    _textPaint.setTextSize(24f * _density);
    float vTextY = vy - (_textPaint.ascent() + _textPaint.descent()) / 2f;
    canvas.drawText("V", vx, vTextY, _textPaint);

    // Inner clipboard icon
    float clipX = _centerArcX + rInner * cosV;
    float clipY = _centerArcY + rInner * sinV;
    _clipboardPaint.setTypeface(_keyFont != null ? _keyFont : Typeface.DEFAULT);
    _clipboardPaint.setTextSize(17f * _density);
    _clipboardPaint.setColor(textColor);
    float clipTextY = clipY - (_clipboardPaint.ascent() + _clipboardPaint.descent()) / 2f;
    canvas.drawText(_keyFont != null ? "\uE017" : "📋", clipX, clipTextY, _clipboardPaint);
  }
}
