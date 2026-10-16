package com.example.selfpilot.ui.score;

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
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScoreFragment extends Fragment {

    private TextView tvHonorLevel;
    private TextView tvStreak;
    private TextView tvDisciplineScore;
    private TextView tvStudyPoints;
    private TextView tvMotivationalText;
    private LinearProgressIndicator progressDiscipline;
    private ExecutorService executorService;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_score, container, false);
        tvHonorLevel = view.findViewById(R.id.tvHonorLevel);
        tvStreak = view.findViewById(R.id.tvStreak);
        tvDisciplineScore = view.findViewById(R.id.tvDisciplineScore);
        tvStudyPoints = view.findViewById(R.id.tvStudyPoints);
        tvMotivationalText = view.findViewById(R.id.tvMotivationalText);
        progressDiscipline = view.findViewById(R.id.progressDiscipline);
        executorService = Executors.newSingleThreadExecutor();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadScoreData();
    }

    private void loadScoreData() {
        if (getContext() == null) return;
        
        executorService.execute(() -> {
            ScoreCalculator.calculateAndSaveDailyScore(getContext());

            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startOfDay = calendar.getTimeInMillis();

            AppDatabase db = AppDatabase.getDatabase(getContext());
            HonorScoreDao dao = db.honorScoreDao();
            HonorScoreEntity score = dao.getScoreByDate(startOfDay);

            if (getActivity() != null && score != null) {
                getActivity().runOnUiThread(() -> {
                    if (tvHonorLevel != null) {
                        tvHonorLevel.setText(score.getHonorLevel());
                    }
                    if (tvStreak != null) {
                        tvStreak.setText(score.getStreak() + " Day" + (score.getStreak() == 1 ? "" : "s"));
                    }
                    tvDisciplineScore.setText(String.valueOf(score.getDisciplineScore()));
                    tvStudyPoints.setText(score.getStudyPoints() + " pts");
                    if (progressDiscipline != null) {
                        progressDiscipline.setProgress(Math.min(100, Math.max(0, score.getDisciplineScore())));
                    }
                    
                    String motivation;
                    if (score.getDisciplineScore() >= 90) {
                        motivation = "Master level discipline! Keep up the brilliant work!";
                    } else if (score.getDisciplineScore() >= 70) {
                        motivation = "Great consistency! You're staying focused.";
                    } else if (score.getDisciplineScore() >= 50) {
                        motivation = "Fair discipline. Try minimizing distracting apps.";
                    } else {
                        motivation = "Refocus your day! Limit distractions to boost your score.";
                    }
                    tvMotivationalText.setText(motivation);
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
