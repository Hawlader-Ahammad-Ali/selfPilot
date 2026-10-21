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

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FocusFragment extends Fragment {

    private TextView tvTimer;
    private TextView tvFocusWarning;
    private RadioGroup rgPresets;
    private RadioButton rb15Min;
    private RadioButton rb30Min;
    private RadioButton rb60Min;
    private RadioButton rb120Min;
    private RadioButton rbCustomMin;
    private LinearLayout layoutCustomInput;
    private EditText etCustomMinutes;

    private Button btnStart;

    private TextView tvFocusSessionsCompleted;
    private TextView tvFocusXpEarned;

    private boolean isTimerRunning = false;
    private ExecutorService executorService;

    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null) {
                if (intent.getAction().equals(FocusTimerService.ACTION_TIMER_TICK)) {
                    long timeLeft = intent.getLongExtra(FocusTimerService.EXTRA_TIME_LEFT, 0);
                    isTimerRunning = intent.getBooleanExtra(FocusTimerService.EXTRA_IS_RUNNING, isTimerRunning);
                    updateTimerUI(timeLeft);
                    updateButtonsState();
                } else if (intent.getAction().equals(FocusTimerService.ACTION_TIMER_FINISHED)) {
                    int xp = intent.getIntExtra(FocusTimerService.EXTRA_XP_EARNED, 0);
                    handleTimerFinished(xp);
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_focus, container, false);

        tvTimer = view.findViewById(R.id.tvTimer);
        tvFocusWarning = view.findViewById(R.id.tvFocusWarning);
        rgPresets = view.findViewById(R.id.rgPresets);
        rb15Min = view.findViewById(R.id.rb15Min);
        rb30Min = view.findViewById(R.id.rb30Min);
        rb60Min = view.findViewById(R.id.rb60Min);
        rb120Min = view.findViewById(R.id.rb120Min);
        rbCustomMin = view.findViewById(R.id.rbCustomMin);
        layoutCustomInput = view.findViewById(R.id.layoutCustomInput);
        etCustomMinutes = view.findViewById(R.id.etCustomMinutes);

        btnStart = view.findViewById(R.id.btnStart);
        tvFocusSessionsCompleted = view.findViewById(R.id.tvFocusSessionsCompleted);
        tvFocusXpEarned = view.findViewById(R.id.tvFocusXpEarned);

        executorService = Executors.newSingleThreadExecutor();

        setupPresetListeners();
        btnStart.setOnClickListener(v -> startTimer());

        return view;
    }

    private void setupPresetListeners() {
        rgPresets.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbCustomMin) {
                layoutCustomInput.setVisibility(View.VISIBLE);
            } else {
                layoutCustomInput.setVisibility(View.GONE);
                int duration = 15;
                if (checkedId == R.id.rb30Min) duration = 30;
                else if (checkedId == R.id.rb60Min) duration = 60;
                else if (checkedId == R.id.rb120Min) duration = 120;
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

        loadHistoryStats();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() != null) {
            getActivity().unregisterReceiver(timerReceiver);
        }
    }

    private void loadHistoryStats() {
        if (getContext() == null) return;
        executorService.execute(() -> {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startOfDay = calendar.getTimeInMillis();

            AppDatabase db = AppDatabase.getDatabase(getContext());
            List<FocusSessionEntity> sessions = db.focusSessionDao().getCompletedSessionsToday(startOfDay);

            int count = 0;
            int totalXp = 0;
            if (sessions != null) {
                count = sessions.size();
                for (FocusSessionEntity s : sessions) {
                    totalXp += s.xpEarned;
                }
            }

            final int finalCount = count;
            final int finalXp = totalXp;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    tvFocusSessionsCompleted.setText(String.valueOf(finalCount));
                    tvFocusXpEarned.setText(finalXp + " XP");
                });
            }
        });
    }

    private void startTimer() {
        int durationMins = 15;
        if (rb30Min.isChecked()) {
            durationMins = 30;
        } else if (rb60Min.isChecked()) {
            durationMins = 60;
        } else if (rb120Min.isChecked()) {
            durationMins = 120;
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
        updateButtonsState();
    }

    private void handleTimerFinished(int xpEarned) {
        isTimerRunning = false;
        updateButtonsState();
        
        int defaultDuration = 15;
        if (rb30Min.isChecked()) defaultDuration = 30;
        else if (rb60Min.isChecked()) defaultDuration = 60;
        else if (rb120Min.isChecked()) defaultDuration = 120;
        updateTimerUI(defaultDuration * 60 * 1000L);

        Toast.makeText(getContext(), "Focus Session Completed! +" + xpEarned + " XP Awarded!", Toast.LENGTH_LONG).show();
        loadHistoryStats();
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
            rgPresets.setVisibility(View.VISIBLE);
            tvFocusWarning.setVisibility(View.GONE);
            if (rbCustomMin.isChecked()) {
                layoutCustomInput.setVisibility(View.VISIBLE);
            }
        } else {
            rgPresets.setVisibility(View.GONE);
            layoutCustomInput.setVisibility(View.GONE);
            btnStart.setVisibility(View.GONE);
            tvFocusWarning.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
