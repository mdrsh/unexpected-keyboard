package juloo.keyboard2;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

public final class VibratorCompat
{
  private static Vibrator _vibrator = null;
  private static boolean _initialized = false;
  private static boolean _hasVibrator = false;
  private static VibrationEffect _tickEffect = null;
  private static AudioAttributes _touchAttributes = null;

  private static void init(Context context)
  {
    if (_initialized)
      return;
    _initialized = true;
    try
    {
      _vibrator = (Vibrator)context.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
      _hasVibrator = (_vibrator != null && _vibrator.hasVibrator());
      if (_hasVibrator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
      {
        _tickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        _touchAttributes = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();
      }
    }
    catch (Exception e)
    {
      _hasVibrator = false;
    }
  }

  public static void vibrate(View v, Config config)
  {
    if (config.vibrate_custom)
    {
      if (config.vibrate_duration > 0)
        vibrator_vibrate(v, config.vibrate_duration);
      return;
    }

    init(v.getContext());

    if (_tickEffect != null)
    {
      try
      {
        _vibrator.vibrate(_tickEffect, _touchAttributes);
        return;
      }
      catch (Exception e)
      {
        // Fallback to performHapticFeedback
      }
    }

    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK,
        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
  }

  /** Use the older [Vibrator] when the newer API is not available or the user
      wants more control. */
  static void vibrator_vibrate(View v, long duration)
  {
    init(v.getContext());
    if (_hasVibrator)
    {
      try
      {
        _vibrator.vibrate(duration);
      }
      catch (Exception e) {}
    }
  }
}

