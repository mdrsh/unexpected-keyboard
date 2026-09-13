package juloo.keyboard2;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.content.res.Resources;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.util.Arrays;
import java.util.List;
import juloo.keyboard2.prefs.LayoutsPreference;

public class Keyboard2View extends View
  implements View.OnTouchListener, Pointers.IPointerEventHandler
{
  private KeyboardData _keyboard;

  /** The key holding the shift key is used to set shift state from
      autocapitalisation. */
  private KeyboardData.Key _shift_key;

  /** Used to add fake pointers. */
  private KeyboardData.Key _compose_key;

  private Pointers _pointers;

  private Pointers.Modifiers _mods;

  private static int _currentWhat = 0;

  private Config _config;

  private float _keyWidth;
  private float _mainLabelSize;
  private float _subLabelSize;
  private float _marginRight;
  private float _marginLeft;
  private float _marginBottom;
  private int _insets_left = 0;
  private int _insets_right = 0;
  private int _insets_bottom = 0;

  private Theme _theme;
  private Theme.Computed _tc;

  private static RectF _tmpRect = new RectF();

  private final ActionArcMenu _arcMenu = new ActionArcMenu();
  private int _potentialArcPointerId = -1;
  private float _potentialArcDownX = 0f;
  private float _potentialArcDownY = 0f;
  private float _potentialArcMinY = 0f;
  private float _potentialArcTurnX = 0f;
  private boolean _potentialArcIsSpace = false;
  private boolean _isSpaceSlidingLanguage = false;
  private float _spaceSlideOffset = 0f;
  private float _spaceKeyWidth = 0f;
  private boolean _hapticFiredForThreshold = false;
  private ValueAnimator _spaceSnapAnim = null;
  private float _mainKeyboardBoundaryY = -1f;
  private float _mainKeyboardHeight = -1f;

  enum Vertical
  {
    TOP,
    CENTER,
    BOTTOM
  }

  public Keyboard2View(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _theme = new Theme(getContext(), attrs);
    _config = Config.globalConfig();
    _pointers = new Pointers(this, _config);
    refresh_navigation_bar(context);
    setOnTouchListener(this);
    int layout_id = (attrs == null) ? 0 :
      attrs.getAttributeResourceValue(null, "layout", 0);
    if (layout_id == 0)
      reset();
    else
      setKeyboard(KeyboardData.load(getResources(), layout_id));
  }

  private Window getParentWindow(Context context)
  {
    if (context instanceof InputMethodService)
      return ((InputMethodService)context).getWindow().getWindow();
    if (context instanceof ContextWrapper)
      return getParentWindow(((ContextWrapper)context).getBaseContext());
    return null;
  }

  public void refresh_navigation_bar(Context context)
  {
    if (VERSION.SDK_INT < 21)
      return;
    // The intermediate Window is a [Dialog].
    Window w = getParentWindow(context);
    w.setNavigationBarColor(_theme.colorNavBar);
    if (VERSION.SDK_INT < 26)
      return;
    int uiFlags = getSystemUiVisibility();
    if (_theme.isLightNavBar)
      uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    else
      uiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    setSystemUiVisibility(uiFlags);
  }

  public void setKeyboard(KeyboardData kw)
  {
    _keyboard = kw;
    _shift_key = _keyboard.findKeyWithValue(KeyValue.SHIFT);
    _compose_key = _keyboard.findKeyWithValue(KeyValue.COMPOSE);
    KeyModifier.set_modmap(_keyboard.modmap);
    reset();
  }

  public void reset()
  {
    if (_arcMenu.isActive())
      _arcMenu.cancel();
    if (_spaceSnapAnim != null)
    {
      _spaceSnapAnim.cancel();
      _spaceSnapAnim = null;
    }
    _isSpaceSlidingLanguage = false;
    _spaceSlideOffset = 0f;
    _potentialArcPointerId = -1;
    _mods = Pointers.Modifiers.EMPTY;
    _pointers.clear();
    requestLayout();
    invalidate();
  }

  void set_fake_ptr_latched(KeyboardData.Key key, KeyValue kv, boolean latched,
      boolean lock)
  {
    if (_keyboard == null || key == null)
      return;
    _pointers.set_fake_pointer_state(key, kv, latched, lock);
  }

  /** Called by auto-capitalisation. */
  public void set_shift_state(boolean latched, boolean lock)
  {
    set_fake_ptr_latched(_shift_key, KeyValue.SHIFT, latched, lock);
  }

  /** Called from [KeyEventHandler]. */
  public void set_compose_pending(boolean pending)
  {
    set_fake_ptr_latched(_compose_key, KeyValue.COMPOSE, pending, false);
  }

  /** Called from [Keybard2.onUpdateSelection].  */
  public void set_selection_state(boolean selection_state)
  {
    if (_config.editor_config.selection_mode_enabled)
      set_fake_ptr_latched(KeyboardData.Key.EMPTY,
          KeyValue.SELECTION_MODE, selection_state, true);
  }

  public KeyValue modifyKey(KeyValue k, Pointers.Modifiers mods)
  {
    return KeyModifier.modify(k, mods);
  }

  public void onPointerDown(KeyValue k, boolean isSwipe)
  {
    updateFlags();
    _config.handler.key_down(k, isSwipe);
    invalidate();
    vibrate();
  }

  public void onPointerUp(KeyValue k, Pointers.Modifiers mods)
  {
    // [key_up] must be called before [updateFlags]. The latter might disable
    // flags.
    _config.handler.key_up(k, mods);
    updateFlags();
    invalidate();
  }

  public void onPointerHold(KeyValue k, Pointers.Modifiers mods)
  {
    _config.handler.key_up(k, mods);
    updateFlags();
  }

  public void onPointerFlagsChanged(boolean shouldVibrate)
  {
    updateFlags();
    invalidate();
    if (shouldVibrate)
      vibrate();
  }

  private void updateFlags()
  {
    _mods = _pointers.getModifiers();
    _config.handler.mods_changed(_mods);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    int p;
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_POINTER_UP:
        int upPointerId = event.getPointerId(event.getActionIndex());
        if (_arcMenu.isActive() && upPointerId == _potentialArcPointerId)
        {
          int arcIdx = event.findPointerIndex(_potentialArcPointerId);
          float upX = (arcIdx != -1) ? event.getX(arcIdx) : _potentialArcDownX;
          float upY = (arcIdx != -1) ? event.getY(arcIdx) : _potentialArcDownY;
          ActionArcMenu.ActionType action = _arcMenu.finishTouch(upX, upY);
          _potentialArcPointerId = -1;
          invalidate();
          if (action != null)
          {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            executeArcAction(action);
          }
          return (true);
        }
        else if (_isSpaceSlidingLanguage && upPointerId == _potentialArcPointerId)
        {
          float kw = _spaceKeyWidth > 0 ? _spaceKeyWidth : 300f;
          float threshold = kw * 0.35f;
          if (_spaceSlideOffset <= -threshold)
          {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            executeArcAction(ActionArcMenu.ActionType.LANGUAGE_SWITCH);
            _isSpaceSlidingLanguage = false;
            _spaceSlideOffset = 0f;
            invalidate();
          }
          else if (_spaceSlideOffset >= threshold)
          {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (_config != null && _config.handler != null)
              _config.handler.key_up(KV_SWITCH_BACKWARD, Pointers.Modifiers.EMPTY);
            _isSpaceSlidingLanguage = false;
            _spaceSlideOffset = 0f;
            invalidate();
          }
          else
          {
            animateSpaceSnapBack();
          }
          _potentialArcPointerId = -1;
          return (true);
        }
        else if (_potentialArcPointerId != -1 && upPointerId == _potentialArcPointerId)
        {
          _potentialArcPointerId = -1;
        }
        _pointers.onTouchUp(upPointerId);
        break;
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_POINTER_DOWN:
        p = event.getActionIndex();
        float tx = event.getX(p);
        float ty = event.getY(p);
        KeyboardData.Key key = getKeyAtPosition(tx, ty);
        if (key != null)
        {
          _pointers.onTouchDown(tx, ty, event.getPointerId(p), key);
          boolean isMainKeyboard = (_keyboard != null && _keyboard.bottom_row);
          boolean isSpace = (key.role == KeyboardData.Key.Role.Space_bar && isMainKeyboard);
          boolean isZero = (key.keys[0] != null && key.keys[0].getKind() == KeyValue.Kind.Char && key.keys[0].getChar() == '0');
          if (isSpace || isZero)
          {
            if (_spaceSnapAnim != null)
            {
              _spaceSnapAnim.cancel();
              _spaceSnapAnim = null;
            }
            _potentialArcPointerId = event.getPointerId(p);
            _potentialArcDownX = tx;
            _potentialArcDownY = ty;
            _potentialArcMinY = ty;
            _potentialArcTurnX = tx;
            _potentialArcIsSpace = isSpace;
            _isSpaceSlidingLanguage = false;
            _spaceSlideOffset = 0f;
            _hapticFiredForThreshold = false;
          }
        }
        break;
      case MotionEvent.ACTION_MOVE:
        if (_arcMenu.isActive())
        {
          int arcIdx = event.findPointerIndex(_potentialArcPointerId);
          if (arcIdx != -1)
          {
            _arcMenu.updateTouch(event.getX(arcIdx), event.getY(arcIdx));
            invalidate();
          }
          return (true);
        }
        else if (_isSpaceSlidingLanguage)
        {
          int arcIdx = event.findPointerIndex(_potentialArcPointerId);
          if (arcIdx != -1)
          {
            float curX = event.getX(arcIdx);
            _spaceSlideOffset = curX - _potentialArcDownX;
            float kw = _spaceKeyWidth > 0 ? _spaceKeyWidth : 300f;
            float threshold = kw * 0.35f;
            if (Math.abs(_spaceSlideOffset) >= threshold)
            {
              if (!_hapticFiredForThreshold)
              {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                _hapticFiredForThreshold = true;
              }
            }
            else
            {
              _hapticFiredForThreshold = false;
            }
            invalidate();
          }
          return (true);
        }
        else if (_potentialArcPointerId != -1)
        {
          int arcIdx = event.findPointerIndex(_potentialArcPointerId);
          if (arcIdx != -1)
          {
            float curX = event.getX(arcIdx);
            float curY = event.getY(arcIdx);
            float dx = curX - _potentialArcDownX;
            float dy = curY - _potentialArcDownY;
            float fanCenterY = getMiddleBottomLetterRowBoundary();
            float triggerY = fanCenterY;

            boolean isUpward = (dy < 0);
            float distUp = isUpward ? -dy : 0f;
            float distSide = Math.abs(dx);

            // Cone of 25 degrees from vertical on each side (50 degrees total cone):
            // tan(25°) ≈ 0.4663f
            boolean isWithinArcCone = isUpward && (distSide <= distUp * 0.4663f);

            if (curY < _potentialArcMinY)
            {
              _potentialArcMinY = curY;
              if (isWithinArcCone)
                _potentialArcTurnX = curX;
            }

            float turnDx = curX - _potentialArcTurnX;
            float turnDist = Math.abs(turnDx);

            boolean hasMultipleLayouts = (_config != null && _config.layouts != null && _config.layouts.size() > 1);

            // Sideways turn after moving up ("Г" / "Т" gesture)
            boolean isTurnSideways = (turnDist >= _config.swipe_dist_px * 0.5f && distUp >= _config.swipe_dist_px * 0.4f && !isWithinArcCone);

            // Horizontal slide on spacebar (within spacebar area, low vertical movement)
            boolean isSpaceHorizontal = _potentialArcIsSpace && hasMultipleLayouts &&
                (distUp < _config.swipe_dist_px * 0.45f && Math.abs(dy) < _config.swipe_dist_px * 0.8f) &&
                (distSide >= _config.swipe_dist_px * 0.35f);

            // Diagonal swipe (outside 50-degree upward cone)
            // On key '0', only upper/horizontal diagonals switch to slider, so downward swipes (for space) are not blocked
            boolean isDownwardOnZero = !_potentialArcIsSpace && (dy > _config.swipe_dist_px * 0.3f);
            boolean isDiagonal = !isWithinArcCone && !isDownwardOnZero && (distSide >= _config.swipe_dist_px * 0.5f);

            if (isSpaceHorizontal)
            {
              _isSpaceSlidingLanguage = true;
              _spaceSlideOffset = dx;
              _hapticFiredForThreshold = false;
              _pointers.cancelPointer(_potentialArcPointerId);
              invalidate();
              return (true);
            }
            else if (isDownwardOnZero)
            {
              _potentialArcPointerId = -1;
            }
            else if (isTurnSideways || isDiagonal)
            {
              float slideDx = isTurnSideways ? turnDx : dx;
              _pointers.switchToSlider(_potentialArcPointerId, curX, curY, slideDx);
              _potentialArcPointerId = -1;
            }
            else if (isWithinArcCone)
            {
              if (curY <= triggerY)
              {
                _pointers.cancelPointer(_potentialArcPointerId);
                _arcMenu.start(curX, _potentialArcDownY, _tc.row_height, fanCenterY,
                    _potentialArcIsSpace && hasMultipleLayouts, getNextLanguageBadge(), getContext(), getWidth(), getHeight());
                _arcMenu.updateTouch(curX, curY);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return (true);
              }
            }
          }
        }
        for (p = 0; p < event.getPointerCount(); p++)
          _pointers.onTouchMove(event.getX(p), event.getY(p), event.getPointerId(p));
        break;
      case MotionEvent.ACTION_CANCEL:
        if (_arcMenu.isActive())
        {
          _arcMenu.cancel();
          _potentialArcPointerId = -1;
          invalidate();
        }
        if (_isSpaceSlidingLanguage)
        {
          _isSpaceSlidingLanguage = false;
          _spaceSlideOffset = 0f;
          _potentialArcPointerId = -1;
          invalidate();
        }
        _pointers.onTouchCancel();
        break;
      default:
        return (false);
    }
    return (true);
  }

  public float getMiddleBottomLetterRowBoundary()
  {
    boolean isMain = (_keyboard != null && _keyboard.bottom_row);

    if (isMain)
    {
      float y = _tc.margin_top;
      int letterRowCount = 0;
      for (KeyboardData.Row row : _keyboard.rows)
      {
        y += row.shift * _tc.row_height;
        y += row.height * _tc.row_height;
        if (!row.is_number_row)
        {
          letterRowCount++;
          if (letterRowCount == 2)
            break;
        }
      }
      _mainKeyboardBoundaryY = y;
      _mainKeyboardHeight = getHeight();
      return y;
    }

    // On numeric/special keyboards: output the fan at the EXACT SAME LEVEL as on the main keyboard!
    if (_mainKeyboardBoundaryY > 0f && _mainKeyboardHeight > 0f)
    {
      float distFromBottom = _mainKeyboardHeight - _mainKeyboardBoundaryY;
      return getHeight() - distFromBottom;
    }

    // Fallback if main keyboard was not measured yet: calculate directly from current layout in _config
    if (_config != null && _config.layouts != null && !_config.layouts.isEmpty() && _tc != null)
    {
      int idx = _config.get_current_layout();
      if (idx >= 0 && idx < _config.layouts.size())
      {
        KeyboardData raw = _config.layouts.get(idx);
        if (raw != null)
        {
          KeyboardData mainKb = LayoutModifier.modify_layout(raw);
          float y = _tc.margin_top;
          int letterRowCount = 0;
          for (KeyboardData.Row row : mainKb.rows)
          {
            y += row.shift * _tc.row_height;
            y += row.height * _tc.row_height;
            if (!row.is_number_row)
            {
              letterRowCount++;
              if (letterRowCount == 2)
                break;
            }
          }
          float mainH = _tc.row_height * mainKb.keysHeight + _config.marginTop + _marginBottom;
          _mainKeyboardBoundaryY = y;
          _mainKeyboardHeight = mainH;
          float distFromBottom = mainH - y;
          return getHeight() - distFromBottom;
        }
      }
    }

    return getHeight() * 0.48f;
  }

  private KeyboardData.Row getRowAtPosition(float ty)
  {
    float y = _config.marginTop;
    if (ty < y)
      return null;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += (row.shift + row.height) * _tc.row_height;
      if (ty < y)
        return row;
    }
    return null;
  }

  private KeyboardData.Key getKeyAtPosition(float tx, float ty)
  {
    KeyboardData.Row row = getRowAtPosition(ty);
    float x = _marginLeft;
    if (row == null || tx < x)
      return null;
    for (KeyboardData.Key key : row.keys)
    {
      float xLeft = x + key.shift * _keyWidth;
      float xRight = xLeft + key.width * _keyWidth;
      if (tx < xLeft)
        return null;
      if (tx < xRight)
        return key;
      x = xRight;
    }
    return null;
  }

  private void vibrate()
  {
    VibratorCompat.vibrate(this, _config);
  }

  @Override
  public void onMeasure(int wSpec, int hSpec)
  {
    DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
    int width = dm.widthPixels;
    _marginLeft = Math.max(_config.horizontal_margin, _insets_left);
    _marginRight = Math.max(_config.horizontal_margin, _insets_right);
    _marginBottom = _config.margin_bottom + _insets_bottom;
    _keyWidth = (width - _marginLeft - _marginRight) / _keyboard.keysWidth;
    _tc = new Theme.Computed(_theme, _config, _keyWidth, _keyboard);
    // Compute the size of labels based on the width or the height of keys. The
    // margin around keys is taken into account. Keys normal aspect ratio is
    // assumed to be 3/2 for a 10 columns layout. It's generally more, the
    // width computation is useful when the keyboard is unusually high.
    float labelBaseSize = Math.min(
        _tc.row_height - _tc.vertical_margin,
        (width / 10 - _tc.horizontal_margin) * 3/2
        ) * _config.characterSize;
    _mainLabelSize = labelBaseSize * _config.labelTextSize;
    _subLabelSize = labelBaseSize * _config.sublabelTextSize;
    int height =
      (int)(_tc.row_height * _keyboard.keysHeight
          + _config.marginTop + _marginBottom);
    setMeasuredDimension(width, height);

    if (_keyboard != null && _keyboard.bottom_row)
    {
      float y = _tc.margin_top;
      int letterRowCount = 0;
      for (KeyboardData.Row row : _keyboard.rows)
      {
        y += row.shift * _tc.row_height;
        y += row.height * _tc.row_height;
        if (!row.is_number_row)
        {
          letterRowCount++;
          if (letterRowCount == 2)
            break;
        }
      }
      _mainKeyboardBoundaryY = y;
      _mainKeyboardHeight = height;
    }
  }

  Rect _cached_exclusion_rect = new Rect();
  List<Rect> _cached_exclusion_rects = Arrays.asList(_cached_exclusion_rect);
  @Override
  public void onLayout(boolean changed, int left, int top, int right, int bottom)
  {
    if (!changed)
      return;
    // Since SDK 30, this is done automatically:
    // https://android.googlesource.com/platform/frameworks/base/+/android11-release/core/java/android/inputmethodservice/InputMethodService.java#852
    if (VERSION.SDK_INT == 29)
    {
      // Disable the back-gesture on the keyboard area
      _cached_exclusion_rect.set(
          left + (int)_marginLeft,
          top + (int)_config.marginTop,
          right - (int)_marginRight,
          bottom - (int)_marginBottom);
      setSystemGestureExclusionRects(_cached_exclusion_rects);
    }
  }

  @Override
  public WindowInsets onApplyWindowInsets(WindowInsets wi)
  {
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS is set in [Keyboard2#updateSoftInputWindowLayoutParams] for SDK_INT >= 35.
    if (VERSION.SDK_INT < 35)
      return wi;
    int insets_types =
      WindowInsets.Type.systemBars()
      | WindowInsets.Type.displayCutout();
    Insets insets = wi.getInsets(insets_types);
    _insets_left = insets.left;
    _insets_right = insets.right;
    _insets_bottom = insets.bottom;
    return WindowInsets.CONSUMED;
  }

  /** Horizontal and vertical position of the 9 indexes. */
  static final Paint.Align[] LABEL_POSITION_H = new Paint.Align[]{
    Paint.Align.CENTER, Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT,
    Paint.Align.RIGHT, Paint.Align.LEFT, Paint.Align.RIGHT,
    Paint.Align.CENTER, Paint.Align.CENTER
  };

  static final Vertical[] LABEL_POSITION_V = new Vertical[]{
    Vertical.CENTER, Vertical.TOP, Vertical.TOP, Vertical.BOTTOM,
    Vertical.BOTTOM, Vertical.CENTER, Vertical.CENTER, Vertical.TOP,
    Vertical.BOTTOM
  };

  @Override
  protected void onDraw(Canvas canvas)
  {
    if (_tc.keyboard_background_paint != null)
    {
      canvas.drawRect(
              0,
              0,
              getWidth(),
              getHeight(),
              _tc.keyboard_background_paint);
    }

    float y = _tc.margin_top;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += row.shift * _tc.row_height;
      float x = _marginLeft + _tc.margin_left;
      float keyH = row.height * _tc.row_height - _tc.vertical_margin;
      for (KeyboardData.Key k : row.keys)
      {
        x += k.shift * _keyWidth;
        float keyW = _keyWidth * k.width - _tc.horizontal_margin;
        boolean isKeyDown = _pointers.isKeyDown(k);
        boolean isMainKeyDown = _pointers.isMainKeyDown(k);
        Theme.Computed.Key tc_key;
        if (isMainKeyDown)
          tc_key = _tc.key_activated;
        else
          switch (k.role)
          {
            case Action: tc_key = _tc.key_action; break;
            case Space_bar: tc_key = _tc.key_space_bar; break;
            case Suggestion: tc_key = _tc.key_suggestion; break;
            default:
            case Normal: tc_key = _tc.key; break;
          }
        drawKeyFrame(canvas, x, y, keyW, keyH, tc_key);
        boolean isAction = isActionKey(k);
        if (k.keys[0] != null)
        {
          float labelY = y;
          if (row.is_number_row && k.keys[1] == null && k.keys[2] == null && k.keys[7] == null)
            labelY += NUMBER_ROW_LABEL_Y_OFFSET * keyH;
          drawLabel(canvas, k.keys[0], keyW / 2f + x, labelY, keyH, isMainKeyDown, isKeyDown, tc_key,
              row.is_number_row ? NUMBER_ROW_LABEL_SCALE : 1.0f, isAction);
        }
        boolean isLightSubLabel = row.is_number_row || (k.role != KeyboardData.Key.Role.Normal);
        boolean isMainSpaceBar = k.role == KeyboardData.Key.Role.Space_bar && _keyboard.bottom_row;
        if (isMainSpaceBar)
          _spaceKeyWidth = keyW;
        for (int i = 1; i < 9; i++)
        {
          if (k.keys[i] != null && !isMainSpaceBar)
            drawSubLabel(canvas, k.keys[i], x, y, keyW, keyH, i, isMainKeyDown, tc_key, isLightSubLabel);
        }
        drawIndication(canvas, k, x, y, keyW, keyH, _tc);
        x += _keyWidth * k.width;
      }
      y += row.height * _tc.row_height;
    }
    if (_arcMenu.isActive())
    {
      _arcMenu.draw(canvas, _theme);
    }
  }

  @Override
  public void onDetachedFromWindow()
  {
    super.onDetachedFromWindow();
  }

  /** Draw borders and background of the key. */
  void drawKeyFrame(Canvas canvas, float x, float y, float keyW, float keyH,
      Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    float w = tc.border_width;
    float padding = w / 2.f;
    _tmpRect.set(x + padding, y + padding, x + keyW - padding, y + keyH - padding);
    canvas.drawRoundRect(_tmpRect, r, r, tc.bg_paint);
    if (w > 0.f)
    {
      float overlap = r - r * 0.85f + w; // sin(45°)
      drawBorder(canvas, x, y, x + overlap, y + keyH, tc.border_left_paint, tc);
      drawBorder(canvas, x + keyW - overlap, y, x + keyW, y + keyH, tc.border_right_paint, tc);
      drawBorder(canvas, x, y, x + keyW, y + overlap, tc.border_top_paint, tc);
      drawBorder(canvas, x, y + keyH - overlap, x + keyW, y + keyH, tc.border_bottom_paint, tc);
    }
  }

  /** Clip to draw a border at a time. This allows to call [drawRoundRect]
      several time with the same parameters but a different Paint. */
  void drawBorder(Canvas canvas, float clipl, float clipt, float clipr,
      float clipb, Paint paint, Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    canvas.save();
    canvas.clipRect(clipl, clipt, clipr, clipb);
    canvas.drawRoundRect(_tmpRect, r, r, paint);
    canvas.restore();
  }

  private static boolean isActionKey(KeyboardData.Key k)
  {
    if (k.role != KeyboardData.Key.Role.Action)
      return false;
    KeyValue mainKey = k.keys[0];
    if (mainKey == null)
      return false;
    return mainKey.getKind() != KeyValue.Kind.Char;
  }

  private int labelColor(KeyValue k, boolean isKeyFrameActivated, boolean isAnyPointerDown, boolean sublabel, boolean isLightSubLabel, boolean isActionLabel)
  {
    int flags = _pointers.getKeyFlags(k);
    if (flags != -1)
    {
      if (isKeyFrameActivated)
      {
        if ((flags & Pointers.FLAG_P_LOCKED) != 0)
          return (_theme.lockedColor != 0) ? _theme.lockedColor : 0xFFFFFFFF;
        return (_theme.activatedColor != 0) ? _theme.activatedColor : 0xFFFFFFFF;
      }
      return _theme.hasActionLabelColor ? _theme.actionLabelColor : _theme.colorKeyActivated;
    }
    if (isKeyFrameActivated)
      return _theme.secondaryLabelColor;
    if (sublabel)
    {
      if (k.hasFlagsAny(KeyValue.FLAG_GREYED) && !(k.getKind() == KeyValue.Kind.Editing && k.getEditing() == KeyValue.Editing.SPACE_BAR))
        return _theme.greyedLabelColor;
      return isLightSubLabel ? _theme.subLabelNumberRowColor : _theme.subLabelColor;
    }
    if (k.hasFlagsAny(KeyValue.FLAG_GREYED))
      return _theme.greyedLabelColor;
    if (isAnyPointerDown)
      return _theme.labelColor;
    if (isActionLabel && _theme.hasActionLabelColor)
      return _theme.actionLabelColor;
    if (k.hasFlagsAny(KeyValue.FLAG_SECONDARY))
      return _theme.secondaryLabelColor;
    return _theme.labelColor;
  }

  private static final float NUMBER_ROW_LABEL_SCALE = 0.8f;
  private static final float NUMBER_ROW_LABEL_Y_OFFSET = -0.10f;
  private static final float PUNCTUATION_LABEL_Y_OFFSET = -0.05f;

  private void drawLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyH, boolean isMainKeyDown, boolean isAnyPointerDown, Theme.Computed.Key tc, float scale, boolean isActionKey)
  {
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    boolean isAction = isActionKey || (kv.getKind() == KeyValue.Kind.Editing && kv.getEditing() == KeyValue.Editing.SELECTION_CANCEL);
    float textSize = scaleTextSize(kv, true) * scale;
    String label = kv.getString();
    boolean specialFont = kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT);
    boolean isShift = kv.equals(KeyValue.SHIFT) ||
        (kv.getKind() == KeyValue.Kind.Event && kv.getEvent() == KeyValue.Event.CAPS_LOCK);
    if (isShift)
    {
      int flags = _pointers.getKeyFlags(kv);
      if (flags != -1 && (flags & Pointers.FLAG_P_LOCKED) != 0)
      {
        label = String.valueOf((char)0xE005);
        specialFont = true;
      }
      else
      {
        label = String.valueOf((char)0xE00A);
        specialFont = true;
      }
    }
    if (kv.getKind() == KeyValue.Kind.Editing && kv.getEditing() == KeyValue.Editing.SPACE_BAR)
    {
      if (_config != null && _config.layouts != null && _config.layouts.size() > 1)
      {
        String lang = getLanguageLabel(_keyboard);
        if (lang != null && !lang.isEmpty())
        {
          label = lang;
          specialFont = false;
        }
      }
    }
    boolean isImeAction = (kv.getKind() == KeyValue.Kind.Event && kv.getEvent() == KeyValue.Event.ACTION);
    boolean isSingleCharAction = isImeAction && label != null && label.length() == 1;
    boolean isFilledGlyph = isSingleCharAction && (label.equals("➤") || label.equals("◀"));
    if (isSingleCharAction && !isFilledGlyph)
      textSize *= 1.20f;
    Paint p = tc.label_paint(specialFont, labelColor(kv, isMainKeyDown, isAnyPointerDown, false, false, isAction), textSize);
    if (isSingleCharAction || (label != null && label.equals("✓")))
    {
      p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
      if (!isFilledGlyph)
        p.setFakeBoldText(true);
    }

    if (kv.getKind() == KeyValue.Kind.Editing && kv.getEditing() == KeyValue.Editing.SPACE_BAR &&
        _keyboard != null && _keyboard.bottom_row &&
        _config != null && _config.layouts != null && _config.layouts.size() > 1 &&
        (_isSpaceSlidingLanguage || _spaceSlideOffset != 0f))
    {
      float textY = (keyH - p.ascent() - p.descent()) / 2f + y;
      float kw = _spaceKeyWidth > 0 ? _spaceKeyWidth : 300f;
      float pad = _config.keyPadding;
      float clipLeft = x - kw / 2f + pad;
      float clipRight = x + kw / 2f - pad;

      canvas.save();
      canvas.clipRect(clipLeft, y, clipRight, y + keyH);

      float currentX = x + _spaceSlideOffset;
      canvas.drawText(label, currentX, textY, p);

      int total = _config.layouts.size();
      float spacing = Math.max(kw * 0.55f, p.measureText(label) / 2f + 40f);
      float threshold = kw * 0.35f;
      boolean reached = Math.abs(_spaceSlideOffset) >= threshold;

      if (_spaceSlideOffset < 0)
      {
        int nextIdx = (_config.get_current_layout() + 1) % total;
        String nextLang = getLanguageLabel(_config.layouts.get(nextIdx));
        if (nextLang != null)
        {
          Paint pNext = new Paint(p);
          if (reached)
            pNext.setColor(_theme.colorKeyActivated);
          canvas.drawText(nextLang, currentX + spacing, textY, pNext);
        }
      }
      else if (_spaceSlideOffset > 0)
      {
        int prevIdx = (_config.get_current_layout() - 1 + total) % total;
        String prevLang = getLanguageLabel(_config.layouts.get(prevIdx));
        if (prevLang != null)
        {
          Paint pPrev = new Paint(p);
          if (reached)
            pPrev.setColor(_theme.colorKeyActivated);
          canvas.drawText(prevLang, currentX - spacing, textY, pPrev);
        }
      }

      canvas.restore();
      return;
    }

    float textY = (keyH - p.ascent() - p.descent()) / 2f + y;
    if ((_config == null || _config.isUserModeBottomRow) &&
        (label.equals(",") || label.equals(".") || label.equals(";") || label.equals(":")))
      textY += PUNCTUATION_LABEL_Y_OFFSET * keyH;
    canvas.drawText(label, x, textY, p);
  }

  private static String getLanguageLabel(KeyboardData kb)
  {
    if (kb == null || kb.name == null)
      return null;
    String name = kb.name.trim();
    String nameLower = name.toLowerCase(java.util.Locale.ROOT);
    if (nameLower.contains("ukrain") || nameLower.contains("українськ"))
      return "українська";
    if (nameLower.contains("deutsch") || nameLower.contains("german"))
      return "deutsch";
    if (nameLower.equals("colemak") || nameLower.equals("dvorak") || nameLower.equals("workman"))
      return "english";
    int start = name.indexOf('(');
    int end = name.lastIndexOf(')');
    if (start != -1 && end != -1 && end > start)
    {
      String inner = name.substring(start + 1, end).trim();
      String innerLower = inner.toLowerCase(java.util.Locale.ROOT);
      if (innerLower.equals("us") || innerLower.equals("uk") || innerLower.equals("gb") ||
          innerLower.equals("ca") || innerLower.equals("au") || innerLower.equals("en") ||
          innerLower.equals("english"))
        return "english";
      if (innerLower.contains("ukrain") || innerLower.contains("українськ"))
        return "українська";
      if (innerLower.contains("deutsch") || innerLower.contains("german"))
        return "deutsch";
      int comma = inner.indexOf(',');
      if (comma != -1)
        inner = inner.substring(0, comma).trim();
      int withIdx = innerLower.indexOf(" with");
      if (withIdx != -1)
        inner = inner.substring(0, withIdx).trim();
      return inner.toLowerCase(java.util.Locale.ROOT);
    }
    return name.toLowerCase(java.util.Locale.ROOT);
  }

  private void drawSubLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyW, float keyH, int sub_index, boolean isMainKeyDown,
      Theme.Computed.Key tc, boolean isLightSubLabel)
  {
    Paint.Align a = LABEL_POSITION_H[sub_index];
    Vertical v = LABEL_POSITION_V[sub_index];
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    boolean isCaps = (kv.getKind() == KeyValue.Kind.Event && kv.getEvent() == KeyValue.Event.CAPS_LOCK);
    if (isCaps)
    {
      int flags = _pointers.getKeyFlags(kv);
      if (flags != -1 && (flags & Pointers.FLAG_P_LOCKED) != 0)
        return;
    }
    float textSize = scaleTextSize(kv, false);
    Paint p = tc.sublabel_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(kv, isMainKeyDown, false, true, isLightSubLabel, false), textSize, a);
    float subPadding = _config.keyPadding;
    if (v == Vertical.CENTER)
      y += (keyH - p.ascent() - p.descent()) / 2f;
    else
      y += (v == Vertical.TOP) ? subPadding - p.ascent() : keyH - subPadding - p.descent();
    if (a == Paint.Align.CENTER)
      x += keyW / 2f;
    else
      x += (a == Paint.Align.LEFT) ? subPadding : keyW - subPadding;
    String label = kv.getString();
    int label_len = label.length();
    // Limit the label of string keys to 3 characters
    if (label_len > 3 && kv.getKind() == KeyValue.Kind.String)
      label_len = 3;
    canvas.drawText(label, 0, label_len, x, y, p);
  }

  private void drawIndication(Canvas canvas, KeyboardData.Key k, float x,
      float y, float keyW, float keyH, Theme.Computed tc)
  {
    if (k.indication == null || k.indication.equals(""))
      return;
    Paint p = tc.indication_paint;
    p.setTextSize(_subLabelSize);
    canvas.drawText(k.indication, 0, k.indication.length(),
        x + keyW / 2f, (keyH - p.ascent() - p.descent()) * 4/5 + y, p);
  }

  private float scaleTextSize(KeyValue k, boolean main_label)
  {
    float smaller_font = k.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) ? 0.75f : 1.f;
    float label_size = main_label ? _mainLabelSize : _subLabelSize;
    return label_size * smaller_font;
  }

  public static String layoutIdToLanguageCode(String id)
  {
    if (id == null || id.isEmpty() || id.equals("system"))
      return null;
    id = id.toLowerCase(java.util.Locale.ROOT);
    // Explicit overrides for layouts without country suffixes or non-standard naming
    if (id.equals("latn_qwerty_us") || id.equals("latn_qwerty_gb") ||
        id.equals("latn_colemak") || id.equals("latn_dvorak") ||
        id.equals("latn_workman_us") || id.equals("shaw_imperial_en"))
      return "en";
    if (id.equals("latn_qwerty_pl"))
      return "pl";
    if (id.equals("cyrl_jcuken_uk") || id.equals("cyrl_jiuken"))
      return "uk";
    if (id.startsWith("arab_"))
    {
      if (id.endsWith("_ir") || id.contains("_fa")) return "fa";
      if (id.contains("_ckb")) return "ckb";
      if (id.endsWith("_tly")) return "tly";
      return "ar";
    }
    if (id.startsWith("armn_")) return "hy";
    if (id.startsWith("deva_")) return "hi";
    if (id.startsWith("georgian_")) return "ka";
    if (id.startsWith("grek_")) return "el";
    if (id.startsWith("guj_")) return "gu";
    if (id.startsWith("hang_")) return "ko";
    if (id.startsWith("hebr_")) return "he";
    if (id.startsWith("kann_")) return "kn";
    if (id.startsWith("sinhala_")) return "si";
    if (id.startsWith("tamil_")) return "ta";
    if (id.startsWith("beng_")) return id.contains("assamese") ? "as" : "bn";
    if (id.equals("cyrl_ueishsht")) return "bg";
    if (id.equals("cyrl_yaverti") || id.equals("cyrl_yawerty")) return "ru";
    if (id.equals("cyrl_yqukeng_tj")) return "tg";
    if (id.equals("cyrl_yxukeng_os")) return "os";
    if (id.equals("latn_bone") || id.equals("latn_neo2") || id.equals("latn_qwertz")) return "de";
    if (id.equals("latin_kbdtuf_tr")) return "tr";

    int lastUnder = id.lastIndexOf('_');
    if (lastUnder != -1 && lastUnder < id.length() - 1)
    {
      String suffix = id.substring(lastUnder + 1);
      if (suffix.equals("us") || suffix.equals("gb")) return "en";
      if (suffix.equals("cz")) return "cs";
      if (suffix.equals("se")) return "sv";
      if (suffix.equals("br") || suffix.equals("pt")) return "pt";
      if (suffix.equals("jp")) return "ja";
      if (suffix.equals("kr")) return "ko";
      if (suffix.equals("il")) return "he";
      if (suffix.equals("be") || suffix.equals("ch")) return "fr";
      if (suffix.equals("tj")) return "tg";
      if (suffix.length() == 2)
        return suffix;
    }
    return null;
  }

  public String getNextLanguageBadge()
  {
    if (_config == null || _config.layouts == null || _config.layouts.size() <= 1)
      return "";
    int total = _config.layouts.size();
    int nextIdx = (_config.get_current_layout() + 1) % total;
    KeyboardData nextKb = _config.layouts.get(nextIdx);

    String layoutId = null;
    if (_config.layout_ids != null && nextIdx < _config.layout_ids.size())
      layoutId = _config.layout_ids.get(nextIdx);

    // Fallback: match nextKb.name with pref_layout_entries
    if (layoutId == null && nextKb != null && nextKb.name != null)
    {
      Resources res = getResources();
      List<String> names = LayoutsPreference.get_layout_names(res);
      String[] entries = res.getStringArray(R.array.pref_layout_entries);
      for (int i = 0; i < Math.min(names.size(), entries.length); i++)
      {
        if (nextKb.name.equals(entries[i]))
        {
          layoutId = names.get(i);
          break;
        }
      }
    }

    if (layoutId == null || layoutId.equals("system"))
    {
      String lang = null;
      if (_config.device_locales != null && _config.device_locales.default_ != null)
      {
        if (_config.device_locales.default_.default_layout != null)
        {
          String code = layoutIdToLanguageCode(_config.device_locales.default_.default_layout);
          if (code != null)
            return code;
        }
        lang = _config.device_locales.default_.lang_tag;
      }
      if (lang == null || lang.isEmpty())
        lang = getResources().getConfiguration().locale.getLanguage();
      if (lang != null && !lang.isEmpty())
      {
        int dash = lang.indexOf('-');
        if (dash != -1)
          lang = lang.substring(0, dash);
        return lang.substring(0, Math.min(2, lang.length())).toLowerCase(java.util.Locale.ROOT);
      }
      return "en";
    }

    String code = layoutIdToLanguageCode(layoutId);
    if (code != null)
      return code;

    // Fallback for custom layouts: extract from parentheses or start of name
    if (nextKb != null && nextKb.name != null)
    {
      int start = nextKb.name.indexOf('(');
      int end = nextKb.name.lastIndexOf(')');
      if (start != -1 && end != -1 && end > start + 1)
      {
        String inner = nextKb.name.substring(start + 1, end).trim();
        if (inner.length() >= 2)
          return inner.substring(0, 2).toLowerCase(java.util.Locale.ROOT);
      }
      return nextKb.name.substring(0, Math.min(2, nextKb.name.length())).toLowerCase(java.util.Locale.ROOT);
    }
    return "en";
  }

  private static final KeyValue KV_HOME = KeyValue.getKeyByName("home");
  private static final KeyValue KV_END = KeyValue.getKeyByName("end");
  private static final KeyValue KV_UNDO = KeyValue.getKeyByName("undo");
  private static final KeyValue KV_REDO = KeyValue.getKeyByName("redo");
  private static final KeyValue KV_SELECT_ALL = KeyValue.getKeyByName("selectAll");
  private static final KeyValue KV_CUT = KeyValue.getKeyByName("cut");
  private static final KeyValue KV_COPY = KeyValue.getKeyByName("copy");
  private static final KeyValue KV_PASTE = KeyValue.getKeyByName("paste");
  private static final KeyValue KV_SWITCH_FORWARD = KeyValue.getKeyByName("switch_forward");
  private static final KeyValue KV_SWITCH_BACKWARD = KeyValue.getKeyByName("switch_backward");
  private static final KeyValue KV_SWITCH_CLIPBOARD = KeyValue.getKeyByName("switch_clipboard");

  private void animateSpaceSnapBack()
  {
    if (_spaceSnapAnim != null)
    {
      _spaceSnapAnim.cancel();
      _spaceSnapAnim = null;
    }
    if (_spaceSlideOffset == 0f)
    {
      _isSpaceSlidingLanguage = false;
      invalidate();
      return;
    }
    ValueAnimator anim = ValueAnimator.ofFloat(_spaceSlideOffset, 0f);
    _spaceSnapAnim = anim;
    anim.setDuration(120);
    anim.addUpdateListener(a -> {
      _spaceSlideOffset = (Float)a.getAnimatedValue();
      invalidate();
    });
    anim.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        _isSpaceSlidingLanguage = false;
        _spaceSlideOffset = 0f;
        _spaceSnapAnim = null;
        invalidate();
      }
    });
    anim.start();
  }

  private void executeArcAction(ActionArcMenu.ActionType action)
  {
    if (action == null || _config == null || _config.handler == null)
      return;
    KeyValue kv = null;
    switch (action)
    {
      case HOME: kv = KV_HOME; break;
      case END: kv = KV_END; break;
      case UNDO: kv = KV_UNDO; break;
      case REDO: kv = KV_REDO; break;
      case SELECT_ALL: kv = KV_SELECT_ALL; break;
      case CUT: kv = KV_CUT; break;
      case COPY: kv = KV_COPY; break;
      case PASTE: kv = KV_PASTE; break;
      case LANGUAGE_SWITCH: kv = KV_SWITCH_FORWARD; break;
      case CLIPBOARD: kv = KV_SWITCH_CLIPBOARD; break;
    }
    if (kv != null)
    {
      _config.handler.key_up(kv, Pointers.Modifiers.EMPTY);
    }
  }
}
