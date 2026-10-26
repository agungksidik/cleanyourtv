package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.function.Consumer;

/**
 * power_row: small round buttons centred under the dock. Power puts the TV in standby, Restart opens
 * Settings, System at its Restart item, Close force-stops the app opened last (both through HomeKeyService: an
 * app can do none of these itself). TV opens tv_app, a package or "input:<id>" as in app_keys. Down from the dock comes
 * here, Up goes back; the focused button's name shows where the dock app's name does.
 */
final class PowerRow {
    private static final String SYSTEM_SETTINGS = "com.android.tv.settings.DEVICES";
    private static final int SIZE_DP = 34;

    final LinearLayout row;
    private final Activity activity;
    private final String tvApp;
    private final Runnable up;
    private final Consumer<String> focused;
    private final View[] buttons;

    PowerRow(Activity activity, FrameLayout root, String tvApp, Runnable up, Consumer<String> focused) {
        this.activity = activity;
        this.tvApp = tvApp;
        this.up = up;
        this.focused = focused;
        float dp = activity.getResources().getDisplayMetrics().density;
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Room for the focused button's zoom, which the row would otherwise cut off.
        int pad = Math.round(6 * dp);
        row.setPadding(pad, pad, pad, pad);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        buttons = new View[]{
                button(R.drawable.ic_power, R.string.power_off, this::power, dp),
                button(R.drawable.ic_restart, R.string.restart, this::restart, dp),
                button(R.drawable.ic_livetv, R.string.live_tv, this::tv, dp),
                button(R.drawable.ic_close_app, R.string.close_last_app, this::closeLast, dp)};
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(SIZE_DP * dp), Math.round(SIZE_DP * dp));
            if (i > 0) lp.leftMargin = Math.round(18 * dp);
            row.addView(buttons[i], lp);
            final boolean first = i == 0, last = i == buttons.length - 1;
            buttons[i].setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { this.up.run(); return true; }
                return key == KeyEvent.KEYCODE_DPAD_DOWN
                        || (key == KeyEvent.KEYCODE_DPAD_LEFT && first) || (key == KeyEvent.KEYCODE_DPAD_RIGHT && last);
            });
        }
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        p.bottomMargin = Math.round(8 * dp);
        root.addView(row, p);
    }

    /** Focuses the button nearest to x, the centre of the dock tile Down came from, in window coordinates. */
    void focus(float x) {
        View best = buttons[0];
        float bestDistance = Float.MAX_VALUE;
        int[] at = new int[2];
        for (View b : buttons) {
            b.getLocationInWindow(at);
            float distance = Math.abs(at[0] + b.getWidth() / 2f - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = b;
            }
        }
        best.requestFocus();
    }

    private View button(int icon, int label, Runnable action, float dp) {
        ImageView b = new ImageView(activity);
        b.setImageResource(icon);
        b.setContentDescription(activity.getString(label));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = Math.round(8 * dp);
        b.setPadding(inset, inset, inset, inset);
        b.setFocusable(true);
        GradientDrawable rest = new GradientDrawable();
        rest.setShape(GradientDrawable.OVAL);
        rest.setColor(0x591A2230);
        rest.setStroke(Math.max(1, Math.round(dp)), 0x2EFFFFFF);
        GradientDrawable lit = new GradientDrawable();
        lit.setShape(GradientDrawable.OVAL);
        lit.setColor(0xF2FFFFFF);
        b.setBackground(rest);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? lit : rest);
            b.setColorFilter(has ? 0xFF1A2230 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(160).start();
            if (has) focused.accept(activity.getString(label));
        });
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void power() {
        if (!HomeKeyService.standby()) toast(activity, R.string.power_needs_helper);
    }

    private void restart() {
        if (!HomeKeyService.openRestart()) {
            toast(activity, R.string.power_needs_helper);
            return;
        }
        start(activity, new Intent(SYSTEM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private void tv() {
        RemoteKeys.launch(activity, tvApp.isEmpty() ? "com.tcl.tv" : tvApp);
    }

    private void closeLast() {
        String pkg = LastOpened.latest(activity);
        if (pkg == null) toast(activity, R.string.no_last_app);
        else forceStop(activity, pkg);
    }

    /** Opens the app's App info page and has HomeKeyService press Force stop and OK there, then come back Home. */
    static void forceStop(Activity activity, String pkg) {
        if (!HomeKeyService.forceStop()) {
            toast(activity, R.string.power_needs_helper);
            return;
        }
        start(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private static void start(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(activity, R.string.not_available);
        }
    }

    private static void toast(Activity activity, int text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
