package juloo.keyboard2;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.view.inputmethod.InputConnection;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
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
  private float _potentialArcKeyTopY = 0f;
  private float _potentialArcExitX = -1f;
  private boolean _isSpaceSlidingLanguage = false;
  private float _spaceSlideOffset = 0f;
  private float _spaceKeyWidth = 0f;
  private boolean _hapticFiredForThreshold = false;
  private ValueAnimator _spaceSnapAnim = null;
  private float _mainKeyboardBoundaryY = -1f;
  private float _mainKeyboardHeight = -1f;

  private static final int TRACKPAD_MODE_NONE = 0;
  private static final int TRACKPAD_MODE_CURSOR = 1;
  private static final int TRACKPAD_MODE_SELECTION = 2;
  private static final int TRACKPAD_HOLD_DELAY_CURSOR_MS = 100;
  private static final int TRACKPAD_HOLD_DELAY_SELECTION_MS = 180;

  public interface TrackpadListener
  {
    boolean onTrackpadStateChanged(boolean armed, String label, int textColor, int bgColor);
  }

  private TrackpadListener _trackpadListener = null;
  private boolean _trackpadOverlayHandledExternally = false;

  public void setTrackpadListener(TrackpadListener listener)
  {
    _trackpadListener = listener;
  }

  private final Handler _trackpadHandler = new Handler(Looper.getMainLooper());
  private boolean _trackpadArmed = false;
  private KeyboardData.Key _trackpadTriggerKey = null;
  private final RectF _trackpadTriggerRect = new RectF();
  private final Paint _trackpadDimPaint = new Paint();
  private final Paint _trackpadBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _trackpadBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final Runnable _trackpadArmRunnable = new Runnable()
  {
    @Override
    public void run()
    {
      armTrackpad();
    }
  };

  private void armTrackpad()
  {
    if (_trackpadTriggerPointerId == -1 || _trackpadArmed)
      return;
    _trackpadHandler.removeCallbacks(_trackpadArmRunnable);
    _trackpadArmed = true;
    _trackpadTriggerConsumed = true;
    _pointers.stopPointerLongPress(_trackpadTriggerPointerId);
    if (_trackpadTriggerMode == TRACKPAD_MODE_CURSOR)
    {
      _pointers.clearShiftModifier();
    }
    if (_trackpadPointerId != -1)
    {
      _pointers.cancelPointer(_trackpadPointerId);
      _trackpadActive = true;
    }
    String label = (_trackpadTriggerMode == TRACKPAD_MODE_SELECTION) ? "Виділення" : "Переміщення курсору";
    int textColor = (_theme != null && _theme.subLabelNumberRowColor != 0)
        ? _theme.subLabelNumberRowColor
        : ((_theme != null) ? _theme.subLabelColor : Color.WHITE);
    int baseBg = (_theme != null)
        ? ((_theme.hasKeyboardGradient && _theme.keyboardGradientStart != 0) ? _theme.keyboardGradientStart : _theme.colorKeyboard)
        : 0xFF181818;
    if (baseBg == 0) baseBg = 0xFF181818;
    int bgColor = Color.argb(245, Color.red(baseBg), Color.green(baseBg), Color.blue(baseBg));
    boolean handled = false;
    if (_trackpadListener != null)
    {
      handled = _trackpadListener.onTrackpadStateChanged(true, label, textColor, bgColor);
    }
    _trackpadOverlayHandledExternally = handled;
    invalidate();
  }

  private void cancelTrackpadArming()
  {
    _trackpadHandler.removeCallbacks(_trackpadArmRunnable);
    _trackpadTriggerPointerId = -1;
    _trackpadTriggerKey = null;
    _trackpadTriggerMode = TRACKPAD_MODE_NONE;
    _trackpadTriggerConsumed = false;
    _trackpadArmed = false;
    _trackpadActive = false;
    _trackpadPointerId = -1;
    _trackpadAccumX = 0f;
    _trackpadAccumY = 0f;
    if (_trackpadListener != null)
    {
      _trackpadListener.onTrackpadStateChanged(false, null, 0, 0);
    }
    _trackpadOverlayHandledExternally = false;
    invalidate();
  }

  private int _trackpadTriggerPointerId = -1;
  private int _trackpadTriggerMode = TRACKPAD_MODE_NONE;
  private float _trackpadTriggerDownX = 0f;
  private float _trackpadTriggerDownY = 0f;
  private boolean _trackpadTriggerConsumed = false;
  private int _trackpadPointerId = -1;
  private boolean _trackpadActive = false;
  private float _trackpadStartX = 0f;
  private float _trackpadStartY = 0f;
  private float _trackpadLastX = 0f;
  private float _trackpadLastY = 0f;
  private float _trackpadAccumX = 0f;
  private float _trackpadAccumY = 0f;

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
    _trackpadDimPaint.setColor(Color.argb(140, 0, 0, 0));
    _trackpadBadgePaint.setColor(Color.argb(215, 30, 30, 30));
    _trackpadBadgeTextPaint.setColor(Color.WHITE);
    _trackpadBadgeTextPaint.setTextAlign(Paint.Align.CENTER);
    _trackpadBadgeTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
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
    _trackpadHandler.removeCallbacks(_trackpadArmRunnable);
    _trackpadArmed = false;
    _trackpadTriggerPointerId = -1;
    _trackpadTriggerKey = null;
    _trackpadTriggerMode = TRACKPAD_MODE_NONE;
    _trackpadTriggerConsumed = false;
    _trackpadPointerId = -1;
    _trackpadActive = false;
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

  public boolean isTrackpadArmed()
  {
    return _trackpadArmed;
  }

  public boolean isSliding()
  {
    return _pointers != null && _pointers.isSliding();
  }

  /** Called by auto-capitalisation. */
  public void set_shift_state(boolean latched, boolean lock)
  {
    if (_trackpadArmed || isSliding())
      return;
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
    if (_trackpadArmed)
      return;
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
    if (k != null && k.getKind() == KeyValue.Kind.Editing && k.getEditing() == KeyValue.Editing.DELETE_WORD)
      vibrate();
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
            vibrate();
            executeArcAction(action);
          }
          return (true);
        }
        else if (_isSpaceSlidingLanguage && upPointerId == _potentialArcPointerId)
        {
          float kw = _spaceKeyWidth > 0 ? _spaceKeyWidth : 300f;
          float threshold = kw * 0.30f;
          if (_spaceSlideOffset <= -threshold)
          {
            vibrate();
            executeArcAction(ActionArcMenu.ActionType.LANGUAGE_SWITCH);
            _isSpaceSlidingLanguage = false;
            _spaceSlideOffset = 0f;
            invalidate();
          }
          else if (_spaceSlideOffset >= threshold)
          {
            vibrate();
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
        if (_potentialArcPointerId != -1 && upPointerId == _potentialArcPointerId)
        {
          _potentialArcPointerId = -1;
        }

        if (upPointerId == _trackpadTriggerPointerId)
        {
          _trackpadHandler.removeCallbacks(_trackpadArmRunnable);
          boolean wasArmedOrConsumed = _trackpadArmed || _trackpadTriggerConsumed;
          _trackpadTriggerPointerId = -1;
          _trackpadTriggerKey = null;
          _trackpadTriggerMode = TRACKPAD_MODE_NONE;
          _trackpadTriggerConsumed = false;
          _trackpadArmed = false;
          _trackpadActive = false;
          _trackpadPointerId = -1;
          _trackpadAccumX = 0f;
          _trackpadAccumY = 0f;
          if (_trackpadListener != null)
          {
            _trackpadListener.onTrackpadStateChanged(false, null, 0, 0);
          }
          _trackpadOverlayHandledExternally = false;
          if (wasArmedOrConsumed)
          {
            _pointers.cancelPointer(upPointerId);
            invalidate();
            if (_config != null && _config.handler != null)
              _config.handler.sync_selection();
            return (true);
          }
        }
        else if (upPointerId == _trackpadPointerId)
        {
          _trackpadActive = false;
          _trackpadPointerId = -1;
          _trackpadAccumX = 0f;
          _trackpadAccumY = 0f;
          if (_trackpadArmed)
          {
            invalidate();
            return (true);
          }
        }

        boolean wasSliding = _pointers.isSliding();
        _pointers.onTouchUp(upPointerId);
        if (wasSliding && !_pointers.isSliding())
        {
          if (_config != null && _config.handler != null)
            _config.handler.sync_selection();
        }
        break;
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_POINTER_DOWN:
        p = event.getActionIndex();
        float tx = event.getX(p);
        float ty = event.getY(p);
        int downPointerId = event.getPointerId(p);

        if (_trackpadArmed && _trackpadTriggerPointerId != -1 && downPointerId != _trackpadTriggerPointerId)
        {
          _trackpadPointerId = downPointerId;
          _trackpadActive = true;
          _trackpadStartX = tx;
          _trackpadStartY = ty;
          _trackpadLastX = tx;
          _trackpadLastY = ty;
          _trackpadAccumX = 0f;
          _trackpadAccumY = 0f;
          return (true);
        }

        KeyboardData.Key key = getKeyAtPosition(tx, ty);
        if (key != null)
        {
          _pointers.onTouchDown(tx, ty, downPointerId, key);
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
            _potentialArcPointerId = downPointerId;
            _potentialArcDownX = tx;
            _potentialArcDownY = ty;
            _potentialArcMinY = ty;
            _potentialArcTurnX = tx;
            _potentialArcIsSpace = isSpace;
            _potentialArcKeyTopY = getKeyRowTopY(ty);
            _potentialArcExitX = -1f;
            _isSpaceSlidingLanguage = false;
            _spaceSlideOffset = 0f;
            _hapticFiredForThreshold = false;
          }

          int trigMode = getTrackpadTriggerMode(key, _pointers.getPointerValue(downPointerId));
          if (trigMode != TRACKPAD_MODE_NONE)
          {
            _trackpadTriggerPointerId = downPointerId;
            _trackpadTriggerKey = key;
            _trackpadTriggerMode = trigMode;
            _trackpadTriggerDownX = tx;
            _trackpadTriggerDownY = ty;
            _trackpadTriggerConsumed = false;
            _trackpadArmed = false;
            _trackpadActive = false;
            _trackpadPointerId = -1;
            _trackpadHandler.removeCallbacks(_trackpadArmRunnable);
            int delay = (trigMode == TRACKPAD_MODE_SELECTION)
                ? TRACKPAD_HOLD_DELAY_SELECTION_MS
                : TRACKPAD_HOLD_DELAY_CURSOR_MS;
            _trackpadHandler.postDelayed(_trackpadArmRunnable, delay);
          }
          else if (!_trackpadArmed && _trackpadTriggerPointerId != -1 && downPointerId != _trackpadTriggerPointerId)
          {
            _trackpadPointerId = downPointerId;
            _trackpadActive = false;
            _trackpadStartX = tx;
            _trackpadStartY = ty;
            _trackpadLastX = tx;
            _trackpadLastY = ty;
            _trackpadAccumX = 0f;
            _trackpadAccumY = 0f;
          }
        }
        break;
      case MotionEvent.ACTION_MOVE:
        if (!_trackpadArmed && _trackpadTriggerPointerId != -1)
        {
          int trigIdx = event.findPointerIndex(_trackpadTriggerPointerId);
          if (trigIdx != -1)
          {
            float tdx = event.getX(trigIdx) - _trackpadTriggerDownX;
            float tdy = event.getY(trigIdx) - _trackpadTriggerDownY;
            boolean isGesture = _pointers.isPointerGesturing(_trackpadTriggerPointerId);
            float swipeDist = (_config != null && _config.swipe_dist_px > 0) ? _config.swipe_dist_px : 60f;
            float threshold = swipeDist * 0.75f;
            if (isGesture || (tdx * tdx + tdy * tdy >= threshold * threshold))
            {
              // Finger moved on trigger key: user is swiping/gesturing on it (e.g. autocorrect toggle, caps lock).
              cancelTrackpadArming();
            }
          }

          if (!_trackpadArmed && _trackpadTriggerPointerId != -1 && _trackpadPointerId != -1)
          {
            int padIdx = event.findPointerIndex(_trackpadPointerId);
            if (padIdx != -1)
            {
              float pdx = event.getX(padIdx) - _trackpadStartX;
              float pdy = event.getY(padIdx) - _trackpadStartY;
              float slideThreshold = (_config != null && _config.slide_step_px > 0) ? (_config.slide_step_px * 0.5f) : 18f;
              if (pdx * pdx + pdy * pdy >= slideThreshold * slideThreshold)
              {
                armTrackpad();
              }
            }
          }
        }

        if (_trackpadArmed && _trackpadPointerId != -1)
        {
          int padIdx = event.findPointerIndex(_trackpadPointerId);
          if (padIdx != -1)
          {
            float curX = event.getX(padIdx);
            float curY = event.getY(padIdx);
            float dx = curX - _trackpadLastX;
            float dy = curY - _trackpadLastY;
            _trackpadLastX = curX;
            _trackpadLastY = curY;

            float absDx = Math.abs(dx);
            float absDy = Math.abs(dy);

            float stepX = (_config != null && _config.slide_step_px > 0) ? _config.slide_step_px : 35f;
            float stepY = stepX * 2.2f;

            // Bezel protection: when the finger reaches the left or right edge of the screen,
            // horizontal dx drops to 0 while the thumb rolls or slides against the bezel.
            // Do not allow bezel friction to accumulate vertical drift and jump lines!
            boolean isNearHorizontalEdge = (curX <= 30f || curX >= getWidth() - 30f);
            boolean isBezelFriction = isNearHorizontalEdge && (absDx < 2.5f);

            if (isBezelFriction)
            {
              // Finger stopped at bezel: cancel any vertical drift
              _trackpadAccumY = 0f;
            }
            else if (absDx >= absDy * 0.85f)
            {
              // Horizontal scrub (including natural ergonomic thumb tilt up to ~50°):
              // Accumulate X and strictly zero vertical accumulator so horizontal navigation NEVER jumps lines!
              _trackpadAccumX += dx;
              _trackpadAccumY = 0f;
            }
            else if (absDy > absDx * 1.25f)
            {
              // Deliberate vertical movement (clearly up or down):
              // Accumulate Y and zero horizontal accumulator so line jumping doesn't jitter sideways
              _trackpadAccumY += dy;
              _trackpadAccumX = 0f;
            }
            else
            {
              // Ambiguous transition zone (around 45°-50°):
              // Default to horizontal text navigation for stability
              _trackpadAccumX += dx;
              _trackpadAccumY = 0f;
            }

            if (Math.abs(_trackpadAccumX) >= stepX)
            {
              int stepsX = (int) (_trackpadAccumX / stepX);
              _trackpadAccumX -= stepsX * stepX;
              _trackpadAccumY = 0f;
              if (_config != null && _config.handler != null)
                _config.handler.move_trackpad(stepsX, 0, _trackpadTriggerMode == TRACKPAD_MODE_SELECTION);
            }

            if (Math.abs(_trackpadAccumY) >= stepY)
            {
              int stepsY = (int) (_trackpadAccumY / stepY);
              _trackpadAccumY -= stepsY * stepY;
              _trackpadAccumX = 0f;
              if (_config != null && _config.handler != null)
                _config.handler.move_trackpad(0, stepsY, _trackpadTriggerMode == TRACKPAD_MODE_SELECTION);
            }

            return (true);
          }
        }
        if (_arcMenu.isActive())
        {
          int arcIdx = event.findPointerIndex(_potentialArcPointerId);
          if (arcIdx != -1)
          {
            _arcMenu.updateTouch(event.getX(arcIdx), event.getY(arcIdx));
            if (_arcMenu.checkAndClearHapticTick())
            {
              vibrate();
            }
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
            float threshold = kw * 0.30f;
            if (Math.abs(_spaceSlideOffset) >= threshold)
            {
              if (!_hapticFiredForThreshold)
              {
                vibrate();
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
            float triggerY = getMiddleBottomLetterRowBoundary();
            float fanCenterY = triggerY + (_potentialArcDownY - triggerY) * 0.20f;

            boolean isUpward = (dy < 0);
            float distUp = isUpward ? -dy : 0f;
            float distSide = Math.abs(dx);

            // Cone of 25 degrees from vertical on each side (50 degrees total cone):
            // tan(25°) ≈ 0.4663f
            // tan(30°) ≈ 0.57735f
            boolean isWithinArcCone = isUpward && (distSide <= distUp * 0.57735f);

            if (curY < _potentialArcMinY)
            {
              _potentialArcMinY = curY;
              if (isWithinArcCone)
                _potentialArcTurnX = curX;
            }

            if (_potentialArcExitX < 0f && curY <= _potentialArcKeyTopY)
            {
              _potentialArcExitX = curX;
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
                float fanCenterX = (_potentialArcExitX >= 0f) ? _potentialArcExitX : curX;
                _arcMenu.start(fanCenterX, _potentialArcDownY, _tc.row_height, fanCenterY,
                    _potentialArcIsSpace && hasMultipleLayouts, getNextLanguageBadge(), getContext(), getWidth(), getHeight());
                _arcMenu.updateTouch(curX, curY);
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
        cancelTrackpadArming();
        boolean wasSlidingCancel = _pointers.isSliding();
        _pointers.onTouchCancel();
        if (wasSlidingCancel && !_pointers.isSliding())
        {
          if (_config != null && _config.handler != null)
            _config.handler.sync_selection();
        }
        break;
      default:
        return (false);
    }
    return (true);
  }

  private int getTrackpadTriggerMode(KeyboardData.Key key, KeyValue activeValue)
  {
    if (activeValue != null)
    {
      if (isTrackpadShiftValue(activeValue))
        return TRACKPAD_MODE_SELECTION;
      if (isTrackpadCursorValue(activeValue))
        return TRACKPAD_MODE_CURSOR;
    }
    if (key != null && key.keys != null && key.keys.length > 0)
    {
      KeyValue k0 = key.keys[0];
      if (k0 != null)
      {
        if (isTrackpadShiftValue(k0))
          return TRACKPAD_MODE_SELECTION;
        if (isTrackpadCursorValue(k0))
          return TRACKPAD_MODE_CURSOR;
      }
      if (key.role == KeyboardData.Key.Role.Action)
      {
        for (KeyValue k : key.keys)
        {
          if (k == null) continue;
          if (isTrackpadShiftValue(k))
            return TRACKPAD_MODE_SELECTION;
          if (isTrackpadCursorValue(k))
            return TRACKPAD_MODE_CURSOR;
        }
      }
    }
    return TRACKPAD_MODE_NONE;
  }

  private boolean isTrackpadShiftValue(KeyValue kv)
  {
    if (kv == null) return false;
    return kv.equals(KeyValue.SHIFT) ||
        (kv.getKind() == KeyValue.Kind.Modifier && kv.getModifier() == KeyValue.Modifier.SHIFT) ||
        (kv.getKind() == KeyValue.Kind.Event && kv.getEvent() == KeyValue.Event.CAPS_LOCK);
  }

  private boolean isTrackpadCursorValue(KeyValue kv)
  {
    if (kv == null) return false;
    if (kv.getKind() == KeyValue.Kind.Event)
    {
      KeyValue.Event ev = kv.getEvent();
      return ev == KeyValue.Event.SWITCH_NUMERIC || ev == KeyValue.Event.SWITCH_TEXT;
    }
    if (kv.getKind() == KeyValue.Kind.Modifier && kv.getModifier() == KeyValue.Modifier.FN)
      return true;
    return false;
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

  private float getKeyRowTopY(float ty)
  {
    if (_keyboard == null || _tc == null)
      return ty;
    float y = _config.marginTop;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      float rowTop = y + row.shift * _tc.row_height;
      y = rowTop + row.height * _tc.row_height;
      if (ty < y)
        return rowTop;
    }
    return ty;
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
        if (k == _trackpadTriggerKey)
        {
          _trackpadTriggerRect.set(x, y, x + keyW, y + keyH);
        }
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
    if (_trackpadArmed)
    {
      drawTrackpadOverlay(canvas);
    }
    if (_arcMenu.isActive())
    {
      _arcMenu.draw(canvas, _theme);
    }
  }

  private void drawTrackpadOverlay(Canvas canvas)
  {
    canvas.save();
    if (_trackpadTriggerRect != null && !_trackpadTriggerRect.isEmpty())
    {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      {
        canvas.clipOutRect(_trackpadTriggerRect);
      }
      else
      {
        canvas.clipRect(_trackpadTriggerRect, Region.Op.DIFFERENCE);
      }
    }
    canvas.drawRect(0, 0, getWidth(), getHeight(), _trackpadDimPaint);
    canvas.restore();

    if (_trackpadOverlayHandledExternally)
      return;

    String label = (_trackpadTriggerMode == TRACKPAD_MODE_SELECTION) ? "Виділення" : "Переміщення курсору";
    int textColor = (_theme != null && _theme.subLabelNumberRowColor != 0)
        ? _theme.subLabelNumberRowColor
        : ((_theme != null) ? _theme.subLabelColor : Color.WHITE);
    _trackpadBadgeTextPaint.setColor(textColor);
    _trackpadBadgeTextPaint.setTypeface(Typeface.DEFAULT);
    _trackpadBadgeTextPaint.setFakeBoldText(false);
    _trackpadBadgeTextPaint.setTextSize(_tc.row_height * 0.28f);
    float textW = _trackpadBadgeTextPaint.measureText(label);
    float badgeH = _tc.row_height * 0.44f;
    float badgeW = textW + badgeH;
    float cx = getWidth() / 2f;
    float cy = _tc.margin_top + badgeH * 0.75f;
    int baseBg = (_theme != null)
        ? ((_theme.hasKeyboardGradient && _theme.keyboardGradientStart != 0) ? _theme.keyboardGradientStart : _theme.colorKeyboard)
        : 0xFF181818;
    if (baseBg == 0) baseBg = 0xFF181818;
    int badgeBgColor = Color.argb(235, Color.red(baseBg), Color.green(baseBg), Color.blue(baseBg));
    _trackpadBadgePaint.setColor(badgeBgColor);
    RectF badgeRect = new RectF(cx - badgeW / 2f, cy - badgeH / 2f, cx + badgeW / 2f, cy + badgeH / 2f);
    canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, _trackpadBadgePaint);
    float textY = cy - (_trackpadBadgeTextPaint.descent() + _trackpadBadgeTextPaint.ascent()) / 2f;
    canvas.drawText(label, cx, textY, _trackpadBadgeTextPaint);
  }

  @Override
  public void onDetachedFromWindow()
  {
    cancelTrackpadArming();
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
      if (k.equals(KeyValue.AUTO_REPLACE_TOGGLE))
      {
        return _theme.hasActionLabelColor ? _theme.actionLabelColor : (_theme.lockedColor != 0 ? _theme.lockedColor : 0xFF00C0FF);
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
      float threshold = kw * 0.30f;
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
    if (kv.equals(KeyValue.AUTO_REPLACE_TOGGLE))
    {
      int flags = _pointers.getKeyFlags(kv);
      label = (flags != -1) ? "✖" : "↺";
    }
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
      case CUT_ALL: executeCutAll(); return;
      case COPY: kv = KV_COPY; break;
      case COPY_WORD: executeCopyWord(); return;
      case COPY_ALL: executeCopyAll(); return;
      case PASTE: kv = KV_PASTE; break;
      case LANGUAGE_SWITCH: kv = KV_SWITCH_FORWARD; break;
      case CLIPBOARD: kv = KV_SWITCH_CLIPBOARD; break;
    }
    if (kv != null)
    {
      _config.handler.key_up(kv, Pointers.Modifiers.EMPTY);
    }
  }

  private void executeCutAll()
  {
    if (_config == null || _config.handler == null)
      return;
    InputConnection conn = _config.handler.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.performContextMenuAction(android.R.id.selectAll);
    postDelayed(new Runnable() {
      @Override
      public void run() {
        InputConnection c = (_config != null && _config.handler != null)
            ? _config.handler.getCurrentInputConnection() : null;
        if (c != null)
          c.performContextMenuAction(android.R.id.cut);
      }
    }, 50);
  }

  private void executeCopyAll()
  {
    if (_config == null || _config.handler == null)
      return;
    InputConnection conn = _config.handler.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.performContextMenuAction(android.R.id.selectAll);
    postDelayed(new Runnable() {
      @Override
      public void run() {
        InputConnection c = (_config != null && _config.handler != null)
            ? _config.handler.getCurrentInputConnection() : null;
        if (c != null)
          c.performContextMenuAction(android.R.id.copy);
      }
    }, 50);
  }

  private void executeCopyWord()
  {
    if (_config == null || _config.handler == null)
      return;
    InputConnection conn = _config.handler.getCurrentInputConnection();
    if (conn == null)
      return;

    CharSequence selected = conn.getSelectedText(0);
    if (selected != null && selected.length() > 0)
    {
      CharSequence before = conn.getTextBeforeCursor(256, 0);
      CharSequence after = conn.getTextAfterCursor(256, 0);
      String snapped = expandSelectionToWordBoundaries(before, selected, after);
      if (snapped != null && !snapped.isEmpty())
        copyToClipboard(snapped);
      else
        copyToClipboard(selected.toString());
      return;
    }

    CharSequence before = conn.getTextBeforeCursor(256, 0);
    CharSequence after = conn.getTextAfterCursor(256, 0);
    String word = extractWordAtCursor(before, after);
    if (word != null && !word.isEmpty())
    {
      copyToClipboard(word);
    }
  }

  private void copyToClipboard(String text)
  {
    if (text == null || text.isEmpty())
      return;
    try
    {
      ClipboardManager cm = (ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null)
      {
        ClipData clip = ClipData.newPlainText("text", text);
        cm.setPrimaryClip(clip);
      }
    }
    catch (Exception e)
    {
      // ignore
    }
  }

  public static String extractWordAtCursor(CharSequence beforeCs, CharSequence afterCs)
  {
    String before = (beforeCs != null) ? beforeCs.toString() : "";
    String after = (afterCs != null) ? afterCs.toString() : "";
    String fullText = before + after;
    if (fullText.isEmpty())
      return null;

    int pos = before.length();

    if (pos >= fullText.length() || Character.isWhitespace(fullText.charAt(pos)))
    {
      if (pos > 0 && !Character.isWhitespace(fullText.charAt(pos - 1)))
      {
        pos = pos - 1;
      }
      else
      {
        int back = pos - 1;
        while (back >= 0 && Character.isWhitespace(fullText.charAt(back)))
        {
          if (fullText.charAt(back) == '\n' || fullText.charAt(back) == '\r')
            break;
          back--;
        }
        if (back >= 0 && !Character.isWhitespace(fullText.charAt(back)))
        {
          pos = back;
        }
        else
        {
          int forward = pos;
          while (forward < fullText.length() && Character.isWhitespace(fullText.charAt(forward)))
          {
            if (fullText.charAt(forward) == '\n' || fullText.charAt(forward) == '\r')
              break;
            forward++;
          }
          if (forward < fullText.length() && !Character.isWhitespace(fullText.charAt(forward)))
          {
            pos = forward;
          }
          else
          {
            return null;
          }
        }
      }
    }

    int start = pos;
    while (start > 0 && !Character.isWhitespace(fullText.charAt(start - 1)))
    {
      start--;
    }
    int end = pos;
    while (end < fullText.length() && !Character.isWhitespace(fullText.charAt(end)))
    {
      end++;
    }

    String token = fullText.substring(start, end);
    return trimWordPunctuation(token);
  }

  public static String trimWordPunctuation(String token)
  {
    if (token == null || token.isEmpty())
      return token;

    int start = 0;
    int end = token.length();

    while (start < end && isTrimPunctuation(token.charAt(start)))
    {
      start++;
    }
    while (end > start && isTrimPunctuation(token.charAt(end - 1)))
    {
      end--;
    }

    if (start >= end)
    {
      return token;
    }
    return token.substring(start, end);
  }

  public static String expandSelectionToWordBoundaries(CharSequence beforeCs, CharSequence selectedCs, CharSequence afterCs)
  {
    String before = (beforeCs != null) ? beforeCs.toString() : "";
    String selected = (selectedCs != null) ? selectedCs.toString() : "";
    String after = (afterCs != null) ? afterCs.toString() : "";

    if (selected.isEmpty())
      return selected;

    StringBuilder sb = new StringBuilder();

    // 1. Expand left edge into `before` if selection started inside a word
    int selStart = 0;
    while (selStart < selected.length() && Character.isWhitespace(selected.charAt(selStart)))
    {
      selStart++;
    }

    if (selStart < selected.length() && isWordChar(selected.charAt(selStart)))
    {
      if (!before.isEmpty() && isWordChar(before.charAt(before.length() - 1)))
      {
        int b = before.length() - 1;
        while (b >= 0 && isWordChar(before.charAt(b)))
        {
          b--;
        }
        sb.append(before.substring(b + 1));
      }
    }

    // 2. Append the main selected content
    sb.append(selected);

    // 3. Expand right edge into `after` if selection ended inside a word
    int selEnd = selected.length() - 1;
    while (selEnd >= 0 && Character.isWhitespace(selected.charAt(selEnd)))
    {
      selEnd--;
    }

    if (selEnd >= 0 && isWordChar(selected.charAt(selEnd)))
    {
      if (!after.isEmpty() && isWordChar(after.charAt(0)))
      {
        int a = 0;
        while (a < after.length() && isWordChar(after.charAt(a)))
        {
          a++;
        }
        sb.append(after.substring(0, a));
      }
    }

    return trimWordPunctuation(sb.toString());
  }

  private static boolean isWordChar(char c)
  {
    return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '\u2019' || c == '\u02BC';
  }

  private static boolean isTrimPunctuation(char c)
  {
    switch (c)
    {
      case '"':
      case '\'':
      case '`':
      case '«':
      case '»':
      case '“':
      case '”':
      case '‘':
      case '’':
      case '„':
      case '(':
      case ')':
      case '[':
      case ']':
      case '{':
      case '}':
      case '<':
      case '>':
      case '.':
      case ',':
      case '!':
      case '?':
      case ':':
      case ';':
      case '…':
      case '~':
      case '*':
      case '^':
        return true;
      default:
        return false;
    }
  }
}
