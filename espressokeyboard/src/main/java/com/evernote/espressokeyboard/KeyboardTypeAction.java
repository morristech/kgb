/**
 * Copyright (c) 2015 Evernote Corporation. All rights reserved.
 */
package com.evernote.espressokeyboard;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Build;
import android.support.test.espresso.IdlingResource;
import android.support.test.espresso.InjectEventSecurityException;
import android.support.test.espresso.PerformException;
import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.action.GeneralClickAction;
import android.support.test.espresso.action.GeneralLocation;
import android.support.test.espresso.action.Press;
import android.support.test.espresso.action.Tap;
import android.support.test.espresso.core.deps.guava.base.Preconditions;
import android.support.test.espresso.matcher.ViewMatchers;
import android.support.test.espresso.util.HumanReadables;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SearchView;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eu.chainfire.libsuperuser.Shell;

/**
 * Created by paour on 26/08/15.
 */
public class KeyboardTypeAction implements ViewAction, IdlingResource {
  private static final String TAG = KeyboardTypeAction.class.getSimpleName();
  private boolean tapToFocus = true;
  private UiAutomation uiAutomation = null;
  private Shell.Interactive interactive;
  List<KeyInfo> keysToBeHit = new ArrayList<>();
  StringBuilder description = new StringBuilder();
  boolean fallbackToInjection = false;

  public KeyboardTypeAction(UiAutomation uiAutomation) {
    this.uiAutomation = uiAutomation;
  }

  public KeyboardTypeAction(Instrumentation instrumentation) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      this.uiAutomation = instrumentation.getUiAutomation();
    }
  }

  public KeyboardTypeAction(String stringToBeTyped) {
    this(true, stringToBeTyped);
  }

  public KeyboardTypeAction(KeyInfo... keysToBeHit) {
    this(true, keysToBeHit);
  }

  public KeyboardTypeAction(boolean tapToFocus, String stringToBeTyped) {
    this.tapToFocus = tapToFocus;

    add(stringToBeTyped);
  }

  public KeyboardTypeAction(boolean tapToFocus, KeyInfo... keysToBeHit) {
    this.tapToFocus = tapToFocus;

    add(keysToBeHit);
  }

  public KeyboardTypeAction add(String stringToBeTyped) {
    Preconditions.checkNotNull(stringToBeTyped);
    appendDescription(stringToBeTyped);

    for (char c : stringToBeTyped.toCharArray()) {
      if (c == '\n') {
        keysToBeHit.add(KeyLocations.instance().findSpecial(KeyEvent.KEYCODE_ENTER));
      } else {
        if (Character.isUpperCase(c)) {
          keysToBeHit.add(KeyLocations.instance().findSpecial(KeyEvent.KEYCODE_SHIFT_LEFT));
          c = Character.toLowerCase(c);
        }

        try {
          keysToBeHit.add(KeyLocations.instance().findStandard(c));
        } catch (IllegalStateException e) {
          if (fallbackToInjection) {
            Log.w(TAG, "Using fallback for " + c);

            keysToBeHit.add(KeyInfo.getFallbackKey("" + c));
          } else {
            throw e;
          }
        }
      }
    }

    return this;
  }

  public KeyboardTypeAction add(KeyInfo... keysToBeHit) {
    appendDescription(String.format("%d keys", keysToBeHit.length));
    this.keysToBeHit.addAll(Arrays.asList(keysToBeHit));

    return this;
  }

  public KeyboardTypeAction addReturn() {
    return add(KeyLocations.instance().findSpecial(KeyEvent.KEYCODE_ENTER));
  }

  public KeyboardTypeAction addBackspace() {
    return add(KeyLocations.instance().findSpecial(KeyEvent.KEYCODE_BACK));
  }

  public KeyboardTypeAction addCompletion() {
    return add(KeyLocations.instance().findCompletion());
  }

  public KeyboardTypeAction setFallbackToInjection(boolean fallbackToInjection) {
    this.fallbackToInjection = fallbackToInjection;

    return this;
  }

  public KeyboardTypeAction setTapToFocus(boolean tapToFocus) {
    this.tapToFocus = tapToFocus;

    return this;
  }

  private void appendDescription(String s) {
    if (description.length() != 0) {
      description.append(", ");
    }

    description.append(s);
  }

  public Matcher<View> getConstraints() {
    Matcher matchers = Matchers.allOf(ViewMatchers.isDisplayed());
    if (!this.tapToFocus) {
      matchers = Matchers.allOf(matchers, ViewMatchers.hasFocus());
    }

    //noinspection unchecked
    return Build.VERSION.SDK_INT < 11 ?
        Matchers.allOf(matchers, ViewMatchers.supportsInputMethods()) :
        Matchers.allOf(matchers, Matchers.anyOf(ViewMatchers.supportsInputMethods(), ViewMatchers.isAssignableFrom(SearchView.class)));
  }

  public void perform(UiController uiController, View view) {
    if (keysToBeHit.size() == 0) {
      Log.w(TAG, "Supplied string is empty resulting in no-op (nothing is typed).");
    } else {
      if (this.tapToFocus) {
        (new GeneralClickAction(Tap.SINGLE, GeneralLocation.CENTER, Press.FINGER)).perform(uiController, view);
        uiController.loopMainThreadUntilIdle();
      }

      if (uiAutomation == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
        Shell.Builder builder = new Shell.Builder().useSU();

        for (KeyInfo keyInfo : keysToBeHit) {
          KeyLocation location = keyInfo.getLocation();

          if (location != KeyLocation.NONE) {
            builder.addCommand("input tap " + location.getAbsoluteX() + " " + location.getAbsoluteY());
          } else {
            builder.addCommand("input text \"" + keyInfo.getKey().getCharacter() + "\"");
          }
        }

        interactive = builder.open(null);

        // todo: use IdlingResource
        uiController.loopMainThreadForAtLeast(Math.max(keysToBeHit.size() * 500, 1000));
      } else {
        for (KeyInfo keyInfo : keysToBeHit) {
          KeyLocation location = keyInfo.getLocation();

          if (location != KeyLocation.NONE) {
            KeyboardSwitcher.injectTap(location.getAbsoluteX(),
                location.getAbsoluteY(), uiAutomation, false);
          } else {
            try {
              uiController.injectString(keyInfo.getKey().getCharacter());
            } catch (InjectEventSecurityException e) {
              Log.e(TAG, "Failed to type character: " + keyInfo.getKey().getCharacter());
              throw new PerformException.Builder()
                  .withActionDescription(this.getDescription())
                  .withViewDescription(HumanReadables.describe(view))
                  .withCause(e)
                  .build();
            }
          }
          uiController.loopMainThreadUntilIdle();
        }

        uiController.loopMainThreadForAtLeast(Math.max(keysToBeHit.size() * 100, 1000));
      }
    }
  }

  public String getDescription() {
    return String.format("really type text(%s)", this.description.toString());
  }

  @Override
  public String getName() {
    return "Root command idle";
  }

  @Override
  public boolean isIdleNow() {
    return interactive == null || interactive.isIdle();
  }

  @Override
  public void registerIdleTransitionCallback(ResourceCallback resourceCallback) {
    //this.resourceCallback = resourceCallback;
  }
}
