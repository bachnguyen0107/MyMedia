package com.example.mymediaplayer;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.mymediaplayer.data.AppDatabase;
import com.example.mymediaplayer.data.PasswordUtil;
import com.example.mymediaplayer.data.SessionManager;
import com.example.mymediaplayer.data.UserDao;
import com.example.mymediaplayer.data.UserEntity;

public class LoginActivity extends AppCompatActivity {
    private SessionManager session;
    private UserDao userDao;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionManager(this);
        if (session.isLoggedIn()) {
            goToMain();
            return;
        }
        setContentView(R.layout.activity_login);
        userDao = AppDatabase.getInstance(this).userDao();

        EditText etUsername = findViewById(R.id.et_username);
        EditText etPassword = findViewById(R.id.et_password);
        Button btnLogin = findViewById(R.id.btn_login);
        TextView linkRegister = findViewById(R.id.link_register);

        btnLogin.setOnClickListener(v -> {
            String u = etUsername.getText().toString().trim();
            String p = etPassword.getText().toString();
            if (TextUtils.isEmpty(u) || TextUtils.isEmpty(p)) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show();
                return;
            }
            UserEntity user = userDao.findByUsername(u);
            if (user == null) {
                Toast.makeText(this, R.string.user_not_found, Toast.LENGTH_SHORT).show();
                return;
            }
            String hash = PasswordUtil.hashPassword(p, user.salt);
            if (!hash.equals(user.passwordHash)) {
                Toast.makeText(this, R.string.invalid_credentials, Toast.LENGTH_SHORT).show();
                return;
            }
            session.login(user.id, user.username);
            goToMain();
        });

        linkRegister.setOnClickListener(v -> {
            startActivity(new Intent(this, RegisterActivity.class));
        });
    }

    private void goToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}

