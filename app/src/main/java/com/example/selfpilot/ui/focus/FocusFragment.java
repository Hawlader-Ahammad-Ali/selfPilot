package com.example.selfpilot.ui.focus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

import com.example.selfpilot.MainActivity;
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
    private View layoutCustomInput;
    private EditText etCustomMinutes;

    private View btnStart;

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
        setupCustomDurationFormatting();
        btnStart.setOnClickListener(v -> startTimer());

        return view;
    }

    /**
     * Keeps the custom field in a strict HH:MM:SS shape while typing, so the value can never
     * be ambiguous between minutes and hours.
     */
    private void setupCustomDurationFormatting() {
        etCustomMinutes.addTextChangedListener(new TextWatcher() {
            private boolean formatting = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (formatting) return;
                formatting = true;

                String digits = s.toString().replaceAll("[^0-9]", "");
                if (digits.length() > 6) {
                    digits = digits.substring(0, 6);
                }

                StringBuilder out = new StringBuilder();
                for (int i = 0; i < digits.length(); i++) {
                    if (i == 2 || i == 4) out.append(':');
                    out.append(digits.charAt(i));
                }

                etCustomMinutes.setText(out.toString());
                etCustomMinutes.setSelection(out.length());
                formatting = false;
            }
        });
    }

    /** "HH:MM:SS" -> total seconds, or -1 when the value is not a complete timestamp. */
    private static long parseHmsSeconds(String value) {
        if (value == null) return -1L;
        String trimmed = value.trim();
        if (!trimmed.matches("\\d{2}:\\d{2}:\\d{2}")) return -1L;

        String[] parts = trimmed.split(":");
        try {
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            long seconds = Long.parseLong(parts[2]);
            if (minutes > 59 || seconds > 59) return -1L;

            long total = hours * 3600 + minutes * 60 + seconds;
            return total > 0 ? total : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String formatHms(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
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
        }

        // Restore the UI from the service snapshot. Asking the service for its status used to
        // mean starting it, which spawned a stray background service that never stopped.
        isTimerRunning = FocusTimerService.isRunning();
        if (isTimerRunning) {
            updateTimerUI(FocusTimerService.getTimeLeftMs());
        } else {
            updateTimerUI(currentPresetMillis());
        }
        updateButtonsState();

        loadHistoryStats();
    }

    /** Duration of the currently selected preset, in milliseconds. */
    private long currentPresetMillis() {
        if (rb30Min.isChecked()) return 30 * 60 * 1000L;
        if (rb60Min.isChecked()) return 60 * 60 * 1000L;
        if (rb120Min.isChecked()) return 120 * 60 * 1000L;
        if (rbCustomMin.isChecked()) {
            long seconds = parseHmsSeconds(etCustomMinutes.getText().toString());
            if (seconds > 0) return seconds * 1000L;
        }
        return 15 * 60 * 1000L;
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
        long seconds;

        if (rbCustomMin.isChecked()) {
            seconds = parseHmsSeconds(etCustomMinutes.getText().toString());
            if (seconds <= 0) {
                Toast.makeText(getContext(),
                        "Enter the duration as HH:MM:SS (for example 00:45:00)",
                        Toast.LENGTH_LONG).show();
                return;
            }
            // Clamp so an accidental huge value cannot create an endless session.
            if (seconds > 12 * 3600L) seconds = 12 * 3600L;
        } else {
            seconds = currentPresetMillis() / 1000L;
        }

        Intent intent = new Intent(getContext(), FocusTimerService.class);
        intent.setAction(FocusTimerService.ACTION_START);
        intent.putExtra(FocusTimerService.EXTRA_DURATION_SECONDS, seconds);
        if (getActivity() != null) {
            ContextCompat.startForegroundService(requireContext(), intent);
        }

        isTimerRunning = true;
        updateButtonsState();
        updateTimerUI(seconds * 1000L);
        notifyFocusLockChanged();
    }

    /** Tells the host activity that the session state changed (shows/removes the lock screen). */
    private void notifyFocusLockChanged() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onFocusSessionStateChanged();
        }
    }

    private void handleTimerFinished(int xpEarned) {
        isTimerRunning = false;
        updateButtonsState();
        notifyFocusLockChanged();

        updateTimerUI(currentPresetMillis());

        Toast.makeText(getContext(), "Focus Session Completed! +" + xpEarned + " XP Awarded!", Toast.LENGTH_LONG).show();
        loadHistoryStats();
    }

    private void updateTimerUI(long timeLeftInMillis) {
        tvTimer.setText(formatHms(timeLeftInMillis));
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
