package com.example.selfpilot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.example.selfpilot.service.FocusTimerService;
import com.example.selfpilot.ui.focus.FocusLockActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private OnBackPressedCallback focusBackCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.example.selfpilot.util.FrozenAppsManager.init(this);
        ensureGuardianRunning();
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null && bottomNav != null) {
            NavController navController = navHostFragment.getNavController();
            NavigationUI.setupWithNavController(bottomNav, navController);
        }

        // While a session runs, back does nothing here either.
        focusBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // Intentionally ignored: a focus session cannot be left early.
            }
        };
        getOnBackPressedDispatcher().addCallback(this, focusBackCallback);

        handleNavigationIntent();
    }

    @Override
    protected void onStart() {
        super.onStart();

        boolean running = FocusTimerService.isRunning();
        if (focusBackCallback != null) {
            focusBackCallback.setEnabled(running);
        }
        // If the app is reopened mid-session, go straight back to the countdown screen.
        if (running && !FocusLockActivity.VISIBLE) {
            FocusLockActivity.start(this);
        }
    }

    /**
     * SelfPilot must always keep running in the background: the freezer is only as reliable
     * as the process that enforces it.
     */
    private void ensureGuardianRunning() {
        requestNotificationPermission();
        com.example.selfpilot.service.GuardianService.start(this);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void handleNavigationIntent() {
        Intent intent = getIntent();
        if (intent == null) return;
        int navId = intent.getIntExtra("NAVIGATE_TO", -1);
        if (navId == -1) return;

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(navId);
        }
        intent.removeExtra("NAVIGATE_TO");
    }
}

