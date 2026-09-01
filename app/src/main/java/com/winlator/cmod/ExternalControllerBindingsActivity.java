package com.winlator.cmod;

import android.content.Intent;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.ExternalControllerBinding;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.ui.inputcontrols.ControllerBindingItem;
import com.winlator.cmod.ui.inputcontrols.ExternalControllerBindingsCallbacks;
import com.winlator.cmod.ui.inputcontrols.ExternalControllerBindingsComposeHost;
import com.winlator.cmod.ui.inputcontrols.ExternalControllerBindingsModel;

import java.util.ArrayList;
import java.util.List;

public class ExternalControllerBindingsActivity extends AppCompatActivity {
    private static final int TYPE_KEYBOARD = 0;
    private static final int TYPE_MOUSE = 1;
    private static final int TYPE_GAMEPAD = 2;

    private ControlsProfile profile;
    private ExternalController controller;
    private ComposeView composeView;
    private int highlightedPosition = -1;

    // Track trigger state to only register on rising edge
    private boolean l2WasPressed = false;
    private boolean r2WasPressed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        int profileId = intent.getIntExtra("profile_id", 0);
        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        String controllerId = intent.getStringExtra("controller_id");

        controller = profile.getController(controllerId);
        if (controller == null) {
            controller = profile.addController(controllerId);
            profile.save();
        }

        composeView = ExternalControllerBindingsComposeHost.create(this, buildModel(), createCallbacks());
        setContentView(composeView);
    }

    private Binding[] bindingValuesForType(int typeIndex) {
        switch (typeIndex) {
            case TYPE_MOUSE:
                return Binding.mouseBindingValues();
            case TYPE_GAMEPAD:
                return Binding.gamepadBindingValues();
            default:
                return Binding.keyboardBindingValues();
        }
    }

    private String[] bindingLabelsForType(int typeIndex) {
        switch (typeIndex) {
            case TYPE_MOUSE:
                return Binding.mouseBindingLabels();
            case TYPE_GAMEPAD:
                return Binding.gamepadBindingLabels();
            default:
                return Binding.keyboardBindingLabels();
        }
    }

    private int typeIndexFor(Binding binding) {
        if (binding.isMouse()) return TYPE_MOUSE;
        if (binding.isGamepad()) return TYPE_GAMEPAD;
        return TYPE_KEYBOARD;
    }

    private int indexOf(Binding[] values, Binding value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        return -1;
    }

    private ExternalControllerBindingsModel buildModel() {
        List<ControllerBindingItem> items = new ArrayList<>();
        int count = controller.getControllerBindingCount();
        for (int position = 0; position < count; position++) {
            ExternalControllerBinding item = controller.getControllerBindingAt(position);
            if (item == null) continue;

            int typeIndex = typeIndexFor(item.getBinding());
            Binding[] values = bindingValuesForType(typeIndex);
            int bindingIndex = indexOf(values, item.getBinding());
            if (bindingIndex < 0) bindingIndex = 0;

            String[] labels = bindingLabelsForType(typeIndex);
            List<String> labelList = new ArrayList<>(labels.length);
            for (String label : labels) labelList.add(label);

            items.add(new ControllerBindingItem(position, item.toString(), typeIndex, labelList, bindingIndex));
        }
        return new ExternalControllerBindingsModel(controller.getName(), items, highlightedPosition);
    }

    private void refreshCompose() {
        if (composeView != null) {
            ExternalControllerBindingsComposeHost.update(composeView, buildModel());
        }
    }

    private ExternalControllerBindingsCallbacks createCallbacks() {
        return new ExternalControllerBindingsCallbacks() {
            @Override
            public void onBack() {
                finish();
            }

            @Override
            public void onRemoveBinding(int position) {
                ExternalControllerBinding item = controller.getControllerBindingAt(position);
                if (item == null) return;
                controller.removeControllerBinding(item);
                profile.save();
                refreshCompose();
            }

            @Override
            public void onBindingTypeChanged(int position, int typeIndex) {
                ExternalControllerBinding item = controller.getControllerBindingAt(position);
                if (item == null) return;
                Binding[] values = bindingValuesForType(typeIndex);
                if (values.length == 0) return;
                Binding newBinding = values[0];
                if (newBinding != item.getBinding()) {
                    item.setBinding(newBinding);
                    profile.save();
                }
                refreshCompose();
            }

            @Override
            public void onBindingValueChanged(int position, int typeIndex, int valueIndex) {
                ExternalControllerBinding item = controller.getControllerBindingAt(position);
                if (item == null) return;
                Binding[] values = bindingValuesForType(typeIndex);
                if (valueIndex < 0 || valueIndex >= values.length) return;
                Binding newBinding = values[valueIndex];
                if (newBinding != item.getBinding()) {
                    item.setBinding(newBinding);
                    profile.save();
                }
                refreshCompose();
            }
        };
    }

    private void updateControllerBinding(int keyCode, Binding binding) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN)
            return;

        ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
        int position;
        if (controllerBinding == null) {
            controllerBinding = new ExternalControllerBinding();
            controllerBinding.setKeyCode(keyCode);
            controllerBinding.setBinding(binding);

            controller.addControllerBinding(controllerBinding);
            profile.save();
            position = controller.getPosition(controllerBinding);
        } else {
            position = controller.getPosition(controllerBinding);
        }
        highlightedPosition = position;
        refreshCompose();
    }

    private void processJoystickInput() {
        final int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        final float[] values = {
                controller.state.thumbLX, controller.state.thumbLY,
                controller.state.thumbRX, controller.state.thumbRY,
                controller.state.getDPadX(), controller.state.getDPadY()
        };

        for (int i = 0; i < axes.length; i++) {
            float value = values[i];
            byte sign = Mathf.sign(value);
            if (sign != 0) {
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                updateControllerBinding(keyCode, Binding.NONE); // Or prompt the user to select a binding
            }
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Accept motion events from any game controller for binding registration
        InputDevice device = event.getDevice();
        if (device != null && ExternalController.isGameController(device)
                && controller.updateStateFromMotionEvent(event)) {

            // Use a higher threshold for binding registration to avoid false triggers
            float l2Value = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_BRAKE));
            float r2Value = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_GAS));

            // Only register L2/R2 on rising edge (first press) past 80% threshold
            boolean l2Pressed = l2Value > 0.8f;
            if (l2Pressed && !l2WasPressed) {
                updateControllerBinding(KeyEvent.KEYCODE_BUTTON_L2, Binding.NONE);
            }
            l2WasPressed = l2Pressed;

            boolean r2Pressed = r2Value > 0.8f;
            if (r2Pressed && !r2WasPressed) {
                updateControllerBinding(KeyEvent.KEYCODE_BUTTON_R2, Binding.NONE);
            }
            r2WasPressed = r2Pressed;

            processJoystickInput();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Accept any gamepad button or D-pad keycode
        if (event.getRepeatCount() == 0 && keyCode != KeyEvent.KEYCODE_BACK) {
            updateControllerBinding(keyCode, Binding.NONE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Consume the key up for gamepad buttons
        if (isGamepadKeyCode(keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isGamepadKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                keyCode == KeyEvent.KEYCODE_BUTTON_X ||
                keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
                keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_L2 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_R2 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL ||
                keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR ||
                keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
                keyCode == KeyEvent.KEYCODE_BUTTON_MODE ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
    }
}
