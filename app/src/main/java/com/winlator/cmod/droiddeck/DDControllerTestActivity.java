package com.winlator.cmod.droiddeck;

import android.app.Activity;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.winlator.cmod.inputcontrols.GamepadState;

/**
 * Host mandiri layar Controller Test: kontrol layar DroidDeck + trackpad/grip Deck + pad fisik
 * menulis ke state pad Deck, dan DDControllerTestView menampilkannya.
 */
public class DDControllerTestActivity extends Activity {
    private DDOnScreenControls osc;
    private DDDeckControlsView deck;
    private DDControllerTestView test;
    private boolean controlsOn = true;
    private final GamepadState phys = new GamepadState();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DDDeck.prepare(this);

        FrameLayout root = new FrameLayout(this);
        test = new DDControllerTestView(this, new DDControllerTestView.Host() {
            @Override public void close() { finish(); }
            @Override public void toggleControls() { setControls(!controlsOn); }
            @Override public boolean controlsVisible() { return controlsOn; }
            @Override public boolean standalone() { return true; }
        });
        root.addView(test, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        osc = new DDOnScreenControls(this, new DDOnScreenControls.Sink() {
            @Override public void onPad(GamepadState s) { DDDeck.updatePad(s); }
            @Override public void onGuide(boolean down) { DDDeck.setGuide(down); }
        }, false);
        root.addView(osc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        deck = new DDDeckControlsView(this, DDPrefs.read(this).tint);
        root.addView(deck, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setControls(boolean on) {
        controlsOn = on;
        int v = on ? View.VISIBLE : View.GONE;
        if (!on) { osc.releaseAll(); deck.releaseAll(); }
        osc.setVisibility(v);
        deck.setVisibility(v);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        DDDeck.startMotion(this, () -> getWindowManager().getDefaultDisplay().getRotation());
    }

    @Override
    protected void onPause() {
        DDDeck.stopMotion();
        if (osc != null) osc.releaseAll();
        if (deck != null) deck.releaseAll();
        DDDeck.releaseAll();
        super.onPause();
    }

    // ------------------------------------------------------------ pad fisik

    private static boolean isPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean pad = isPad(e.getSource());
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (pad && e.getAction() != KeyEvent.ACTION_MULTIPLE) {
            if (code == KeyEvent.KEYCODE_BACK) code = KeyEvent.KEYCODE_BUTTON_SELECT;
            if (padKey(code, down, e.getRepeatCount())) return true;
        }
        if (!pad && code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean padKey(int code, boolean down, int repeat) {
        if (repeat > 0) return true;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: phys.setPressed(0, down); break;
            case KeyEvent.KEYCODE_BUTTON_B: phys.setPressed(1, down); break;
            case KeyEvent.KEYCODE_BUTTON_X: phys.setPressed(2, down); break;
            case KeyEvent.KEYCODE_BUTTON_Y: phys.setPressed(3, down); break;
            case KeyEvent.KEYCODE_BUTTON_L1: phys.setPressed(4, down); break;
            case KeyEvent.KEYCODE_BUTTON_R1: phys.setPressed(5, down); break;
            case KeyEvent.KEYCODE_BUTTON_SELECT: phys.setPressed(6, down); break;
            case KeyEvent.KEYCODE_BUTTON_START: phys.setPressed(7, down); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: phys.setPressed(8, down); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: phys.setPressed(9, down); break;
            case KeyEvent.KEYCODE_BUTTON_L2: phys.triggerL = down ? 1f : 0f; break;
            case KeyEvent.KEYCODE_BUTTON_R2: phys.triggerR = down ? 1f : 0f; break;
            case KeyEvent.KEYCODE_DPAD_UP: phys.dpad[0] = down; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: phys.dpad[1] = down; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: phys.dpad[2] = down; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: phys.dpad[3] = down; break;
            case KeyEvent.KEYCODE_BUTTON_MODE: DDDeck.setGuide(down); return true;
            default: return false;
        }
        DDDeck.updatePad(phys);
        return true;
    }

    private static float dead(float v) { return Math.abs(v) < 0.08f ? 0f : v; }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (isPad(e.getSource()) && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            phys.thumbLX = dead(e.getAxisValue(MotionEvent.AXIS_X));
            phys.thumbLY = dead(e.getAxisValue(MotionEvent.AXIS_Y));
            phys.thumbRX = dead(e.getAxisValue(MotionEvent.AXIS_Z));
            phys.thumbRY = dead(e.getAxisValue(MotionEvent.AXIS_RZ));
            phys.triggerL = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            phys.triggerR = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            phys.dpad[0] = hy < -0.5f;
            phys.dpad[1] = hx > 0.5f;
            phys.dpad[2] = hy > 0.5f;
            phys.dpad[3] = hx < -0.5f;
            DDDeck.updatePad(phys);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }
}
