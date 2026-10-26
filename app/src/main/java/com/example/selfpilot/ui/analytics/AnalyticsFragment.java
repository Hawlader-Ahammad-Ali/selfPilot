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

import android.graphics.drawable.Drawable;

public class AnalyticsFragment extends Fragment {

    public static class AppUsageData {
        public String packageName;
        public String appName;
        public Drawable icon;
        public long timeInMinutes;
        public float percentage;
    }

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
        
        // Custom UI modifications
        appUsagePieChart.getLegend().setEnabled(false);
        appUsagePieChart.setDrawEntryLabels(false);
        appUsagePieChart.setHighlightPerTapEnabled(true);
        
        AppUsageMarkerView mv = new AppUsageMarkerView(requireContext(), R.layout.layout_pie_marker);
        mv.setChartView(appUsagePieChart);
        appUsagePieChart.setMarker(mv);
    }

    private int getColorForPackage(String packageName) {
        if ("Others".equals(packageName) || "No Data".equals(packageName)) {
            return Color.parseColor("#9CA3AF");
        }
        int hash = packageName.hashCode();
        String[] palette = {
            "#3B82F6", "#8B5CF6", "#10B981", "#F59E0B", "#EF4444",
            "#06B6D4", "#F43F5E", "#84CC16", "#D946EF", "#14B8A6",
            "#F97316", "#6366F1", "#EC4899", "#EAB308"
        };
        return Color.parseColor(palette[Math.abs(hash) % palette.length]);
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

        Context safeContext = getContext();
        if (safeContext == null) return;

        executorService.execute(() -> {
            try {
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
                Map<String, UsageStats> stats = UsageUtil.getUsageStats(safeContext, startMillis, now);
                long totalScreenMillis = 0;
                List<PieEntry> pieEntries = new ArrayList<>();
                List<Integer> colors = new ArrayList<>();
                
                if (stats != null) {
                    long totalMillisForPie = 0;
                    for (UsageStats s : stats.values()) {
                        totalMillisForPie += s.getTotalTimeInForeground();
                    }

                    // Group by top apps
                    List<Map.Entry<String, UsageStats>> sortedStats = new ArrayList<>(stats.entrySet());
                    sortedStats.sort((e1, e2) -> Long.compare(e2.getValue().getTotalTimeInForeground(), e1.getValue().getTotalTimeInForeground()));

                    int limit = 5;
                    for (int i = 0; i < sortedStats.size() && i < limit; i++) {
                        UsageStats stat = sortedStats.get(i).getValue();
                        long time = stat.getTotalTimeInForeground();
                        if (time > 0) {
                            totalScreenMillis += time;
                            String pkg = sortedStats.get(i).getKey();
                            String appName = pkg;
                            Drawable icon = null;
                            try {
                                ApplicationInfo ai = packageManager.getApplicationInfo(pkg, 0);
                                appName = packageManager.getApplicationLabel(ai).toString();
                                icon = packageManager.getApplicationIcon(ai);
                            } catch (Exception ignored) {}
                            
                            AppUsageData data = new AppUsageData();
                            data.packageName = pkg;
                            data.appName = appName;
                            data.icon = icon;
                            data.timeInMinutes = time / 60000;
                            data.percentage = totalMillisForPie > 0 ? ((float) time / totalMillisForPie) * 100f : 0f;

                            pieEntries.add(new PieEntry((float) (time / 60000), appName, data));
                            colors.add(getColorForPackage(pkg));
                        }
                    }
                    // Add "Others"
                    long otherMillis = 0;
                    for (int i = limit; i < sortedStats.size(); i++) {
                        otherMillis += sortedStats.get(i).getValue().getTotalTimeInForeground();
                    }
                    totalScreenMillis += otherMillis;
                    if (otherMillis > 0) {
                        AppUsageData data = new AppUsageData();
                        data.packageName = "Others";
                        data.appName = "Others";
                        data.icon = null;
                        data.timeInMinutes = otherMillis / 60000;
                        data.percentage = totalMillisForPie > 0 ? ((float) otherMillis / totalMillisForPie) * 100f : 0f;

                        pieEntries.add(new PieEntry((float) (otherMillis / 60000), "Others", data));
                        colors.add(getColorForPackage("Others"));
                    }
                }

                if (pieEntries.isEmpty()) {
                    AppUsageData data = new AppUsageData();
                    data.packageName = "No Data";
                    data.appName = "No Data";
                    data.icon = null;
                    data.timeInMinutes = 0;
                    data.percentage = 0f;
                    pieEntries.add(new PieEntry(1f, "No Data", data));
                    colors.add(getColorForPackage("No Data"));
                }

                // Prepare Data Models
                final int finalFocusSessions = focusSessionsCount;
                final long finalProductiveHours = (productiveMillis / 60000) / 60;
                final long finalScreenHours = (totalScreenMillis / 60000) / 60;

                List<BarEntry> barEntries = new ArrayList<>();
                barEntries.add(new BarEntry(1f, (float) finalProductiveHours));
                barEntries.add(new BarEntry(2f, (float) finalScreenHours));
                BarDataSet barDataSet = new BarDataSet(barEntries, "Hours");
                barDataSet.setColors(Color.parseColor("#10B981"), Color.parseColor("#3B82F6"));
                barDataSet.setValueTextColor(Color.WHITE);
                BarData barData = new BarData(barDataSet);

                PieDataSet pieDataSet = new PieDataSet(pieEntries, "Top Apps");
                pieDataSet.setColors(colors);
                pieDataSet.setValueTextColor(Color.WHITE);
                pieDataSet.setDrawValues(false); // Hide the slice values
                PieData pieData = new PieData(pieDataSet);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            pbAnalyticsLoading.setVisibility(View.GONE);
                            tvFocusSessionsTotal.setText(String.valueOf(finalFocusSessions));
                            tvProductiveTimeAvg.setText(finalProductiveHours + "h");
                            tvScreenTimeAvg.setText(finalScreenHours + "h");

                            if (mainBarChart != null) {
                                mainBarChart.setData(barData);
                                mainBarChart.invalidate();
                            }

                            if (appUsagePieChart != null) {
                                appUsagePieChart.setData(pieData);
                                appUsagePieChart.invalidate();
                            }
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
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
