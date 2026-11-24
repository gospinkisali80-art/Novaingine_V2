package com.novaingine.v2;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.animation.ObjectAnimator;
import android.animation.AnimatorSet;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public final class AuthActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FirebaseAuth auth;
    private FirebaseFirestore database;
    private boolean registerMode = false;
    private boolean busy = false;
    private LinearLayout form;
    private EditText nameField;
    private EditText emailField;
    private EditText passwordField;
    private EditText confirmField;
    private TextView message;
    private Button primaryButton;
    private TextView modeButton;
    private int cyan = Color.rgb(55, 207, 255);
    private int violet = Color.rgb(133, 93, 255);
    private int background = Color.rgb(7, 11, 23);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        showIntro();
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeWidth > 0) d.setStroke(dp(strokeWidth), strokeColor);
        return d;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void showIntro() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF071326, 0xFF10102B, 0xFF080B18}));
        root.setClipChildren(false);
        root.setClipToPadding(false);

        View outerGlow = new View(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.OVAL);
        glow.setColor(0x2235CFFF);
        glow.setStroke(dp(2), 0x5535CFFF);
        outerGlow.setBackground(glow);
        FrameLayout.LayoutParams glowP = new FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER);
        glowP.bottomMargin = dp(105);
        root.addView(outerGlow, glowP);

        TextView emblem = text("N", 64, Color.WHITE, true);
        emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(rounded(0xFF142A49, 30, cyan, 2));
        emblem.setElevation(dp(16));
        FrameLayout.LayoutParams emblemP = new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER);
        emblemP.bottomMargin = dp(105);
        root.addView(emblem, emblemP);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER);
        TextView brand = text("NOVA ENGINE", 25, Color.WHITE, true);
        brand.setLetterSpacing(0.16f);
        TextView line = text("CONNECT YOU AND GAMES", 12, cyan, true);
        line.setLetterSpacing(0.18f);
        TextView creator = text("CREATED BY  M E S H", 11, 0xFF9AAAC5, true);
        creator.setLetterSpacing(0.18f);
        words.addView(brand);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        words.addView(line, lp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(38);
        words.addView(creator, cp);
        FrameLayout.LayoutParams wordsP = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        wordsP.topMargin = dp(160);
        root.addView(words, wordsP);

        TextView loading = text("INITIALIZING EXPERIENCE", 9, 0xFF7185A5, true);
        loading.setLetterSpacing(0.2f);
        loading.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams loadP = new FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM);
        loadP.bottomMargin = dp(36);
        root.addView(loading, loadP);

        View track = new View(this);
        track.setBackground(rounded(0xFF202B42, 8, 0, 0));
        FrameLayout.LayoutParams trackP = new FrameLayout.LayoutParams(dp(180), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        trackP.bottomMargin = dp(28);
        root.addView(track, trackP);
        View progress = new View(this);
        progress.setBackground(rounded(cyan, 8, 0, 0));
        FrameLayout.LayoutParams progressP = new FrameLayout.LayoutParams(dp(24), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        progressP.bottomMargin = dp(28);
        root.addView(progress, progressP);

        setContentView(root);

        AnimatorSet pulse = new AnimatorSet();
        ObjectAnimator sx = ObjectAnimator.ofFloat(emblem, View.SCALE_X, 0.88f, 1.08f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(emblem, View.SCALE_Y, 0.88f, 1.08f, 1f);
        ObjectAnimator rotate = ObjectAnimator.ofFloat(outerGlow, View.ROTATION, 0f, 180f);
        ObjectAnimator glowScale = ObjectAnimator.ofFloat(outerGlow, View.SCALE_X, 0.88f, 1.12f, 0.88f);
        sx.setDuration(1500); sy.setDuration(1500); rotate.setDuration(2600); glowScale.setDuration(1900);
        rotate.setRepeatCount(ObjectAnimator.INFINITE);
        glowScale.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.playTogether(sx, sy, rotate, glowScale);
        pulse.start();

        ObjectAnimator progressAnim = ObjectAnimator.ofFloat(progress, View.SCALE_X, 1f, 7.2f);
        progressAnim.setDuration(2400);
        progressAnim.setInterpolator(new DecelerateInterpolator());
        progressAnim.start();

        handler.postDelayed(() -> {
            if (isFinishing()) return;
            initializeFirebaseAndContinue();
        }, 2700L);
    }

    private void initializeFirebaseAndContinue() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this);
            if (FirebaseApp.getApps(this).isEmpty()) {
                showConfigurationMessage();
                return;
            }
            auth = FirebaseAuth.getInstance();
            database = FirebaseFirestore.getInstance();
            FirebaseUser current = auth.getCurrentUser();
            if (current != null) openEditor();
            else showAuthForm();
        } catch (Exception error) {
            showConfigurationMessage();
        }
    }

    private void showConfigurationMessage() {
        showAuthForm();
        message.setText("Firebase is not connected yet. Add your app's google-services.json file to the app/ folder.");
        message.setTextColor(0xFFFFC36B);
        primaryButton.setEnabled(false);
        modeButton.setEnabled(false);
    }

    private void showAuthForm() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF071326, 0xFF10102B, 0xFF080B18}));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(28), dp(24), dp(28));
        scroll.addView(page);

        TextView miniBrand = text("N O V A I N G I N E", 12, cyan, true);
        miniBrand.setGravity(Gravity.CENTER);
        page.addView(miniBrand);

        TextView heading = text("Welcome to your world.", 26, Color.WHITE, true);
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(22);
        page.addView(heading, hp);

        TextView subtitle = text("Your projects. Your creations. Your universe.", 12, 0xFF9AAAC5, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(8);
        page.addView(subtitle, sp);

        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(22), dp(20), dp(20));
        form.setBackground(rounded(0xEE111B2D, 20, 0x5535CFFF, 1));
        form.setElevation(dp(12));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(28);
        page.addView(form, fp);

        TextView formTitle = text("SIGN IN", 17, Color.WHITE, true);
        formTitle.setLetterSpacing(0.12f);
        form.addView(formTitle);

        nameField = makeField("Your name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORD);
        emailField = makeField("Email address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        passwordField = makeField("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmField = makeField("Confirm password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        addField(form, nameField);
        addField(form, emailField);
        addField(form, passwordField);
        addField(form, confirmField);

        message = text("", 11, 0xFFFF9C9C, false);
        message.setPadding(0, dp(8), 0, dp(4));
        form.addView(message);

        primaryButton = new Button(this);
        primaryButton.setText("SIGN IN");
        primaryButton.setTextColor(Color.WHITE);
        primaryButton.setTextSize(13);
        primaryButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        primaryButton.setAllCaps(false);
        primaryButton.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF168DCE, 0xFF6551D9}));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(50));
        bp.topMargin = dp(12);
        form.addView(primaryButton, bp);
        primaryButton.setOnClickListener(v -> submitAuth());

        TextView forgot = text("Forgot password?", 11, cyan, true);
        forgot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams forgotP = new LinearLayout.LayoutParams(-1, dp(42));
        form.addView(forgot, forgotP);
        forgot.setOnClickListener(v -> resetPassword());

        modeButton = text("NEW HERE?  CREATE AN ACCOUNT", 11, 0xFFD0D9E8, true);
        modeButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(48));
        mp.topMargin = dp(4);
        form.addView(modeButton, mp);
        modeButton.setOnClickListener(v -> {
            registerMode = !registerMode;
            updateMode();
        });

        TextView footer = text("CREATED BY MESH  •  NOVA ENGINE", 10, 0xFF7185A5, true);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footP = new LinearLayout.LayoutParams(-1, dp(54));
        page.addView(footer, footP);

        setContentView(scroll);
        updateMode();
        form.setAlpha(0f);
        form.setTranslationY(dp(18));
        form.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
    }

    private EditText makeField(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(13);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xFF7D8CA5);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(rounded(0xFF0B1424, 11, 0x443D6388, 1));
        return field;
    }

    private void addField(LinearLayout parent, EditText field) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.topMargin = dp(12);
        parent.addView(field, p);
    }

    private void updateMode() {
        if (form == null) return;
        ((TextView)form.getChildAt(0)).setText(registerMode ? "CREATE YOUR ACCOUNT" : "SIGN IN");
        nameField.setVisibility(registerMode ? View.VISIBLE : View.GONE);
        confirmField.setVisibility(registerMode ? View.VISIBLE : View.GONE);
        primaryButton.setText(registerMode ? "CREATE ACCOUNT" : "SIGN IN");
        modeButton.setText(registerMode ? "ALREADY HAVE AN ACCOUNT?  SIGN IN" : "NEW HERE?  CREATE AN ACCOUNT");
        message.setText("");
    }

    private void submitAuth() {
        if (busy || auth == null) return;
        String email = emailField.getText().toString().trim();
        String password = passwordField.getText().toString();
        String displayName = nameField.getText().toString().trim();
        String confirm = confirmField.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            showError("Enter your email and password.");
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Enter a valid email address.");
            return;
        }
        if (registerMode) {
            if (displayName.isEmpty()) { showError("Enter your name."); return; }
            if (!password.equals(confirm)) { showError("Your passwords do not match."); return; }
            if (password.length() < 6) { showError("Use a password with at least 6 characters."); return; }
            setBusy(true, "CREATING ACCOUNT...");
            auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        if (!task.isSuccessful()) {
                            setBusy(false, null);
                            showError(authError(task.getException()));
                            return;
                        }
                        FirebaseUser user = auth.getCurrentUser();
                        if (user == null) { setBusy(false, null); showError("Account created, but sign-in did not finish."); return; }
                        UserProfileChangeRequest request = new UserProfileChangeRequest.Builder()
                                .setDisplayName(displayName).build();
                        user.updateProfile(request).addOnCompleteListener(profileTask ->
                                saveUserProfile(user, displayName, true));
                    });
        } else {
            setBusy(true, "SIGNING IN...");
            auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        if (!task.isSuccessful()) {
                            setBusy(false, null);
                            showError(authError(task.getException()));
                            return;
                        }
                        FirebaseUser user = auth.getCurrentUser();
                        if (user == null) { setBusy(false, null); showError("Sign-in could not be completed."); return; }
                        String name = user.getDisplayName();
                        if (name == null || name.trim().isEmpty()) name = email.substring(0, email.indexOf("@"));
                        saveUserProfile(user, name, false);
                    });
        }
    }

    private void saveUserProfile(FirebaseUser user, String name, boolean isNew) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("uid", user.getUid());
        profile.put("displayName", name);
        profile.put("email", user.getEmail());
        profile.put("lastLoginAt", FieldValue.serverTimestamp());
        if (isNew) profile.put("createdAt", FieldValue.serverTimestamp());
        database.collection("users").document(user.getUid()).set(profile,
                com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(unused -> openEditor())
                .addOnFailureListener(error -> {
                    // Authentication is already valid. A profile write failure must not
                    // lock the owner out; the profile can be repaired on the next login.
                    openEditor();
                    Toast.makeText(this, "Signed in. Profile sync will retry next time.", Toast.LENGTH_LONG).show();
                });
    }

    private void resetPassword() {
        if (auth == null) return;
        String email = emailField.getText().toString().trim();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Enter your email address first.");
            return;
        }
        auth.sendPasswordResetEmail(email).addOnCompleteListener(this, task -> {
            if (task.isSuccessful()) showError("Password reset email sent. Check your inbox.");
            else showError(authError(task.getException()));
        });
    }

    private String authError(Exception error) {
        if (error == null) return "Something went wrong. Please try again.";
        String m = error.getMessage();
        if (m == null) return "Something went wrong. Please try again.";
        String lower = m.toLowerCase();
        if (lower.contains("no user record") || lower.contains("user not found")) return "No account was found for that email.";
        if (lower.contains("password is invalid") || lower.contains("wrong password") || lower.contains("invalid credential")) return "Email or password is incorrect.";
        if (lower.contains("email address is already in use")) return "An account already uses this email. Sign in instead.";
        if (lower.contains("network")) return "Network unavailable. Check your internet connection.";
        return "Could not complete that request. Check your details and try again.";
    }

    private void showError(String value) {
        if (message != null) message.setText(value);
    }

    private void setBusy(boolean value, String label) {
        busy = value;
        if (primaryButton != null) {
            primaryButton.setEnabled(!value);
            primaryButton.setText(value ? label : (registerMode ? "CREATE ACCOUNT" : "SIGN IN"));
            primaryButton.setAlpha(value ? 0.65f : 1f);
        }
        if (modeButton != null) modeButton.setEnabled(!value);
    }

    private void openEditor() {
        if (isFinishing()) return;
        handler.removeCallbacksAndMessages(null);
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
