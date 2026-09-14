package juloo.keyboard2;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.util.Iterator;
import juloo.keyboard2.suggestions.Suggestions;

public final class KeyEventHandler
  implements Config.IKeyEventHandler,
             ClipboardHistoryService.ClipboardPasteCallback,
             CurrentlyTypedWord.Callback
{
  IReceiver _recv;
  Autocapitalisation _autocap;
  Suggestions _suggestions;
  CurrentlyTypedWord _typedword;
  /** State of the system modifiers. It is updated whether a modifier is down
      or up and a corresponding key event is sent. */
  Pointers.Modifiers _mods;
  /** Consistent with [_mods]. This is a mutable state rather than computed
      from [_mods] to ensure that the meta state is correct while up and down
      events are sent for the modifier keys. */
  int _meta_state = 0;
  /** Whether to force sending arrow keys to move the cursor when
      [setSelection] could be used instead. */
  boolean _move_cursor_force_fallback = false;
  /** Whether the space bar automatically enters the best suggestion. */
  boolean _space_bar_auto_complete = false;
  /** Reference to global configuration. */
  Config _config;
  /** Remember the action that was handled. This is used by autocorrect. */
  LastAction _last_action = null;
  LastAction _next_last_action = null;

  enum SmartAction
  {
    NONE,
    ELLIPSIS,             // ... -> …
    EM_DASH,              // -- -> —
    DOUBLE_SPACE_PERIOD,  // " " -> ". "
    AUTO_SPACE,           // inserted " " before text
  }
  SmartAction _last_smart_action = SmartAction.NONE;
  int _last_auto_space_text_len = 0;
  long _last_space_time = 0;

  public KeyEventHandler(IReceiver recv, Suggestions sg)
  {
    _recv = recv;
    Handler handler = recv.getHandler();
    _autocap = new Autocapitalisation(handler,
        this.new Autocapitalisation_callback());
    _mods = Pointers.Modifiers.EMPTY;
    _suggestions = sg;
    _typedword = new CurrentlyTypedWord(handler, this);
  }

  /** Editing just started. */
  public void started(Config conf)
  {
    InputConnection ic = _recv.getCurrentInputConnection();
    _autocap.started(conf, ic);
    _typedword.started(conf, ic);
    _suggestions.started();
    _config = conf;
    _move_cursor_force_fallback =
      conf.editor_config.should_move_cursor_force_fallback;
    _space_bar_auto_complete = conf.space_bar_auto_complete;
    _last_action = null;
    _last_smart_action = SmartAction.NONE;
    _last_space_time = 0;
  }

  boolean should_autocapitalise()
  {
    return _config != null && _config.autocapitalisation
        && (_config.editor_config == null || _config.editor_config.auto_space_allowed)
        && (_mods == null || !_mods.has(KeyValue.Modifier.AUTO_REPLACE_OFF));
  }

  /** Selection has been updated. */
  public void selection_updated(int oldSelStart, int newSelStart, int newSelEnd)
  {
    _autocap.selection_updated(oldSelStart, newSelStart);
    _typedword.selection_updated(oldSelStart, newSelStart, newSelEnd);
    if (should_autocapitalise())
    {
      InputConnection ic = _recv.getCurrentInputConnection();
      if (ic != null)
      {
        CharSequence before = ic.getTextBeforeCursor(60, 0);
        if (is_at_sentence_start(before))
          _recv.set_shift_state(true, false);
      }
    }
  }

  /** A key is being pressed. There will not necessarily be a corresponding
      [key_up] event. */
  @Override
  public void key_down(KeyValue key, boolean isSwipe)
  {
    if (key == null)
      return;
    // Stop auto capitalisation when pressing some keys
    switch (key.getKind())
    {
      case Modifier:
        switch (key.getModifier())
        {
          case CTRL:
          case ALT:
          case META:
            _autocap.stop();
            break;
          case AUTO_REPLACE_OFF:
            _recv.set_shift_state(false, false);
            _autocap.stop();
            break;
        }
        break;
      case Compose_pending:
        _autocap.stop();
        break;
      case Slider:
        // Don't wait for the next key_up and move the cursor right away. This
        // is called after the trigger distance have been travelled.
        handle_slider(key.getSlider(), key.getSliderRepeat(), true);
        break;
      default: break;
    }
  }

  /** A key has been released. */
  @Override
  public void key_up(KeyValue key, Pointers.Modifiers mods)
  {
    if (key == null)
      return;
    _next_last_action = LastAction.OTHER;
    Pointers.Modifiers old_mods = _mods;
    update_meta_state(mods);
    switch (key.getKind())
    {
      case Char: send_text(String.valueOf(key.getChar())); break;
      case String: send_text(key.getString()); break;
      case Event: _recv.handle_event_key(key.getEvent()); break;
      case Keyevent:
        _last_smart_action = SmartAction.NONE;
        _last_space_time = 0;
        send_key_down_up(key.getKeyevent());
        break;
      case Modifier:
        if (key.getModifier() == KeyValue.Modifier.AUTO_REPLACE_OFF)
        {
          if (_mods != null && _mods.has(KeyValue.Modifier.AUTO_REPLACE_OFF))
          {
            _recv.set_shift_state(false, false);
            _autocap.stop();
          }
          else
          {
            if (should_autocapitalise())
            {
              InputConnection ic = _recv.getCurrentInputConnection();
              if (ic != null && is_at_sentence_start(ic.getTextBeforeCursor(60, 0)))
                _recv.set_shift_state(true, false);
            }
          }
        }
        break;
      case Editing: handle_editing_key(key.getEditing()); break;
      case Compose_pending: _recv.set_compose_pending(true); break;
      case Slider: handle_slider(key.getSlider(), key.getSliderRepeat(), false); break;
      case Macro: evaluate_macro(key.getMacro()); break;
      case Stateful: handle_stateful(key.getStateful()); break;
    }
    update_meta_state(old_mods);
    _last_action = _next_last_action;
  }

  @Override
  public void mods_changed(Pointers.Modifiers mods)
  {
    update_meta_state(mods);
  }

  @Override
  public void suggestion_entered(String text)
  {
    _last_smart_action = SmartAction.NONE;
    _last_space_time = (text != null && text.endsWith(" ")) ? SystemClock.uptimeMillis() : 0;
    String old = _typedword.get();
    int cur_rel = _typedword.cursor_relative();
    replace_surrounding_text(old.length() + cur_rel, -cur_rel, text);
    last_replaced_word = old;
    last_replacement_word_len = text.length();
    _next_last_action = LastAction.SUGGESTION_ENTERED;
  }

  @Override
  public void paste_from_clipboard_pane(String content)
  {
    send_text(content);
  }

  @Override
  public void currently_typed_word(String word)
  {
    _suggestions.currently_typed_word(word);
  }

  public void dictionary_changed()
  {
    // Refresh the suggestions immediately after dictionary changed.
    _suggestions.currently_typed_word(_typedword.get());
  }

  /** Update [_mods] to be consistent with the [mods], sending key events if
      needed. */
  void update_meta_state(Pointers.Modifiers mods)
  {
    // Released modifiers
    Iterator<KeyValue> it = _mods.diff(mods);
    while (it.hasNext())
      sendMetaKeyForModifier(it.next(), false);
    // Activated modifiers
    it = mods.diff(_mods);
    while (it.hasNext())
      sendMetaKeyForModifier(it.next(), true);
    _mods = mods;
  }

  // private void handleDelKey(int before, int after)
  // {
  //  CharSequence selection = getCurrentInputConnection().getSelectedText(0);

  //  if (selection != null && selection.length() > 0)
  //  getCurrentInputConnection().commitText("", 1);
  //  else
  //  getCurrentInputConnection().deleteSurroundingText(before, after);
  // }

  void sendMetaKey(int eventCode, int meta_flags, boolean down)
  {
    if (down)
    {
      _meta_state = _meta_state | meta_flags;
      send_keyevent(KeyEvent.ACTION_DOWN, eventCode, _meta_state);
    }
    else
    {
      send_keyevent(KeyEvent.ACTION_UP, eventCode, _meta_state);
      _meta_state = _meta_state & ~meta_flags;
    }
  }

  void sendMetaKeyForModifier(KeyValue kv, boolean down)
  {
    switch (kv.getKind())
    {
      case Modifier:
        switch (kv.getModifier())
        {
          case CTRL:
            sendMetaKey(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON, down);
            break;
          case ALT:
            sendMetaKey(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_ON, down);
            break;
          case SHIFT:
            sendMetaKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON, down);
            break;
          case META:
            sendMetaKey(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_ON, down);
            break;
          default:
            break;
        }
        break;
    }
  }

  void send_key_down_up(int keyCode)
  {
    send_key_down_up(keyCode, _meta_state);
  }

  /** Ignores currently pressed system modifiers. */
  void send_key_down_up(int keyCode, int metaState)
  {
    send_keyevent(KeyEvent.ACTION_DOWN, keyCode, metaState);
    send_keyevent(KeyEvent.ACTION_UP, keyCode, metaState);
  }

  void send_keyevent(int eventAction, int eventCode, int metaState)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    if (eventCode == KeyEvent.KEYCODE_ENTER && eventAction == KeyEvent.ACTION_DOWN)
    {
      clean_trailing_space_before_enter();
    }
    conn.sendKeyEvent(new KeyEvent(1, 1, eventAction, eventCode, 0,
          metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
          KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    if (eventAction == KeyEvent.ACTION_UP)
    {
      _autocap.event_sent(eventCode, metaState);
      _typedword.event_sent(eventCode, metaState);
    }
  }

  void send_text(String text)
  {
    if (text == null || text.length() == 0)
      return;

    if (text.equals("\n") || text.startsWith("\n"))
    {
      clean_trailing_space_before_enter();
    }

    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;

    long now = SystemClock.uptimeMillis();
    CharSequence before = conn.getTextBeforeCursor(60, 0);

    boolean rawMode = _mods != null && _mods.has(KeyValue.Modifier.AUTO_REPLACE_OFF);

    // Bypass all smart replacements, auto-spacing, and autocapitalisation
    if (rawMode)
    {
      _last_smart_action = SmartAction.NONE;
      _last_space_time = 0;
      conn.commitText(text, 1);
      _autocap.typed(text);
      _typedword.typed(text);
      _recv.set_shift_state(false, false);
      return;
    }

    // Smart Punctuation reverts (4th dot, 3rd hyphen)
    if (_config != null && _config.shouldApplySmartPunctuation())
    {
      if (text.equals(".") && _last_smart_action == SmartAction.ELLIPSIS
          && ends_with(before, "\u2026"))
      {
        replace_surrounding_text(1, 0, "....");
        _last_smart_action = SmartAction.NONE;
        _last_space_time = 0;
        _recv.set_shift_state(false, false);
        return;
      }
      if (text.equals("-") && _last_smart_action == SmartAction.EM_DASH
          && ends_with(before, "\u2014"))
      {
        replace_surrounding_text(1, 0, "---");
        _last_smart_action = SmartAction.NONE;
        _last_space_time = 0;
        _recv.set_shift_state(false, false);
        return;
      }
    }

    // Smart Punctuation replacements (3rd dot -> …, 2nd hyphen -> —)
    if (_config != null && _config.shouldApplySmartPunctuation())
    {
      if (text.equals(".") && ends_with(before, "..") && !ends_with(before, "..."))
      {
        replace_surrounding_text(2, 0, "\u2026");
        _last_smart_action = SmartAction.ELLIPSIS;
        _last_space_time = 0;
        _recv.set_shift_state(false, false);
        return;
      }
      if (text.equals("-") && ends_with(before, "-") && !ends_with(before, "--"))
      {
        replace_surrounding_text(1, 0, "\u2014");
        _last_smart_action = SmartAction.EM_DASH;
        _last_space_time = 0;
        _recv.set_shift_state(false, false);
        return;
      }
    }

    // Double-space to period (". ")
    if (_config != null && _config.shouldApplyDoubleSpacePeriod()
        && text.equals(" ")
        && _last_space_time > 0 && (now - _last_space_time) < 750
        && can_trigger_double_space_period(before))
    {
      replace_surrounding_text(1, 0, ". ");
      _last_smart_action = SmartAction.DOUBLE_SPACE_PERIOD;
      _last_space_time = 0;
      _recv.set_shift_state(true, false);
      return;
    }

    // Track last space time
    if (text.equals(" "))
      _last_space_time = now;
    else
      _last_space_time = 0;

    // 1. Delete space(s) before punctuation (e.g. "слово ," -> "слово,", "( ок )" -> "(ок)")
    int spacesBeforePunct = (_config != null && _config.shouldDeleteSpaceBeforePunctuation())
        ? get_spaces_before_punctuation(before, text) : 0;
    if (spacesBeforePunct > 0)
    {
      conn.beginBatchEdit();
      conn.deleteSurroundingText(spacesBeforePunct, 0);
      conn.commitText(text, 1);
      _typedword.remove_surrounding_text(spacesBeforePunct, 0);
      _typedword.typed(text);
      for (int i = 0; i < spacesBeforePunct; i++)
        _autocap.char_deleted();
      _autocap.typed(text);
      conn.endBatchEdit();
      update_shift_after_commit(before.subSequence(0, before.length() - spacesBeforePunct), text);
      _last_smart_action = SmartAction.NONE;
      return;
    }

    // 2. Delete space(s) after opening bracket (e.g. "(  ок" -> "(ок")
    int openBracketSpaces = (_config != null && _config.shouldDeleteSpaceBeforePunctuation())
        ? get_spaces_after_open_bracket(before, text) : 0;
    if (openBracketSpaces > 0)
    {
      CharSequence trimmedBefore = before.subSequence(0, before.length() - openBracketSpaces);
      if (should_autocapitalise()
          && Character.isLetter(text.charAt(0))
          && is_at_sentence_start(trimmedBefore))
      {
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
      }
      conn.beginBatchEdit();
      conn.deleteSurroundingText(openBracketSpaces, 0);
      conn.commitText(text, 1);
      _typedword.remove_surrounding_text(openBracketSpaces, 0);
      _typedword.typed(text);
      for (int i = 0; i < openBracketSpaces; i++)
        _autocap.char_deleted();
      _autocap.typed(text);
      conn.endBatchEdit();
      _recv.set_shift_state(false, false);
      _last_smart_action = SmartAction.NONE;
      return;
    }

    // 3. Auto-space after punctuation before a letter (e.g. "слово,нове" -> "слово, нове")
    if (_config != null && _config.shouldAutoSpaceAfterPunctuation()
        && should_insert_space_before(before, text))
    {
      if (should_autocapitalise()
          && is_at_sentence_start_after(before, " "))
      {
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
      }
      conn.beginBatchEdit();
      conn.commitText(" " + text, 1);
      _autocap.typed(" ");
      _typedword.typed(" ");
      _autocap.typed(text);
      _typedword.typed(text);
      conn.endBatchEdit();
      _recv.set_shift_state(false, false);
      _last_smart_action = SmartAction.AUTO_SPACE;
      _last_auto_space_text_len = text.length();
      return;
    }

    // 4. Normal text commit with autocapitalisation check at sentence start
    if (should_autocapitalise()
        && Character.isLetter(text.charAt(0))
        && is_at_sentence_start(before))
    {
      text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
    _autocap.typed(text);
    _typedword.typed(text);
    conn.commitText(text, 1);
    update_shift_after_commit(before, text);
    _last_smart_action = SmartAction.NONE;
  }

  void update_shift_after_commit(CharSequence before, String text)
  {
    if (!should_autocapitalise())
      return;

    if (text == null || text.length() == 0)
      return;

    char last = text.charAt(text.length() - 1);
    // If a letter was just typed, Shift should turn off
    if (Character.isLetter(last))
    {
      _recv.set_shift_state(false, false);
      return;
    }

    // If space was typed: check if preceding text was sentence start
    if (last == ' ' || last == '\u00A0')
    {
      if (is_at_sentence_start_after(before, text))
        _recv.set_shift_state(true, false);
      else
        _recv.set_shift_state(false, false);
      return;
    }

    // If newline was typed:
    if (last == '\n' || last == '\r')
    {
      _recv.set_shift_state(true, false);
      return;
    }

    // Any other character (punctuation, letters, symbols): Shift turns off
    _recv.set_shift_state(false, false);
  }

  int get_spaces_before_punctuation(CharSequence before, String text)
  {
    if (text == null || text.length() == 0)
      return 0;
    if (!is_space_deleting_punctuation(text.charAt(0)))
      return 0;
    if (_typedword.is_selection_not_empty())
      return 0;
    if (before == null || before.length() == 0)
      return 0;

    int len = before.length();
    int count = 0;
    while (count < len && (before.charAt(len - 1 - count) == ' ' || before.charAt(len - 1 - count) == '\u00A0'))
    {
      count++;
    }
    if (count > 0 && len > count && !is_whitespace(before.charAt(len - 1 - count)))
    {
      return count;
    }
    return 0;
  }

  int get_spaces_after_open_bracket(CharSequence before, String text)
  {
    if (text == null || text.length() == 0 || is_whitespace(text.charAt(0)))
      return 0;
    if (_typedword.is_selection_not_empty())
      return 0;
    if (before == null || before.length() < 2)
      return 0;

    int len = before.length();
    int count = 0;
    while (count < len && (before.charAt(len - 1 - count) == ' ' || before.charAt(len - 1 - count) == '\u00A0'))
    {
      count++;
    }
    if (count == 0 || count == len)
      return 0;

    char prev = before.charAt(len - 1 - count);
    if (is_opening_bracket(prev))
      return count;

    return 0;
  }

  boolean should_insert_space_before(CharSequence before, String text)
  {
    if (text == null || text.length() == 0)
      return false;
    if (!Character.isLetter(text.charAt(0)))
      return false;
    if (_typedword.is_selection_not_empty())
      return false;
    if (_config != null && _config.editor_config != null && !_config.editor_config.auto_space_allowed)
      return false;

    if (before == null || before.length() == 0)
      return false;

    int len = before.length();
    char last = before.charAt(len - 1);

    boolean isPunctuation = is_space_following_punctuation(last);
    if (!isPunctuation && last == '"' && len >= 2)
    {
      char prev = before.charAt(len - 2);
      isPunctuation = !is_whitespace(prev);
    }

    if (!isPunctuation)
      return false;

    // Avoid inserting space inside URLs, emails, file paths, code identifiers
    int start = len - 1;
    while (start > 0 && !is_whitespace(before.charAt(start - 1)))
    {
      start--;
    }
    String token = before.subSequence(start, len).toString();
    if (token.contains("@") || token.contains("/") || token.contains("\\")
        || token.startsWith("http:") || token.startsWith("https:")
        || token.startsWith("ftp:") || token.startsWith("www."))
    {
      return false;
    }

    return true;
  }

  static boolean is_space_deleting_punctuation(char c)
  {
    switch (c)
    {
      case '.':
      case ',':
      case '?':
      case '!':
      case ':':
      case ';':
      case '\u2026': // …
      case ')':
      case ']':
      case '}':
      case '»':
      case '\u201D': // ”
        return true;
      default:
        return false;
    }
  }

  static boolean is_space_following_punctuation(char c)
  {
    switch (c)
    {
      case '.':
      case ',':
      case '?':
      case '!':
      case ':':
      case ';':
      case '\u2026': // …
      case ')':
      case ']':
      case '}':
      case '»':
      case '\u201D': // ”
        return true;
      default:
        return false;
    }
  }

  static boolean is_opening_bracket(char c)
  {
    switch (c)
    {
      case '(':
      case '[':
      case '{':
      case '«':
      case '\u201C': // “
        return true;
      default:
        return false;
    }
  }

  static boolean is_closing_bracket(char c)
  {
    switch (c)
    {
      case ')':
      case ']':
      case '}':
      case '»':
      case '\u201D': // ”
        return true;
      default:
        return false;
    }
  }

  static boolean is_horizontal_whitespace(char c)
  {
    return c == ' ' || c == '\t' || c == '\u00A0' || Character.isSpaceChar(c);
  }

  static boolean is_whitespace(char c)
  {
    return Character.isWhitespace(c) || Character.isSpaceChar(c);
  }

  static boolean is_punctuation_or_symbol(char c)
  {
    return !Character.isLetterOrDigit(c) && !is_whitespace(c);
  }

  public void clean_trailing_space_before_enter()
  {
    _last_space_time = 0;
    _last_smart_action = SmartAction.NONE;
    if (_mods != null && _mods.has(KeyValue.Modifier.AUTO_REPLACE_OFF))
      return;
    if (_typedword.is_selection_not_empty())
      return;
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    CharSequence before = conn.getTextBeforeCursor(60, 0);
    if (before == null || before.length() == 0)
      return;
    int len = before.length();
    int spacesCount = 0;
    while (spacesCount < len && is_horizontal_whitespace(before.charAt(len - 1 - spacesCount)))
    {
      spacesCount++;
    }
    if (spacesCount > 0 && spacesCount < len)
    {
      char prev = before.charAt(len - 1 - spacesCount);
      if (is_punctuation_or_symbol(prev))
      {
        conn.deleteSurroundingText(spacesCount, 0);
        _typedword.remove_surrounding_text(spacesCount, 0);
        for (int i = 0; i < spacesCount; i++)
          _autocap.char_deleted();
      }
    }
  }

  static boolean is_at_sentence_start(CharSequence before)
  {
    if (before == null || before.length() == 0)
      return true;

    int i = before.length() - 1;
    int spacesSkipped = 0;

    // 1. Skip trailing horizontal whitespace (spaces, tabs, NBSP)
    while (i >= 0 && is_horizontal_whitespace(before.charAt(i)))
    {
      spacesSkipped++;
      i--;
    }

    if (i < 0)
      return true;

    char c = before.charAt(i);

    // 2. Newline is paragraph start -> sentence start
    if (c == '\n' || c == '\r')
      return true;

    // 3. Opening bracket / quote right before cursor (e.g. " ( " or "\n( " or "( ")
    if (is_opening_bracket(c) || c == '"' || c == '«' || c == '\u201C')
    {
      int j = i - 1;
      while (j >= 0 && is_horizontal_whitespace(before.charAt(j)))
      {
        j--;
      }
      if (j < 0)
        return true;
      char cPrev = before.charAt(j);
      if (cPrev == '\n' || cPrev == '\r')
        return true;
    }

    // 4. Closing bracket / quote (e.g. "word.) " or "word?» ")
    if (is_closing_bracket(c) || c == '"' || c == '»' || c == '\u201D')
    {
      i--;
      while (i >= 0 && is_horizontal_whitespace(before.charAt(i)))
      {
        i--;
      }
      if (i < 0)
        return false;
      c = before.charAt(i);
    }

    // 5. Check punctuation marks: MUST be followed by at least one space!
    // Ellipsis (... or …) does not start a new sentence.
    if (spacesSkipped > 0)
    {
      if (c == '?' || c == '!')
        return true;

      if (c == '.')
      {
        // Ellipsis: check if preceded by another dot (e.g. ".. " or "... ")
        if (i > 0 && before.charAt(i - 1) == '.')
          return false;
        return true;
      }
    }

    return false;
  }

  static boolean is_at_sentence_start_after(CharSequence before, String text)
  {
    if (text == null || text.length() == 0)
      return is_at_sentence_start(before);
    StringBuilder sb = new StringBuilder();
    if (before != null)
      sb.append(before);
    sb.append(text);
    return is_at_sentence_start(sb);
  }

  static boolean ends_with(CharSequence cs, String suffix)
  {
    if (cs == null || suffix == null)
      return false;
    int csLen = cs.length();
    int sLen = suffix.length();
    if (csLen < sLen)
      return false;
    for (int i = 0; i < sLen; i++)
    {
      if (cs.charAt(csLen - sLen + i) != suffix.charAt(i))
        return false;
    }
    return true;
  }

  static boolean can_trigger_double_space_period(CharSequence before)
  {
    if (before == null || before.length() < 2)
      return false;
    int len = before.length();
    char last = before.charAt(len - 1);
    if (last != ' ' && last != '\u00A0')
      return false;
    char prev = before.charAt(len - 2);
    if (Character.isLetterOrDigit(prev))
      return true;
    if (is_closing_bracket(prev) || prev == '"' || prev == '\'')
    {
      if (len >= 3)
      {
        char prev2 = before.charAt(len - 3);
        return Character.isLetterOrDigit(prev2);
      }
    }
    return false;
  }

  void replace_surrounding_text(int remove_before, int remove_after,
      String new_text)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.beginBatchEdit();
    conn.deleteSurroundingText(remove_before, remove_after);
    conn.commitText(new_text, 1);
    _typedword.remove_surrounding_text(remove_before, remove_after);
    _typedword.typed(new_text);
    for (int i = 0; i < remove_before; i++)
      _autocap.char_deleted();
    _autocap.typed(new_text);
    conn.endBatchEdit();
  }

  /** See {!InputConnection.performContextMenuAction}. */
  void send_context_menu_action(int id)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.performContextMenuAction(id);
  }

  @SuppressLint("InlinedApi")
  void handle_editing_key(KeyValue.Editing ev)
  {
    switch (ev)
    {
      case COPY: if(_typedword.is_selection_not_empty()) send_context_menu_action(android.R.id.copy); break;
      case PASTE: send_context_menu_action(android.R.id.paste); break;
      case CUT: if(_typedword.is_selection_not_empty()) send_context_menu_action(android.R.id.cut); break;
      case SELECT_ALL: send_context_menu_action(android.R.id.selectAll); break;
      case SHARE: send_context_menu_action(android.R.id.shareText); break;
      case PASTE_PLAIN: send_context_menu_action(android.R.id.pasteAsPlainText); break;
      case UNDO: send_context_menu_action(android.R.id.undo); break;
      case REDO: send_context_menu_action(android.R.id.redo); break;
      case REPLACE: send_context_menu_action(android.R.id.replaceText); break;
      case ASSIST: send_context_menu_action(android.R.id.textAssist); break;
      case AUTOFILL: send_context_menu_action(android.R.id.autofill); break;
      case DELETE_WORD: send_key_down_up(KeyEvent.KEYCODE_DEL, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
      case FORWARD_DELETE_WORD: send_key_down_up(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
      case SELECTION_CANCEL: cancel_selection(); break;
      case SPACE_BAR: handle_space_bar(); break;
      case BACKSPACE: handle_backspace(); break;
      default:
        _last_smart_action = SmartAction.NONE;
        _last_space_time = 0;
        break;
    }
  }

  static ExtractedTextRequest _move_cursor_req = null;

  /** Query the cursor position. The extracted text is empty. Returns [null] if
      the editor doesn't support this operation. */
  ExtractedText get_cursor_pos(InputConnection conn)
  {
    if (_move_cursor_req == null)
    {
      _move_cursor_req = new ExtractedTextRequest();
      _move_cursor_req.hintMaxChars = 0;
    }
    return conn.getExtractedText(_move_cursor_req, 0);
  }

  /** [r] might be negative, in which case the direction is reversed. */
  void handle_slider(KeyValue.Slider s, int r, boolean key_down)
  {
    _last_smart_action = SmartAction.NONE;
    _last_space_time = 0;
    switch (s)
    {
      case Cursor_left: move_cursor(-r); break;
      case Cursor_right: move_cursor(r); break;
      case Cursor_up: move_cursor_vertical(-r); break;
      case Cursor_down: move_cursor_vertical(r); break;
      case Selection_cursor_left: move_cursor_sel(r, true, key_down); break;
      case Selection_cursor_right: move_cursor_sel(r, false, key_down); break;
    }
  }

  void handle_stateful(KeyValue.Stateful st)
  {
    switch (st)
    {
      case Complete_first:
      case Complete_second:
      case Complete_third:
      case Complete_emoji:
        suggestion_entered(st.toString());
        break;
    }
  }

  /** Move the cursor right or left, if possible without sending key events.
      Unlike arrow keys, the selection is not removed even if shift is not on.
      Falls back to sending arrow keys events if the editor do not support
      moving the cursor or a modifier other than shift is pressed. */
  void move_cursor(int d)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null && can_set_selection(conn))
    {
      int sel_start = et.selectionStart;
      int sel_end = et.selectionEnd;
      // Continue expanding the selection even if shift is not pressed
      if (sel_end != sel_start)
      {
        sel_end += d;
        if (sel_end == sel_start) // Avoid making the selection empty
          sel_end += d;
      }
      else
      {
        sel_end += d;
        // Leave 'sel_start' where it is if shift is pressed
        if ((_meta_state & KeyEvent.META_SHIFT_ON) == 0)
          sel_start = sel_end;
      }
      if (conn.setSelection(sel_start, sel_end))
        return; // Fallback to sending key events if [setSelection] failed
    }
    move_cursor_fallback(d);
  }

  /** Move one of the two side of a selection. If [sel_left] is true, the left
      position is moved, otherwise the right position is moved. */
  void move_cursor_sel(int d, boolean sel_left, boolean key_down)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null && can_set_selection(conn))
    {
      int sel_start = et.selectionStart;
      int sel_end = et.selectionEnd;
      // Reorder the selection when the slider has just been pressed. The
      // selection might have been reversed if one end crossed the other end
      // with a previous slider.
      if (key_down && sel_start > sel_end)
      {
        sel_start = et.selectionEnd;
        sel_end = et.selectionStart;
      }
      do
      {
        if (sel_left)
          sel_start += d;
        else
          sel_end += d;
        // Move the cursor twice if moving it once would make the selection
        // empty and stop selection mode.
      } while (sel_start == sel_end);
      if (conn.setSelection(sel_start, sel_end))
        return; // Fallback to sending key events if [setSelection] failed
    }
    move_cursor_fallback(d);
  }

  /** Returns whether the selection can be set using [conn.setSelection()].
      This can happen on Termux or when system modifiers are activated for
      example. */
  boolean can_set_selection(InputConnection conn)
  {
    final int system_mods =
      KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON;
    return !_move_cursor_force_fallback && (_meta_state & system_mods) == 0;
  }

  void move_cursor_fallback(int d)
  {
    if (d < 0)
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_LEFT, -d);
    else
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_RIGHT, d);
  }

  /** Move the cursor up and down. This sends UP and DOWN key events that might
      make the focus exit the text box. */
  void move_cursor_vertical(int d)
  {
    if (d < 0)
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_UP, -d);
    else
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_DOWN, d);
  }

  @Override
  public void move_trackpad(int dx, int dy, boolean select)
  {
    _last_smart_action = SmartAction.NONE;
    _last_space_time = 0;
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;

    if (!select)
    {
      _meta_state &= ~(KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
    }

    if (dx != 0)
    {
      if (select)
      {
        int old_meta = _meta_state;
        _meta_state |= (KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
        ExtractedText et = get_cursor_pos(conn);
        if (et != null && can_set_selection(conn))
        {
          int start = et.selectionStart;
          int end = et.selectionEnd + dx;
          if (end < 0) end = 0;
          if (!conn.setSelection(start, end))
            move_cursor_fallback(dx);
        }
        else
        {
          move_cursor_fallback(dx);
        }
        _meta_state = old_meta;
      }
      else
      {
        ExtractedText et = get_cursor_pos(conn);
        if (et != null && can_set_selection(conn))
        {
          int pos = et.selectionEnd + dx;
          if (pos < 0) pos = 0;
          if (!conn.setSelection(pos, pos))
            move_cursor_fallback(dx);
        }
        else
        {
          move_cursor_fallback(dx);
        }
      }
    }

    if (dy != 0)
    {
      int meta = select ? (_meta_state | KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON)
                        : (_meta_state & ~(KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON));
      int keyCode = (dy < 0) ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
      int repeat = Math.abs(dy);
      while (repeat-- > 0)
        send_key_down_up(keyCode, meta);
    }
  }

  @Override
  public void sync_selection()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null)
    {
      selection_updated(et.selectionStart, et.selectionStart, et.selectionEnd);
      _recv.selection_state_changed(et.selectionStart != et.selectionEnd);
    }
  }

  void evaluate_macro(KeyValue[] keys)
  {
    if (keys.length == 0)
      return;
    // Ignore modifiers that are activated at the time the macro is evaluated
    mods_changed(Pointers.Modifiers.EMPTY);
    evaluate_macro_loop(keys, 0, Pointers.Modifiers.EMPTY, _autocap.pause());
  }

  /** Evaluate the macro asynchronously to make sure event are processed in the
      right order. */
  void evaluate_macro_loop(final KeyValue[] keys, int i, Pointers.Modifiers mods, final boolean autocap_paused)
  {
    boolean should_delay = false;
    KeyValue kv = KeyModifier.modify_no_modmap(keys[i], mods);
    if (kv != null)
    {
      if (kv.hasFlagsAny(KeyValue.FLAG_LATCH))
      {
        // Non-special latchable keys clear latched modifiers
        if (!kv.hasFlagsAny(KeyValue.FLAG_SPECIAL))
          mods = Pointers.Modifiers.EMPTY;
        mods = mods.with_extra_mod(kv);
      }
      else
      {
        key_down(kv, false);
        key_up(kv, mods);
        mods = Pointers.Modifiers.EMPTY;
      }
      should_delay = wait_after_macro_key(kv);
    }
    i++;
    if (i >= keys.length) // Stop looping
    {
      _autocap.unpause(autocap_paused);
    }
    else if (should_delay)
    {
      // Add a delay before sending the next key to avoid race conditions
      // causing keys to be handled in the wrong order. Notably, KeyEvent keys
      // handling is scheduled differently than the other edit functions.
      final int i_ = i;
      final Pointers.Modifiers mods_ = mods;
      _recv.getHandler().postDelayed(new Runnable() {
        public void run()
        {
          evaluate_macro_loop(keys, i_, mods_, autocap_paused);
        }
      }, 1000/30);
    }
    else
      evaluate_macro_loop(keys, i, mods, autocap_paused);
  }

  boolean wait_after_macro_key(KeyValue kv)
  {
    switch (kv.getKind())
    {
      case Keyevent:
      case Editing:
      case Event:
        return true;
      case Slider:
        return _move_cursor_force_fallback;
      default:
        return false;
    }
  }

  /** Repeat calls to [send_key_down_up]. */
  void send_key_down_up_repeat(int event_code, int repeat)
  {
    while (repeat-- > 0)
      send_key_down_up(event_code);
  }

  void cancel_selection()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et == null) return;
    final int curs = et.selectionStart;
    // Notify the receiver as Android's [onUpdateSelection] is not triggered.
    if (conn.setSelection(curs, curs))
      _recv.selection_state_changed(false);
  }

  /** The word that was replaced by a suggestion when the last action was to
      enter a suggestion (with the space bar or the candidates view) or [null]
      otherwise. */
  String last_replaced_word = null;
  /** Length of the text before the cursor that should be replaced by
      backspace. */
  int last_replacement_word_len = 0;

  /** Implement autocorrect when enabled in the settings. */
  void handle_space_bar()
  {
    if (_space_bar_auto_complete && _suggestions.count > 0
        && !_typedword.is_selection_not_empty()
        && _typedword.cursor_relative() == 0)
      suggestion_entered(_suggestions.suggestions[0] + " ");
    else
      send_text(" ");
  }

  /** Undo the last autocorrect or smart action. */
  void handle_backspace()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    _last_space_time = 0;

    if (_last_smart_action != SmartAction.NONE && conn != null)
    {
      SmartAction action = _last_smart_action;
      _last_smart_action = SmartAction.NONE;

      CharSequence before = conn.getTextBeforeCursor(4, 0);
      switch (action)
      {
        case ELLIPSIS:
          if (ends_with(before, "\u2026"))
          {
            replace_surrounding_text(1, 0, "...");
            return;
          }
          break;

        case EM_DASH:
          if (ends_with(before, "\u2014"))
          {
            replace_surrounding_text(1, 0, "--");
            return;
          }
          break;

        case DOUBLE_SPACE_PERIOD:
          if (ends_with(before, ". "))
          {
            replace_surrounding_text(2, 0, "  ");
            _recv.set_shift_state(false, false);
            return;
          }
          break;

        case AUTO_SPACE:
          if (before != null && before.length() >= 1 + _last_auto_space_text_len)
          {
            replace_surrounding_text(1 + _last_auto_space_text_len, 0, "");
            return;
          }
          break;

        default:
          break;
      }
    }

    if (_last_action == LastAction.SUGGESTION_ENTERED
        && last_replaced_word != null)
    {
      replace_surrounding_text(last_replacement_word_len, 0, last_replaced_word);
      last_replaced_word = null;
    }
    else
    {
      send_key_down_up(KeyEvent.KEYCODE_DEL);
    }
  }

  public static interface IReceiver extends Suggestions.Callback
  {
    public void handle_event_key(KeyValue.Event ev);
    public void set_shift_state(boolean state, boolean lock);
    public void set_compose_pending(boolean pending);
    public void selection_state_changed(boolean selection_is_ongoing);
    public InputConnection getCurrentInputConnection();
    public Handler getHandler();
  }

  class Autocapitalisation_callback implements Autocapitalisation.Callback
  {
    @Override
    public void update_shift_state(boolean should_enable, boolean should_disable)
    {
      if (_mods != null && _mods.has(KeyValue.Modifier.AUTO_REPLACE_OFF))
      {
        _recv.set_shift_state(false, false);
        return;
      }
      if (should_enable)
        _recv.set_shift_state(true, false);
      else if (should_disable)
      {
        InputConnection ic = _recv.getCurrentInputConnection();
        if (ic != null && is_at_sentence_start(ic.getTextBeforeCursor(60, 0)))
          _recv.set_shift_state(true, false);
        else
          _recv.set_shift_state(false, false);
      }
    }
  }

  public static enum LastAction
  {
    SUGGESTION_ENTERED,
    OTHER
  }
}
