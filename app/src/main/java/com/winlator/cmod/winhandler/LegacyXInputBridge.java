package com.winlator.cmod.winhandler;

import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.GamepadState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Jalur XInput lama Winlator (UDP, seperti experimental-v2) untuk mode Aggressive. Hanya SATU gamepad yang terlihat
 * oleh DLL xinput/dinput Winlator, jadi di sini dipilih sumbernya: DroidDeck, pad virtual Winlator, atau controller
 * fisik. Sumber yang inputnya paling baru berubah yang dipakai; selama belum ada input, urutan prioritasnya
 * DroidDeck > pad virtual > controller fisik (sumber yang tersedia saja).
 */
final class LegacyXInputBridge {
    static final int NONE = 0, DROIDDECK = 1, VIRTUAL = 2, PHYSICAL = 3;
    static final int DROIDDECK_DEVICE_ID = -7001;

    /** Gamepad terpilih untuk satu balasan/dorongan paket. */
    static final class Pad {
        final int source;
        final int id;
        final String name;
        final GamepadState state;

        Pad(int source, int id, String name, GamepadState state) {
            this.source = source;
            this.id = id;
            this.name = name;
            this.state = state;
        }
    }

    private final byte[] neutral = stateBytes(new GamepadState());
    private final byte[][] last = new byte[4][];
    private int active = NONE;

    private volatile boolean droidDeckActive;
    private volatile GamepadState droidDeckState;
    private volatile ExternalController physical;

    // Pencarian controller fisik (iterasi perangkat input) di-cache singkat: game bisa polling ratusan kali/detik.
    private final long physicalRecheckNanos;
    private ExternalController physicalCached;
    private long physicalCheckedAt;
    private boolean physicalChecked;

    LegacyXInputBridge() {
        this(750_000_000L);
    }

    LegacyXInputBridge(long physicalRecheckNanos) {
        this.physicalRecheckNanos = physicalRecheckNanos;
    }

    static byte[] stateBytes(GamepadState state) {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        state.writeTo(buffer);
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    /** ID yang diumumkan ke DLL; stabil selama profil sama supaya dorongan paket tetap cocok walau sumber berganti. */
    static int padId(ControlsProfile profile) {
        return profile != null ? profile.id : 1;
    }

    /**
     * Catat state sebuah sumber. Sumber menjadi aktif bila state-nya berubah; pembacaan pertama yang masih netral
     * (inisialisasi) tidak merebut aktif.
     */
    private synchronized void update(int source, GamepadState state) {
        byte[] bytes = stateBytes(state);
        byte[] previous = last[source];
        if (previous == null) {
            last[source] = bytes;
            if (!Arrays.equals(bytes, neutral)) active = source;
            return;
        }
        if (!Arrays.equals(previous, bytes)) {
            last[source] = bytes;
            active = source;
        }
    }

    private synchronized void forget(int source) {
        last[source] = null;
        if (active == source) active = NONE;
    }

    synchronized void reset() {
        Arrays.fill(last, null);
        active = NONE;
        droidDeckActive = false;
        droidDeckState = null;
        physical = null;
        physicalChecked = false;
    }

    void droidDeckUpdate(GamepadState state) {
        if (state == null) return;
        droidDeckState = state;
        droidDeckActive = true;
        update(DROIDDECK, state);
    }

    void droidDeckRelease() {
        droidDeckActive = false;
        droidDeckState = null;
        forget(DROIDDECK);
    }

    void virtualUpdate(ControlsProfile profile) {
        if (profile != null && profile.isVirtualGamepad()) update(VIRTUAL, profile.getGamepadState());
        else forget(VIRTUAL);
    }

    void physicalUpdate(ExternalController controller, ControlsProfile profile) {
        if (controller == null) return;
        physical = controller;
        invalidatePhysicalCache();
        update(PHYSICAL, physicalState(controller, profile));
    }

    void physicalRemoved(int deviceId) {
        ExternalController controller = physical;
        if (controller != null && controller.getDeviceId() == deviceId) {
            physical = null;
            forget(PHYSICAL);
        }
        invalidatePhysicalCache();
    }

    private synchronized void invalidatePhysicalCache() {
        physicalChecked = false;
    }

    /** Sama seperti jalur evdev: pakai state hasil remap jika profil punya binding untuk controller ini. */
    private static GamepadState physicalState(ExternalController controller, ControlsProfile profile) {
        if (profile != null) {
            ExternalController profileController = profile.getController(controller.getDeviceId());
            if (profileController != null && profileController.getControllerBindingCount() > 0)
                return controller.remappedState;
        }
        return controller.state;
    }

    /** Controller fisik: instance dari event (state hidup) bila masih tersambung, kalau belum ada event cari yang tersambung. */
    private ExternalController physicalController() {
        final long now = System.nanoTime();
        synchronized (this) {
            if (physicalChecked && now - physicalCheckedAt < physicalRecheckNanos) return physicalCached;
        }
        ExternalController controller = physical;
        if (controller == null || !controller.isConnected()) {
            physical = null;
            controller = ExternalController.getController(0);
        }
        synchronized (this) {
            physicalCached = controller;
            physicalCheckedAt = now;
            physicalChecked = true;
        }
        return controller;
    }

    /** Pilih gamepad yang diumumkan dan dikirim; null bila tidak ada sumber (balasan "tidak ada gamepad"). */
    Pad pick(ControlsProfile profile) {
        final boolean dd = droidDeckActive && droidDeckState != null;
        final boolean virtual = profile != null && profile.isVirtualGamepad();
        final ExternalController controller = physicalController();

        int source;
        synchronized (this) {
            source = active;
            boolean available = source == DROIDDECK ? dd
                    : source == VIRTUAL ? virtual
                    : source == PHYSICAL && controller != null;
            if (!available) source = dd ? DROIDDECK : virtual ? VIRTUAL : controller != null ? PHYSICAL : NONE;
        }

        switch (source) {
            case DROIDDECK:
                return new Pad(source, padId(profile), "DroidDeck", droidDeckState);
            case VIRTUAL:
                return new Pad(source, padId(profile), profile.getName(), profile.getGamepadState());
            case PHYSICAL:
                return new Pad(source, padId(profile), controller.getName(), physicalState(controller, profile));
            default:
                return null;
        }
    }

    /** Balasan GET_GAMEPAD: kode, [id, flag input, panjang nama, nama] atau int 0 jika tidak ada gamepad. */
    static void writeInfo(ByteBuffer out, byte code, Pad pad, byte inputType) {
        out.rewind();
        out.put(code);
        if (pad != null) {
            out.putInt(pad.id);
            out.put(inputType);
            byte[] bytes = (pad.name != null ? pad.name : "Gamepad").getBytes();
            if (bytes.length > 48) bytes = Arrays.copyOf(bytes, 48); // buffer kirim 64 byte
            out.putInt(bytes.length);
            out.put(bytes);
        } else {
            out.putInt(0);
        }
    }

    /** Balasan/dorongan GET_GAMEPAD_STATE: kode, enabled, [id, state 13 byte]. */
    static void writeState(ByteBuffer out, byte code, Pad pad, int id) {
        out.rewind();
        out.put(code);
        out.put((byte) (pad != null ? 1 : 0));
        if (pad != null) {
            out.putInt(id);
            pad.state.writeTo(out);
        }
    }
}
