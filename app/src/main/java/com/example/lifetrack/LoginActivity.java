package com.example.lifetrack;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class LoginActivity extends AppCompatActivity {

    private static final String KEY_PASSWORD_VISIBLE = "password_visible";

    private ImageView btnBack;
    private EditText editEmail;
    private EditText editPassword;
    private ImageView btnTogglePassword;
    private TextView tvForgotPassword;
    private TextView tvRegister;
    private Button btnLogin;
    private boolean passwordVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        btnBack = findViewById(R.id.btnBack);
        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        btnTogglePassword = findViewById(R.id.btnTogglePassword);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
        tvRegister = findViewById(R.id.tvRegister);
        // 简化为单次 findViewById：保留 instanceof 检查避免布局类型不符时 ClassCastException
        View btnLoginView = findViewById(R.id.btnLogin);
        btnLogin = btnLoginView instanceof Button ? (Button) btnLoginView : null;

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        if (savedInstanceState != null) {
            passwordVisible = savedInstanceState.getBoolean(KEY_PASSWORD_VISIBLE, false);
            if (editPassword != null) {
                applyPasswordVisibility();
            }
        }

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        if (btnTogglePassword != null && editPassword != null) {
            btnTogglePassword.setImageResource(passwordVisible ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            btnTogglePassword.setAlpha(passwordVisible ? 0.7f : 0.4f);
            btnTogglePassword.setOnClickListener(v -> {
                passwordVisible = !passwordVisible;
                applyPasswordVisibility();
                editPassword.setSelection(editPassword.getText().length());
            });
        }

        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> attemptLogin());
        }

        if (tvForgotPassword != null) {
            tvForgotPassword.setOnClickListener(v ->
                    Toast.makeText(this, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show());
        }
        if (tvRegister != null) {
            tvRegister.setOnClickListener(v ->
                    Toast.makeText(this, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show());
        }
    }

    private void applyPasswordVisibility() {
        if (editPassword == null || btnTogglePassword == null) return;
        if (passwordVisible) {
            editPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            btnTogglePassword.setImageResource(R.drawable.ic_eye);
            btnTogglePassword.setAlpha(0.7f);
        } else {
            editPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            btnTogglePassword.setImageResource(R.drawable.ic_eye_off);
            btnTogglePassword.setAlpha(0.4f);
        }
    }

    private void attemptLogin() {
        if (isFinishing() || isDestroyed()) return;
        String email = editEmail != null ? editEmail.getText().toString().trim() : "";
        String password = editPassword != null ? editPassword.getText().toString() : "";

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, R.string.activity_login_input_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        // 工程暂无服务端登录 API（KeyManager 已硬编码），此处仅校验非空后跳转主页
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_PASSWORD_VISIBLE, passwordVisible);
    }
}
