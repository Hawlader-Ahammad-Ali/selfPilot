package com.example.selfpilot.ui.home;

import android.app.usage.UsageStats;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.HonorScoreDao;
import com.example.selfpilot.data.HonorScoreEntity;
import com.example.selfpilot.util.ScoreCalculator;
import com.example.selfpilot.util.UsageUtil;
import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {

    private TextView tvTotalScreenTime;
    private TextView tvDisciplineScoreHome;
    private TextView tvStudyPointsHome;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        tvTotalScreenTime = view.findViewById(R.id.tvTotalScreenTime);
        tvDisciplineScoreHome = view.findViewById(R.id.tvDisciplineScoreHome);
        tvStudyPointsHome = view.findViewById(R.id.tvStudyPointsHome);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDashboard();
    }

    private void updateDashboard() {
        if (getContext() == null) return;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            // 1. Calculate total screen time today
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startOfDay = calendar.getTimeInMillis();
            long now = System.currentTimeMillis();

            Map<String, UsageStats> stats = UsageUtil.getUsageStats(requireContext(), startOfDay, now);
            long totalTime = 0;
            if (stats != null) {
                for (UsageStats stat : stats.values()) {
                    totalTime += stat.getTotalTimeInForeground();
                }
            }
            long totalMinutes = totalTime / 60000;
            long hours = totalMinutes / 60;
            long minutes = totalMinutes % 60;
            String screenTimeStr = hours + " hrs " + minutes + " mins";

            // 2. Call ScoreCalculator.calculateAndSaveDailyScore
            ScoreCalculator.calculateAndSaveDailyScore(requireContext());

            // 3. Fetch today's HonorScoreEntity
            AppDatabase db = AppDatabase.getDatabase(requireContext());
            HonorScoreDao honorScoreDao = db.honorScoreDao();
            HonorScoreEntity todayScore = honorScoreDao.getScoreByDate(startOfDay);

            final String disciplineScoreStr = todayScore != null ? String.valueOf(todayScore.getDisciplineScore()) : "0";
            final String studyPointsStr = todayScore != null ? String.valueOf(todayScore.getStudyPoints()) : "0";

            // 4. Update UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    tvTotalScreenTime.setText(screenTimeStr);
                    tvDisciplineScoreHome.setText(disciplineScoreStr);
                    tvStudyPointsHome.setText(studyPointsStr);
                });
            }
        });
    }
}
