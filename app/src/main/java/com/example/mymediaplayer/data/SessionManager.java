package com.example.mymediaplayer.data;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {
    private final SharedPreferences prefs;

    public SessionManager(Context ctx) {
        this.prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE);
    }

    public boolean isLoggedIn() {
        return prefs.contains("userId");
    }

    public int getUserId() {
        return prefs.getInt("userId", -1);
    }

    public String getUsername() {
        return prefs.getString("username", "");
    }

    public void login(int userId, String username) {
        prefs.edit().putInt("userId", userId).putString("username", username).apply();
    }

    public void logout() {
        prefs.edit().clear().apply();
    }
}

