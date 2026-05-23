package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.os.Handler;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;

public class TouchpadView extends View {
    private static final byte MAX_FINGERS = 4;
    private static final short MAX_TWO_FINGERS_SCROLL_DISTANCE = 350;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.25f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private final Finger[] fingers = new Finger[MAX_FINGERS];
    private byte numFingers = 0;
    private float sensitivity = 1.0f;
    private boolean pointerButtonLeftEnabled = true;
    private boolean pointerButtonRightEnabled = true;
    private Finger fingerPointerButtonLeft;
    private Finger fingerPointerButtonRight;
    private float scrollAccumY = 0;
    private boolean scrolling = false;
    private final XServer xServer;
    private Runnable fourFingersTapCallback;
    private final float[] xform = XForm.getInstance();
    private boolean simTouchScreen = false;
    private boolean continueClick = true;
    private int lastTouchedPosX;
    private int lastTouchedPosY;
    private static final Byte CLICK_DELAYED_TIME = 50;
    private static final Byte EFFECTIVE_TOUCH_DISTANCE = 20;
    private float resolutionScale;
    private static final int UPDATE_FORM_DELAYED_TIME = 50;

    private Handler timeoutHandler; // Reference to the activity's timeout handler
    private Runnable hideControlsRunnable; // Runnable to hide the controls

    private SharedPreferences preferences;

    private boolean twoFingerGestureActive = false;
    private float gesturePrevDistance = 0f;
    private float gesturePrevAngle = 0f;
    private float gesturePrevCentroidX = 0f;
    private float gesturePrevCentroidY = 0f;
    private TwoFingerGestureListener twoFingerGestureListener;
    private TwoFingerGestureType activeTwoFingerGesture = null;
    private boolean twoFingerTapCandidate = false;
    private long twoFingerTapStartTime = 0;
    private int twoFingerGestureLastPointerId = -1;
    private boolean twoFingerGestureLeftHeld = false;

    private static final long TWO_FINGER_TAP_MAX_DURATION_MS = 250;
    private static final float TWO_FINGER_TAP_MAX_MOVE = 20f;

    public enum TwoFingerGestureType {
        ZOOM_IN,
        ZOOM_OUT,
        ROTATE,
        PAN,
        TWO_FINGER_TAP
    }

    // Flag to control touchpad vs touchscreen mode

    @SuppressLint("ResourceType")
    public TouchpadView(Context context, XServer xServer, Handler timeoutHandler, Runnable hideControlsRunnable) {
        super(context);
        this.xServer = xServer;

        this.timeoutHandler = timeoutHandler; // Store the reference to timeout handler
        this.hideControlsRunnable = hideControlsRunnable; // Store the reference to the hide controls runnable

        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackground(createTransparentBg());
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo.width, xServer.screenInfo.height);
        // Initialize SharedPreferences here
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);

        this.timeoutHandler = timeoutHandler; // Store the reference to timeout handler
        this.hideControlsRunnable = hideControlsRunnable; // Store the reference to the hide controls runnable

        // Set up the generic motion listener for hover events
        setOnGenericMotionListener(new OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    return handleStylusHoverEvent(event);
                }
                return false;
            }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateXform(w, h, xServer.screenInfo.width, xServer.screenInfo.height);
        resolutionScale = 1000.0f / Math.min(xServer.screenInfo.width, xServer.screenInfo.height);
    }

    private void updateXform(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        ViewTransformation viewTransformation = new ViewTransformation();
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight);

        float invAspect = 1.0f / viewTransformation.aspect;
        if (!xServer.getRenderer().isFullscreen()) {
            XForm.makeTranslation(xform, -viewTransformation.viewOffsetX, -viewTransformation.viewOffsetY);
            XForm.scale(xform, invAspect, invAspect);
        } else
            XForm.makeScale(xform, (float) innerWidth / outerWidth, (float) innerHeight / outerHeight);
    }

    private class Finger {
        private int x;
        private int y;
        private final int startX;
        private final int startY;
        private int lastX;
        private int lastY;
        private final long touchTime;

        public Finger(float x, float y) {
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.x = this.startX = this.lastX = (int)transformedPoint[0];
            this.y = this.startY = this.lastY = (int)transformedPoint[1];
            touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            lastX = this.x;
            lastY = this.y;
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.x = (int)transformedPoint[0];
            this.y = (int)transformedPoint[1];
        }

        private int deltaX() {
            float dx = (x - lastX) * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dx);
        }

        private int deltaY() {
            float dy = (y - lastY) * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dy);
        }

        private boolean isTap() {
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE;
        }

        private float travelDistance() {
            return (float)Math.hypot(x - startX, y - startY);
        }
    }

