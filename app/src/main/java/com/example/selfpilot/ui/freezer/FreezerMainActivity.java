package com.example.selfpilot.ui.freezer;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.selfpilot.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class FreezerMainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_freezer_main);

        BottomNavigationView bottomNav = findViewById(R.id.freezer_bottom_nav);

        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;

            int itemId = item.getItemId();
            if (itemId == R.id.nav_freezer_dashboard) {
                selectedFragment = new FreezerDashboardFragment();
            } else if (itemId == R.id.nav_freezer_manage) {
                selectedFragment = new FreezerManageAppsFragment();
            } else if (itemId == R.id.nav_freezer_settings) {
                selectedFragment = new FreezerSettingsFragment();
            }

            if (selectedFragment != null) {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.freezer_nav_host_fragment, selectedFragment)
                        .commit();
            }
            return true;
        });

        // Set default selection
        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_freezer_dashboard);
        }
    }
}
