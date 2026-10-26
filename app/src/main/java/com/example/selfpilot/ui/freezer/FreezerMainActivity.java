package com.example.selfpilot.ui.freezer;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.selfpilot.MainActivity;
import com.example.selfpilot.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class FreezerMainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_freezer_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.getMenu().setGroupCheckable(0, false, true);

        bottomNav.setOnItemSelectedListener(item -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("NAVIGATE_TO", item.getItemId());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
            return false;
        });

        // Set default selection
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.freezer_nav_host_fragment, new FreezerDashboardFragment())
                    .commit();
        }
    }
}
