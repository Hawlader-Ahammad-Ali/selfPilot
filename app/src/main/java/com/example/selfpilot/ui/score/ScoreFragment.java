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

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScoreFragment extends Fragment {

    private TextView tvDisciplineScore;
    private TextView tvStudyPoints;
    private TextView tvMotivationalText;
    private ExecutorService executorService;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_score, container, false);
        tvDisciplineScore = view.findViewById(R.id.tvDisciplineScore);
        tvStudyPoints = view.findViewById(R.id.tvStudyPoints);
        tvMotivationalText = view.findViewById(R.id.tvMotivationalText);
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
                    tvDisciplineScore.setText(String.valueOf(score.getDisciplineScore()));
                    tvStudyPoints.setText(String.valueOf(score.getStudyPoints()));
                    
                    String motivation;
                    if (score.getDisciplineScore() >= 90) {
                        motivation = "Excellent discipline! Keep up the great work!";
                    } else if (score.getDisciplineScore() >= 70) {
                        motivation = "Good job! Stay focused.";
                    } else if (score.getDisciplineScore() >= 50) {
                        motivation = "You can do better. Try to reduce distractions.";
                    } else {
                        motivation = "Stay focused! Don't let distractions ruin your day.";
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
