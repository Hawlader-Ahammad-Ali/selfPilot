package com.example.selfpilot.ui.analytics;

import android.app.usage.UsageStats;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.util.UsageUtil;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnalyticsFragment extends Fragment {

    private ChipGroup chipGroupAnalyticsDate;
    private TextView tvDateRangeInfo;
    private ProgressBar pbAnalyticsLoading;
    private BarChart mainBarChart;
    private PieChart appUsagePieChart;
    
    private TextView tvFocusSessionsTotal;
    private TextView tvProductiveTimeAvg;
    private TextView tvScreenTimeAvg;
    
    private ExecutorService executorService;
    private AppDatabase db;
    private PackageManager packageManager;

    private int daysToQuery = 7;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_analytics, container, false);
        
        chipGroupAnalyticsDate = view.findViewById(R.id.chipGroupAnalyticsDate);
        tvDateRangeInfo = view.findViewById(R.id.tvDateRangeInfo);
        pbAnalyticsLoading = view.findViewById(R.id.pbAnalyticsLoading);
        mainBarChart = view.findViewById(R.id.mainBarChart);
        appUsagePieChart = view.findViewById(R.id.appUsagePieChart);
        
        tvFocusSessionsTotal = view.findViewById(R.id.tvFocusSessionsTotal);
        tvProductiveTimeAvg = view.findViewById(R.id.tvProductiveTimeAvg);
        tvScreenTimeAvg = view.findViewById(R.id.tvScreenTimeAvg);

        db = AppDatabase.getDatabase(requireContext());
        packageManager = requireContext().getPackageManager();
        executorService = Executors.newSingleThreadExecutor();

        setupCharts();
        setupListeners();
        loadAnalyticsData();

        return view;
    }

    private void setupCharts() {
        mainBarChart.getDescription().setEnabled(false);
        mainBarChart.setDrawGridBackground(false);
        mainBarChart.getAxisRight().setEnabled(false);
        XAxis xAxis = mainBarChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.WHITE);
        mainBarChart.getAxisLeft().setTextColor(Color.WHITE);
        mainBarChart.getLegend().setTextColor(Color.WHITE);

        appUsagePieChart.getDescription().setEnabled(false);
        appUsagePieChart.setDrawHoleEnabled(true);
        appUsagePieChart.setHoleColor(Color.TRANSPARENT);
        appUsagePieChart.getLegend().setTextColor(Color.WHITE);
    }

    private void setupListeners() {
        chipGroupAnalyticsDate.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) {
                int id = checkedIds.get(0);
                if (id == R.id.chip7Days) {
                    daysToQuery = 7;
                } else if (id == R.id.chip30Days) {
                    daysToQuery = 30;
                }
                loadAnalyticsData();
            }
        });
    }

    private void loadAnalyticsData() {
        pbAnalyticsLoading.setVisibility(View.VISIBLE);
        tvDateRangeInfo.setText("Showing data for past " + daysToQuery + " days.");

        executorService.execute(() -> {
            long now = System.currentTimeMillis();
            long startMillis = now - (daysToQuery * 86400000L);

            // 1. Fetch Focus Sessions
            List<FocusSessionEntity> sessions = db.focusSessionDao().getSessionsByDate(startMillis);
            int focusSessionsCount = 0;
            long productiveMillis = 0;
            if (sessions != null) {
                for (FocusSessionEntity s : sessions) {
                    if ("COMPLETED".equals(s.status)) {
                        focusSessionsCount++;
                        productiveMillis += (s.durationMinutes * 60 * 1000L);
                    }
                }
            }

            // 2. Fetch Screen Time via UsageStats
            Map<String, UsageStats> stats = UsageUtil.getUsageStats(requireContext(), startMillis, now);
            long totalScreenMillis = 0;
            List<PieEntry> pieEntries = new ArrayList<>();
            
            if (stats != null) {
                // Group by top apps
                List<Map.Entry<String, UsageStats>> sortedStats = new ArrayList<>(stats.entrySet());
                sortedStats.sort((e1, e2) -> Long.compare(e2.getValue().getTotalTimeInForeground(), e1.getValue().getTotalTimeInForeground()));

                int limit = 5;
                for (int i = 0; i < sortedStats.size() && i < limit; i++) {
                    UsageStats stat = sortedStats.get(i).getValue();
                    long time = stat.getTotalTimeInForeground();
                    if (time > 0) {
                        totalScreenMillis += time;
                        String appName = sortedStats.get(i).getKey();
                        try {
                            ApplicationInfo ai = packageManager.getApplicationInfo(appName, 0);
                            appName = packageManager.getApplicationLabel(ai).toString();
                        } catch (Exception ignored) {}
                        pieEntries.add(new PieEntry((float) (time / 60000), appName));
                    }
                }
                // Add "Others"
                long otherMillis = 0;
                for (int i = limit; i < sortedStats.size(); i++) {
                    otherMillis += sortedStats.get(i).getValue().getTotalTimeInForeground();
                }
                totalScreenMillis += otherMillis;
                if (otherMillis > 0) {
                    pieEntries.add(new PieEntry((float) (otherMillis / 60000), "Others"));
                }
            }

            // Prepare Data Models
            final int finalFocusSessions = focusSessionsCount;
            final long finalProductiveHours = (productiveMillis / 60000) / 60;
            final long finalScreenHours = (totalScreenMillis / 60000) / 60;

            List<BarEntry> barEntries = new ArrayList<>();
            barEntries.add(new BarEntry(1f, finalProductiveHours));
            barEntries.add(new BarEntry(2f, finalScreenHours));
            BarDataSet barDataSet = new BarDataSet(barEntries, "Hours");
            barDataSet.setColors(Color.parseColor("#10B981"), Color.parseColor("#3B82F6"));
            barDataSet.setValueTextColor(Color.WHITE);
            BarData barData = new BarData(barDataSet);

            PieDataSet pieDataSet = new PieDataSet(pieEntries, "Top Apps");
            pieDataSet.setColors(new int[]{Color.parseColor("#3B82F6"), Color.parseColor("#8B5CF6"), Color.parseColor("#10B981"), Color.parseColor("#F59E0B"), Color.parseColor("#EF4444")});
            pieDataSet.setValueTextColor(Color.WHITE);
            PieData pieData = new PieData(pieDataSet);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    pbAnalyticsLoading.setVisibility(View.GONE);
                    tvFocusSessionsTotal.setText(String.valueOf(finalFocusSessions));
                    tvProductiveTimeAvg.setText(finalProductiveHours + "h");
                    tvScreenTimeAvg.setText(finalScreenHours + "h");

                    mainBarChart.setData(barData);
                    mainBarChart.invalidate();

                    appUsagePieChart.setData(pieData);
                    appUsagePieChart.invalidate();
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