//    public void setTouchscreenMode(boolean isTouchscreenMode) {
//        this.isTouchscreenMode = isTouchscreenMode;
//    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean isTouchscreenMode = isSimTouchScreen() || preferences.getBoolean("touchscreen_toggle", false);

        // Reset the timeout timer to keep controls visible
        resetTouchscreenTimeout();  // <-- Ensure the controls stay visible

        // Continue handling touch events as usual
        int toolType = event.getToolType(0);

        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            return handleStylusEvent(event);
        } else if (isTouchscreenMode) {
            return handleTouchscreenEvent(event);
        } else {
            return handleTouchpadEvent(event);
        }
    }

    private void resetTouchscreenTimeout() {
        //Log.d("TouchpadView", "Touch detected, resetting timeout.");
        if (timeoutHandler != null && hideControlsRunnable != null) {
            // Cancel any pending hide requests
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            // Post a new request to hide the controls after 5 seconds
            timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Adjust timeout as necessary
        }
    }
    private boolean handleStylusHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                Log.d("StylusEvent", "Hover Enter");
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                Log.d("StylusEvent", "Hover Move: (" + event.getX() + ", " + event.getY() + ")");
                float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                Log.d("StylusEvent", "Hover Exit");
                break;
            default:
                return false;
        }
        return true;
    }

    private boolean handleStylusEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int buttonState = event.getButtonState();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
                    handleStylusRightClick(event);
                } else {
                    handleStylusLeftClick(event);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                handleStylusMove(event);
                break;
            case MotionEvent.ACTION_UP:
                handleStylusUp(event);
                break;
        }

        return true;
    }

    private void handleStylusLeftClick(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
    }

    private void handleStylusRightClick(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
    }

    private void handleStylusMove(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
    }

    private void handleStylusUp(MotionEvent event) {
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
    }



    private boolean handleTouchpadEvent(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();
        if (pointerId >= MAX_FINGERS) return true;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true;
                scrollAccumY = 0;
                scrolling = false;
                fingers[pointerId] = new Finger(event.getX(actionIndex), event.getY(actionIndex));
                numFingers++;
                if (simTouchScreen) {
                    final Runnable clickDelay = () -> {
                        if (continueClick) {
                            xServer.injectPointerMove(lastTouchedPosX, lastTouchedPosY);
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                        }
                    };
                    if (pointerId == 0) {
                        continueClick = true;
                        if (Math.hypot(fingers[0].x - lastTouchedPosX, fingers[0].y - lastTouchedPosY) * resolutionScale > EFFECTIVE_TOUCH_DISTANCE) {
                            lastTouchedPosX = fingers[0].x;
                            lastTouchedPosY = fingers[0].y;
                        }
                        postDelayed(clickDelay, CLICK_DELAYED_TIME);
                    } else if (pointerId == 1) {
                        // When put a finger on InputControl, such as a button.
                        // The pointerId that TouchPadView got won't increase from 1, so map 1 as 0 here.
                        if (numFingers < 2) {
                            continueClick = true;
                            if (Math.hypot(fingers[1].x - lastTouchedPosX, fingers[1].y - lastTouchedPosY) * resolutionScale > EFFECTIVE_TOUCH_DISTANCE) {
                                lastTouchedPosX = fingers[1].x;
                                lastTouchedPosY = fingers[1].y;
                            }
                            postDelayed(clickDelay, CLICK_DELAYED_TIME);
                        } else
                            continueClick = System.currentTimeMillis() - fingers[0].touchTime > CLICK_DELAYED_TIME;
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
                    else
                        xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                } else {
                    for (byte i = 0; i < MAX_FINGERS; i++) {
                        if (fingers[i] != null) {
                            int pointerIndex = event.findPointerIndex(i);
                            if (pointerIndex >= 0) {
                                fingers[i].update(event.getX(pointerIndex), event.getY(pointerIndex));
                                handleFingerMove(fingers[i]);
                            } else {
                                handleFingerUp(fingers[i]);
                                fingers[i] = null;
                                numFingers--;
                            }
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (fingers[pointerId] != null) {
                    fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    handleFingerUp(fingers[pointerId]);
                    fingers[pointerId] = null;
                    numFingers--;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (byte i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
                numFingers = 0;
                break;
        }

        return true;
    }

    private boolean handleTouchscreenEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) {
                    resetTwoFingerGestureState(event);
                    twoFingerTapCandidate = true;
                    twoFingerTapStartTime = System.currentTimeMillis();
                    activeTwoFingerGesture = null;
                    twoFingerGestureActive = false;
                    twoFingerGestureLastPointerId = event.getPointerId(event.getActionIndex());
                } else {
                    handleTouchDown(event);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 2) {
                    if (twoFingerTapCandidate) {
                        float x0 = event.getX(0);
                        float y0 = event.getY(0);
                        float x1 = event.getX(1);
                        float y1 = event.getY(1);
                        float centroidX = (x0 + x1) * 0.5f;
                        float centroidY = (y0 + y1) * 0.5f;
                        if (Math.hypot(centroidX - gesturePrevCentroidX, centroidY - gesturePrevCentroidY) > TWO_FINGER_TAP_MAX_MOVE) {
                            twoFingerTapCandidate = false;
                        }
                    }
                    handleTwoFingerGesture(event);
                } else {
                    stopTwoFingerGesture();
                    handleTouchMove(event);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (twoFingerGestureActive) {
                    stopTwoFingerGesture();
                }
                if (event.getPointerCount() == 2 && twoFingerTapCandidate) {
                    long duration = System.currentTimeMillis() - twoFingerTapStartTime;
                    if (duration <= TWO_FINGER_TAP_MAX_DURATION_MS) {
                        if (twoFingerGestureListener != null) {
                            twoFingerGestureListener.onTwoFingerGesture(TwoFingerGestureType.TWO_FINGER_TAP, true);
                            twoFingerGestureListener.onTwoFingerGesture(TwoFingerGestureType.TWO_FINGER_TAP, false);
                        }
                        twoFingerTapCandidate = false;
                        break;
                    }
                }
                twoFingerTapCandidate = false;
                if (event.getPointerCount() == 2) {
                    handleTwoFingerTap(event);
                } else {
                    handleTouchUp(event);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (twoFingerGestureActive) {
                    stopTwoFingerGesture();
                }
                if (twoFingerGestureLeftHeld) {
                    releaseLeftButtonForTwoFingerGesture();
                }
                if (xServer.isRelativeMouseMovement()) {
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                }
                else {
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                }
                break;
        }
        return true;
    }

    private void handleTouchDown(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        if (xServer.isRelativeMouseMovement())
            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
        else
            xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);

        // Handle long press for right click (or use a dedicated method to detect long press)
        if (event.getPointerCount() == 1) {
            if (xServer.isRelativeMouseMovement())
                xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
            else
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        }
    }

    private void handleTouchMove(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        if (xServer.isRelativeMouseMovement())
            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
        else
            xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
    }

    private void handleTouchUp(MotionEvent event) {
        if (xServer.isRelativeMouseMovement())
            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
        else
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
    }

    private void handleTwoFingerScroll(MotionEvent event) {
        float scrollDistance = event.getY(0) - event.getY(1);
        if (Math.abs(scrollDistance) > 10) {
            if (scrollDistance > 0) {
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
            } else {
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
            }
        }
    }

    private void handleTwoFingerTap(MotionEvent event) {
        if (event.getPointerCount() == 2) {
            if (xServer.isRelativeMouseMovement()) {
                xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
            }
            else {
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
            }
        }
    }

    public interface TwoFingerGestureListener {
        void onTwoFingerGesture(TwoFingerGestureType gesture, boolean active);
    }

    public void setTwoFingerGestureListener(TwoFingerGestureListener listener) {
        this.twoFingerGestureListener = listener;
    }

    private void resetTwoFingerGestureState(MotionEvent event) {
        twoFingerGestureActive = false;
        gesturePrevDistance = 0f;
        gesturePrevAngle = 0f;
        gesturePrevCentroidX = 0f;
        gesturePrevCentroidY = 0f;
        twoFingerGestureLastPointerId = -1;
        if (event.getPointerCount() == 2) {
            float x0 = event.getX(0);
            float y0 = event.getY(0);
            float x1 = event.getX(1);
            float y1 = event.getY(1);
            gesturePrevDistance = (float) Math.hypot(x1 - x0, y1 - y0);
            gesturePrevAngle = (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
            gesturePrevCentroidX = (x0 + x1) * 0.5f;
            gesturePrevCentroidY = (y0 + y1) * 0.5f;
        }
    }

    private void handleTwoFingerGesture(MotionEvent event) {
        if (event.getPointerCount() != 2) return;

        float x0 = event.getX(0);
        float y0 = event.getY(0);
        float x1 = event.getX(1);
        float y1 = event.getY(1);
        float currentDistance = (float) Math.hypot(x1 - x0, y1 - y0);
        float currentAngle = (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
        float centroidX = (x0 + x1) * 0.5f;
        float centroidY = (y0 + y1) * 0.5f;

        if (gesturePrevDistance <= 0f) {
            resetTwoFingerGestureState(event);
            return;
        }

        float deltaDistance = currentDistance - gesturePrevDistance;
        float deltaAngle = normalizeAngle(currentAngle - gesturePrevAngle);
        float deltaCentroid = (float) Math.hypot(centroidX - gesturePrevCentroidX, centroidY - gesturePrevCentroidY);

        if (twoFingerGestureLeftHeld) {
            float[] prevTrans = XForm.transformPoint(xform, gesturePrevCentroidX, gesturePrevCentroidY);
            float[] currTrans = XForm.transformPoint(xform, centroidX, centroidY);
            if (xServer.isRelativeMouseMovement()) {
                xServer.injectPointerMoveDelta((int) (currTrans[0] - prevTrans[0]), (int) (currTrans[1] - prevTrans[1]));
            } else {
                xServer.injectPointerMove((int) currTrans[0], (int) currTrans[1]);
            }
        }

        TwoFingerGestureType gesture = determineTwoFingerGesture(activeTwoFingerGesture, deltaDistance, deltaAngle, deltaCentroid);
        if (gesture != activeTwoFingerGesture) {
            if (activeTwoFingerGesture != null) {
                if ((activeTwoFingerGesture == TwoFingerGestureType.ROTATE || activeTwoFingerGesture == TwoFingerGestureType.PAN) && twoFingerGestureLeftHeld) {
                    releaseLeftButtonForTwoFingerGesture();
                }
                if (twoFingerGestureListener != null) {
                    twoFingerGestureListener.onTwoFingerGesture(activeTwoFingerGesture, false);
                }
            }
            activeTwoFingerGesture = gesture;
            twoFingerGestureActive = gesture != null;
            if (gesture != null) {
                if ((gesture == TwoFingerGestureType.ROTATE || gesture == TwoFingerGestureType.PAN) && !twoFingerGestureLeftHeld) {
                    pressLeftButtonForTwoFingerGesture(event);
                }
                if (twoFingerGestureListener != null) {
                    twoFingerGestureListener.onTwoFingerGesture(gesture, true);
                }
            }
        }

        gesturePrevDistance = currentDistance;
        gesturePrevAngle = currentAngle;
        gesturePrevCentroidX = centroidX;
        gesturePrevCentroidY = centroidY;
    }

    private TwoFingerGestureType determineTwoFingerGesture(TwoFingerGestureType currentGesture, float deltaDistance, float deltaAngle, float deltaCentroid) {
        float absDistance = Math.abs(deltaDistance);
        float absAngle = Math.abs(deltaAngle);

        if (absDistance > 10f && absDistance > absAngle * 4 && absDistance > deltaCentroid * 1.5f) {
            return deltaDistance > 0 ? TwoFingerGestureType.ZOOM_IN : TwoFingerGestureType.ZOOM_OUT;
        }
        if (absAngle > 5f && absAngle > absDistance * 0.5f && absAngle > deltaCentroid * 1.2f) {
            return TwoFingerGestureType.ROTATE;
        }
        if (deltaCentroid > 8f && deltaCentroid > absDistance * 0.5f && deltaCentroid > absAngle * 0.5f) {
            return TwoFingerGestureType.PAN;
        }

        if (currentGesture == TwoFingerGestureType.ZOOM_IN || currentGesture == TwoFingerGestureType.ZOOM_OUT) {
            if (absDistance > 6f && absDistance > absAngle * 2 && absDistance > deltaCentroid * 1.2f) {
                return currentGesture;
            }
        }
        if (currentGesture == TwoFingerGestureType.ROTATE) {
            if (absAngle > 3f && absAngle > absDistance * 0.25f && absAngle > deltaCentroid * 0.6f) {
                return TwoFingerGestureType.ROTATE;
            }
        }
        if (currentGesture == TwoFingerGestureType.PAN) {
            if (deltaCentroid > 5f && deltaCentroid > absDistance * 0.4f && deltaCentroid > absAngle * 0.4f) {
                return TwoFingerGestureType.PAN;
            }
        }

        return null;
    }

    private void startTwoFingerGesture(TwoFingerGestureType gesture) {
        if (gesture == null) return;
        if (twoFingerGestureActive && gesture == activeTwoFingerGesture) return;
        if (twoFingerGestureActive && activeTwoFingerGesture != null && twoFingerGestureListener != null) {
            twoFingerGestureListener.onTwoFingerGesture(activeTwoFingerGesture, false);
        }
        activeTwoFingerGesture = gesture;
        twoFingerGestureActive = true;
        if (twoFingerGestureListener != null) {
            twoFingerGestureListener.onTwoFingerGesture(gesture, true);
        }
    }

    private void stopTwoFingerGesture() {
        if (!twoFingerGestureActive) return;
        twoFingerGestureActive = false;
        if ((activeTwoFingerGesture == TwoFingerGestureType.ROTATE || activeTwoFingerGesture == TwoFingerGestureType.PAN) && twoFingerGestureLeftHeld) {
            releaseLeftButtonForTwoFingerGesture();
        }
        if (activeTwoFingerGesture != null && twoFingerGestureListener != null) {
            twoFingerGestureListener.onTwoFingerGesture(activeTwoFingerGesture, false);
        }
        activeTwoFingerGesture = null;
        twoFingerGestureLastPointerId = -1;
    }

    private float normalizeAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }

    private void pressLeftButtonForTwoFingerGesture(MotionEvent event) {
        if (twoFingerGestureLeftHeld) return;

        int pointerIndex = event.findPointerIndex(twoFingerGestureLastPointerId);
        float x;
        float y;
        if (pointerIndex >= 0) {
            x = event.getX(pointerIndex);
            y = event.getY(pointerIndex);
        } else {
            x = (event.getX(0) + event.getX(1)) * 0.5f;
            y = (event.getY(0) + event.getY(1)) * 0.5f;
        }

        float[] transformedPoint = XForm.transformPoint(xform, x, y);
        if (xServer.isRelativeMouseMovement()) {
            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int) transformedPoint[0], (int) transformedPoint[1], 0);
            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
        } else {
            xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        }
        twoFingerGestureLeftHeld = true;
    }

    private void releaseLeftButtonForTwoFingerGesture() {
        if (!twoFingerGestureLeftHeld) return;
        if (xServer.isRelativeMouseMovement()) {
            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
        } else {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        }
        twoFingerGestureLeftHeld = false;
    }

    private void handleFingerUp(Finger finger1) {
        switch (numFingers) {
            case 1:
                if (simTouchScreen) {
                    final Runnable clickDelay = () -> {
                        if (continueClick)
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    };
                    postDelayed(clickDelay, CLICK_DELAYED_TIME);
                }
                else if (finger1.isTap()) pressPointerButtonLeft(finger1);
                break;
            case 2:
                Finger finger2 = findSecondFinger(finger1);
                if (finger2 != null && finger1.isTap()) pressPointerButtonRight(finger1);
                break;
            case 4:
                if (fourFingersTapCallback != null) {
                    for (byte i = 0; i < 4; i++) {
                        if (fingers[i] != null && !fingers[i].isTap()) return;
                    }
                    fourFingersTapCallback.run();
                }
                break;
        }

        releasePointerButtonLeft(finger1);
        releasePointerButtonRight(finger1);
    }

    private void handleFingerMove(Finger finger1) {
        boolean skipPointerMove = false;

        Finger finger2 = numFingers == 2 ? findSecondFinger(finger1) : null;
        if (finger2 != null) {
            final float resolutionScale = 1000.0f / Math.min(xServer.screenInfo.width, xServer.screenInfo.height);
            float currDistance = (float)Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y) * resolutionScale;

            if (currDistance < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                scrollAccumY += ((finger1.y + finger2.y) * 0.5f) - (finger1.lastY + finger2.lastY) * 0.5f;

                if (scrollAccumY < -100) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    scrollAccumY = 0;
                }
                else if (scrollAccumY > 100) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    scrollAccumY = 0;
                }
                scrolling = true;
            }
            else if (currDistance >= MAX_TWO_FINGERS_SCROLL_DISTANCE && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) &&
                     finger2.travelDistance() < MAX_TAP_TRAVEL_DISTANCE) {
                pressPointerButtonLeft(finger1);
                skipPointerMove = true;
            }
        }

        if (!scrolling && numFingers <= 2 && !skipPointerMove) {
            int dx = finger1.deltaX();
            int dy = finger1.deltaY();

            if (simTouchScreen) {
                if (System.currentTimeMillis() - finger1.touchTime > CLICK_DELAYED_TIME)
                    xServer.injectPointerMove(finger1.x, finger1.y);
            }
            else if (xServer.isRelativeMouseMovement()) {
                WinHandler winHandler = xServer.getWinHandler();
                winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            }
            else xServer.injectPointerMoveDelta(dx, dy);
        }
    }

    private Finger findSecondFinger(Finger finger) {
        for (byte i = 0; i < MAX_FINGERS; i++) {
            if (fingers[i] != null && fingers[i] != finger) return fingers[i];
        }
        return null;
    }

    private void pressPointerButtonLeft(Finger finger) {
        if (pointerButtonLeftEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            fingerPointerButtonLeft = finger;
        }
    }

    private void pressPointerButtonRight(Finger finger) {
        if (pointerButtonRightEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
            fingerPointerButtonRight = finger;
        }
    }

    private void releasePointerButtonLeft(final Finger finger) {
        if (pointerButtonLeftEnabled && finger == fingerPointerButtonLeft && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                fingerPointerButtonLeft = null;
            }, 30);
        }
    }

    private void releasePointerButtonRight(final Finger finger) {
        if (pointerButtonRightEnabled && finger == fingerPointerButtonRight && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                fingerPointerButtonRight = null;
            }, 30);
        }
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public boolean isPointerButtonLeftEnabled() {
        return pointerButtonLeftEnabled;
    }

    public void setPointerButtonLeftEnabled(boolean pointerButtonLeftEnabled) {
        this.pointerButtonLeftEnabled = pointerButtonLeftEnabled;
    }

    public boolean isPointerButtonRightEnabled() {
        return pointerButtonRightEnabled;
    }

    public void setPointerButtonRightEnabled(boolean pointerButtonRightEnabled) {
        this.pointerButtonRightEnabled = pointerButtonRightEnabled;
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
    }

    public boolean onExternalMouseEvent(MotionEvent event) {
        boolean handled = false;
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            int actionButton = event.getActionButton();
            switch (event.getAction()) {
                case MotionEvent.ACTION_BUTTON_PRESS:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                    } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button press
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                    } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button release
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_HOVER_MOVE:
                    // Coba pakai RELATIVE axis dulu (tersedia untuk mouse Bluetooth/fisik)
                    float relX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
                    float relY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
                    if (relX != 0f || relY != 0f) {
                        // Ini adalah delta — hanya scale diagonal xform yang boleh diterapkan.
                        // xform layout: [n11,n12, n21,n22, dx,dy]
                        // xform[0]=scaleX, xform[3]=scaleY, xform[4]/[5]=translasi offset
                        // Offset translasi TIDAK boleh ditambahkan ke nilai delta.
                        float dx = relX * xform[0];
                        float dy = relY * xform[3];
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)dx, (int)dy, 0);
                        else
                            xServer.injectPointerMoveDelta((int)dx, (int)dy);
                    } else {
                        // Fallback ke posisi absolut (untuk touch/stylus) — XForm penuh benar di sini
                        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
                        else
                            xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_SCROLL:
                    float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (scrollY <= -1.0f) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int)scrollY);
                        else {
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                        }
                    } else if (scrollY >= 1.0f) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0,(int)scrollY);
                        else {
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                        }
                    }
                    handled = true;
                    break;
            }
        }
        return handled;
    }


    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        final float[] result = {0, 0};

        XForm.transformPoint(xform, lastX, lastY, result);
        lastX = result[0];
        lastY = result[1];

        XForm.transformPoint(xform, x, y, result);
        x = result[0];
        y = result[1];

        result[0] = x - lastX;
        result[1] = y - lastY;
        return result;
    }

    private StateListDrawable createTransparentBg() {
        StateListDrawable stateListDrawable = new StateListDrawable();

        ColorDrawable focusedDrawable = new ColorDrawable(Color.TRANSPARENT);
        ColorDrawable defaultDrawable = new ColorDrawable(Color.TRANSPARENT);

        stateListDrawable.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        stateListDrawable.addState(new int[]{}, defaultDrawable);

        return stateListDrawable;
    }

    public void setSimTouchScreen(boolean simTouchScreen) {
        this.simTouchScreen = simTouchScreen;
        xServer.setSimulateTouchScreen(this.simTouchScreen);
    }

    public boolean isSimTouchScreen() {
        return simTouchScreen;
    }

    public void toggleFullscreen() {
        new Handler().postDelayed(() -> updateXform(getWidth(), getHeight(), xServer.screenInfo.width, xServer.screenInfo.height),
                UPDATE_FORM_DELAYED_TIME);
    }
}
