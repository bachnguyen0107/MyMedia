package com.example.mymediaplayer;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
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

public class RegisterActivity extends AppCompatActivity {
    private SessionManager session;
    private UserDao userDao;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        session = new SessionManager(this);
        userDao = AppDatabase.getInstance(this).userDao();

        EditText etUsername = findViewById(R.id.et_username);
        EditText etPassword = findViewById(R.id.et_password);
        EditText etConfirm = findViewById(R.id.et_confirm);
        Button btnRegister = findViewById(R.id.btn_register);
        TextView linkLogin = findViewById(R.id.link_login);

        btnRegister.setOnClickListener(v -> {
            String u = etUsername.getText().toString().trim();
            String p = etPassword.getText().toString();
            String c = etConfirm.getText().toString();
            if (TextUtils.isEmpty(u) || TextUtils.isEmpty(p) || TextUtils.isEmpty(c)) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!p.equals(c)) {
                Toast.makeText(this, R.string.passwords_no_match, Toast.LENGTH_SHORT).show();
                return;
            }
            if (userDao.findByUsername(u) != null) {
                Toast.makeText(this, R.string.username_taken, Toast.LENGTH_SHORT).show();
                return;
            }
            String salt = PasswordUtil.generateSaltHex();
            String hash = PasswordUtil.hashPassword(p, salt);
            long now = System.currentTimeMillis();
            UserEntity user = new UserEntity(u, hash, salt, now);
            int newId = (int) userDao.insert(user);
            session.login(newId, u);
            Toast.makeText(this, R.string.registered_successfully, Toast.LENGTH_SHORT).show();
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });

        linkLogin.setOnClickListener(v -> finish());
    }
}

