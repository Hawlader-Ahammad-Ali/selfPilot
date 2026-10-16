package com.example.selfpilot.ui.focus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.service.FocusTimerService;
import com.example.selfpilot.util.ScoreCalculator;

import java.util.Locale;

public class FocusFragment extends Fragment {

    private TextView tvTimer;
    private RadioGroup rgPresets;
    private RadioButton rb25Min;
    private RadioButton rb50Min;
    private RadioButton rbCustomMin;
    private LinearLayout layoutCustomInput;
    private EditText etCustomMinutes;

    private Button btnStart;
    private Button btnPause;
    private Button btnResume;
    private Button btnCancel;

    private boolean isTimerRunning = false;
    private boolean isTimerPaused = false;

    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null) {
                if (intent.getAction().equals(FocusTimerService.ACTION_TIMER_TICK)) {
                    long timeLeft = intent.getLongExtra(FocusTimerService.EXTRA_TIME_LEFT, 0);
                    isTimerRunning = intent.getBooleanExtra(FocusTimerService.EXTRA_IS_RUNNING, isTimerRunning);
                    isTimerPaused = intent.getBooleanExtra(FocusTimerService.EXTRA_IS_PAUSED, isTimerPaused);
                    updateTimerUI(timeLeft);
                    updateButtonsState();
                } else if (intent.getAction().equals(FocusTimerService.ACTION_TIMER_FINISHED)) {
                    int duration = intent.getIntExtra(FocusTimerService.EXTRA_DURATION, 0);
                    handleTimerFinished(duration);
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_focus, container, false);

        tvTimer = view.findViewById(R.id.tvTimer);
        rgPresets = view.findViewById(R.id.rgPresets);
        rb25Min = view.findViewById(R.id.rb25Min);
        rb50Min = view.findViewById(R.id.rb50Min);
        rbCustomMin = view.findViewById(R.id.rbCustomMin);
        layoutCustomInput = view.findViewById(R.id.layoutCustomInput);
        etCustomMinutes = view.findViewById(R.id.etCustomMinutes);

        btnStart = view.findViewById(R.id.btnStart);
        btnPause = view.findViewById(R.id.btnPause);
        btnResume = view.findViewById(R.id.btnResume);
        btnCancel = view.findViewById(R.id.btnCancel);

        setupPresetListeners();

        btnStart.setOnClickListener(v -> startTimer());
        btnPause.setOnClickListener(v -> pauseTimer());
        btnResume.setOnClickListener(v -> resumeTimer());
        btnCancel.setOnClickListener(v -> cancelTimer());

        return view;
    }

    private void setupPresetListeners() {
        rgPresets.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbCustomMin) {
                layoutCustomInput.setVisibility(View.VISIBLE);
            } else {
                layoutCustomInput.setVisibility(View.GONE);
                int duration = checkedId == R.id.rb25Min ? 25 : 50;
                updateTimerUI(duration * 60 * 1000L);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(FocusTimerService.ACTION_TIMER_TICK);
        filter.addAction(FocusTimerService.ACTION_TIMER_FINISHED);
        if (getActivity() != null) {
            ContextCompat.registerReceiver(
                    getActivity(),
                    timerReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );

            // Query active service status
            Intent queryIntent = new Intent(getContext(), FocusTimerService.class);
            queryIntent.setAction(FocusTimerService.ACTION_QUERY_STATUS);
            getActivity().startService(queryIntent);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() != null) {
            getActivity().unregisterReceiver(timerReceiver);
        }
    }

    private void startTimer() {
        int durationMins = 25;
        if (rb50Min.isChecked()) {
            durationMins = 50;
        } else if (rbCustomMin.isChecked()) {
            String customStr = etCustomMinutes.getText().toString().trim();
            try {
                durationMins = Integer.parseInt(customStr);
            } catch (Exception e) {
                durationMins = 15;
            }
            if (durationMins <= 0) durationMins = 15;
        }

        Intent intent = new Intent(getContext(), FocusTimerService.class);
        intent.setAction(FocusTimerService.ACTION_START);
        intent.putExtra(FocusTimerService.EXTRA_DURATION, durationMins);
        if (getActivity() != null) {
            ContextCompat.startForegroundService(requireContext(), intent);
        }

        isTimerRunning = true;
        isTimerPaused = false;
        updateButtonsState();
    }

    private void pauseTimer() {
        Intent intent = new Intent(getContext(), FocusTimerService.class);
        intent.setAction(FocusTimerService.ACTION_PAUSE);
        if (getActivity() != null) {
            getActivity().startService(intent);
        }

        isTimerPaused = true;
        updateButtonsState();
    }

    private void resumeTimer() {
        Intent intent = new Intent(getContext(), FocusTimerService.class);
        intent.setAction(FocusTimerService.ACTION_RESUME);
        if (getActivity() != null) {
            getActivity().startService(intent);
        }

        isTimerPaused = false;
        updateButtonsState();
    }

    private void cancelTimer() {
        Intent intent = new Intent(getContext(), FocusTimerService.class);
        intent.setAction(FocusTimerService.ACTION_CANCEL);
        if (getActivity() != null) {
            getActivity().startService(intent);
        }

        isTimerRunning = false;
        isTimerPaused = false;
        updateButtonsState();
        updateTimerUI(25 * 60 * 1000L);
    }

    private void handleTimerFinished(int durationMins) {
        isTimerRunning = false;
        isTimerPaused = false;
        updateButtonsState();
        updateTimerUI(25 * 60 * 1000L);

        Toast.makeText(getContext(), "Focus Session Completed! +10 Study Points Awarded!", Toast.LENGTH_LONG).show();

        new Thread(() -> {
            if (getContext() != null) {
                FocusSessionEntity session = new FocusSessionEntity(System.currentTimeMillis(), durationMins);
                AppDatabase.getDatabase(getContext()).focusSessionDao().insert(session);
                ScoreCalculator.calculateAndSaveDailyScore(getContext());
            }
        }).start();
    }

    private void updateTimerUI(long timeLeftInMillis) {
        int minutes = (int) (timeLeftInMillis / 1000) / 60;
        int seconds = (int) (timeLeftInMillis / 1000) % 60;
        String timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        tvTimer.setText(timeFormatted);
    }

    private void updateButtonsState() {
        if (!isTimerRunning) {
            btnStart.setVisibility(View.VISIBLE);
            btnPause.setVisibility(View.GONE);
            btnResume.setVisibility(View.GONE);
            btnCancel.setVisibility(View.GONE);
            rgPresets.setVisibility(View.VISIBLE);
            if (rbCustomMin.isChecked()) {
                layoutCustomInput.setVisibility(View.VISIBLE);
            }
        } else {
            rgPresets.setVisibility(View.GONE);
            layoutCustomInput.setVisibility(View.GONE);
            btnStart.setVisibility(View.GONE);
            btnCancel.setVisibility(View.VISIBLE);
            if (isTimerPaused) {
                btnPause.setVisibility(View.GONE);
                btnResume.setVisibility(View.VISIBLE);
            } else {
                btnPause.setVisibility(View.VISIBLE);
                btnResume.setVisibility(View.GONE);
            }
        }
    }
}
