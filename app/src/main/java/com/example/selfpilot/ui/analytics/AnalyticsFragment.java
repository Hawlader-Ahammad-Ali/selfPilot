package com.example.selfpilot.ui.analytics;

import android.app.usage.UsageStats;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.data.GoalEntity;
import com.example.selfpilot.util.ExportManager;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnalyticsFragment extends Fragment {

    public static class AppUsageData {
        public String packageName;
        public String appName;
        public Drawable icon;
        public long timeInMinutes;
        public float percentage;
    }

    private static final long REFRESH_THROTTLE_MS = 3000L;

    private ChipGroup chipGroupAnalyticsDate;
    private TextView tvDateRangeInfo;
    private ProgressBar pbAnalyticsLoading;
    private BarChart mainBarChart;
    private PieChart appUsagePieChart;
    private View btnExportCsv;

    private TextView tvFocusSessionsTotal;
    private TextView tvExamSessionsTotal;
    private TextView tvProductiveTimeAvg;
    private TextView tvScreenTimeAvg;
    private TextView tvHonorScoreAvg;
    private TextView tvGoalAchievementAvg;

    private ExecutorService executorService;
    private AppDatabase db;
    private PackageManager packageManager;

    private int daysToQuery = 7;
    private long lastLoadAt = 0L;

    private ActivityResultLauncher<String> exportLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Registered before the fragment reaches STARTED, which the API requires.
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                this::handleExportUri);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_analytics, container, false);

        chipGroupAnalyticsDate = view.findViewById(R.id.chipGroupAnalyticsDate);
        tvDateRangeInfo = view.findViewById(R.id.tvDateRangeInfo);
        pbAnalyticsLoading = view.findViewById(R.id.pbAnalyticsLoading);
        mainBarChart = view.findViewById(R.id.mainBarChart);
        appUsagePieChart = view.findViewById(R.id.appUsagePieChart);
        btnExportCsv = view.findViewById(R.id.btnExportCsv);

        tvFocusSessionsTotal = view.findViewById(R.id.tvFocusSessionsTotal);
        tvExamSessionsTotal = view.findViewById(R.id.tvExamSessionsTotal);
        tvProductiveTimeAvg = view.findViewById(R.id.tvProductiveTimeAvg);
        tvScreenTimeAvg = view.findViewById(R.id.tvScreenTimeAvg);
        tvHonorScoreAvg = view.findViewById(R.id.tvHonorScoreAvg);
        tvGoalAchievementAvg = view.findViewById(R.id.tvGoalAchievementAvg);

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
        mainBarChart.setFitBars(true);
        mainBarChart.setDrawValueAboveBar(true);

        XAxis xAxis = mainBarChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.WHITE);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        xAxis.setLabelCount(2);
        xAxis.setAxisMinimum(0.5f);
        xAxis.setAxisMaximum(2.5f);
        // Without this the axis showed bare "1" and "2" with no clue which bar was which.
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                if (value == 1f) return "Productive";
                if (value == 2f) return "Screen";
                return "";
            }
        });

        mainBarChart.getAxisLeft().setTextColor(Color.WHITE);
        mainBarChart.getLegend().setTextColor(Color.WHITE);
        mainBarChart.getLegend().setEnabled(false);

        appUsagePieChart.getDescription().setEnabled(false);
        appUsagePieChart.setDrawHoleEnabled(true);
        appUsagePieChart.setHoleColor(Color.TRANSPARENT);
        appUsagePieChart.setHoleRadius(52f);
        appUsagePieChart.setTransparentCircleRadius(56f);
        appUsagePieChart.setCenterText("Top apps");
        appUsagePieChart.setCenterTextColor(Color.WHITE);
        appUsagePieChart.setCenterTextSize(13f);

        appUsagePieChart.getLegend().setEnabled(false);
        appUsagePieChart.setDrawEntryLabels(false);
        appUsagePieChart.setHighlightPerTapEnabled(true);
        appUsagePieChart.setUsePercentValues(false);

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
                if (id == R.id.chip30Days) {
                    daysToQuery = 30;
                } else if (id == R.id.chip7Days) {
                    daysToQuery = 7;
                }
                loadAnalyticsData();
            }
        });

        if (btnExportCsv != null) {
            btnExportCsv.setOnClickListener(v -> startExport());
        }
    }

    // ------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------

    /**
     * The Export button existed in the layout but was never bound, so tapping it did nothing
     * and ExportManager had no caller at all.
     */
    private void startExport() {
        if (exportLauncher == null) {
            Toast.makeText(requireContext(), "Export is not available right now.", Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        try {
            exportLauncher.launch("selfpilot-export-" + stamp + ".json");
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "No file picker available on this device.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleExportUri(Uri uri) {
        if (uri == null || getContext() == null) return;

        final Context appContext = requireContext().getApplicationContext();
        Toast.makeText(requireContext(), "Exporting data...", Toast.LENGTH_SHORT).show();

        ExportManager.exportDataToUri(appContext, uri, daysToQuery, new ExportManager.ExportCallback() {
            @Override
            public void onSuccess(String msg) {
                showToast("Export complete.");
            }

            @Override
            public void onError(String err) {
                showToast("Export failed: " + err);
            }
        });
    }

    private void showToast(final String message) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (getActivity() != null) {
                Toast.makeText(getActivity(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------------------------

    @Override
    public void onResume() {
        super.onResume();
        // Coming back to the tab should show fresh numbers, but the first onResume right after
        // onCreateView must not trigger a second query.
        if (System.currentTimeMillis() - lastLoadAt > REFRESH_THROTTLE_MS) {
            loadAnalyticsData();
        }
    }

    private void loadAnalyticsData() {
        final Context safeContext = getContext();
        if (safeContext == null) return;

        lastLoadAt = System.currentTimeMillis();

        // The executor is shut down in onDestroyView; a late chip change used to throw
        // RejectedExecutionException here.
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        pbAnalyticsLoading.setVisibility(View.VISIBLE);
        tvDateRangeInfo.setText("Showing data for past " + daysToQuery + " days.");

        final int days = daysToQuery;

        executorService.execute(() -> {
            long now = System.currentTimeMillis();
            long startMillis = now - (days * 86400000L);

            int focusSessionsCount = 0;
            long productiveMillis = 0;
            int examSessionsCount = 0;
            int honorScoreAvg = 0;
            int goalAchievedPercent = 0;
            long totalScreenMillis = 0;
            final List<PieEntry> pieEntries = new ArrayList<>();
            final List<Integer> colors = new ArrayList<>();

            try {
                // 1. Focus sessions
                List<FocusSessionEntity> sessions = db.focusSessionDao().getSessionsByDate(startMillis);
                if (sessions != null) {
                    for (FocusSessionEntity s : sessions) {
                        if ("COMPLETED".equals(s.status)) {
                            focusSessionsCount++;
                            productiveMillis += (s.durationMinutes * 60 * 1000L);
                        }
                    }
                }

                // 2. Exam sessions
                examSessionsCount = db.examProfileDao().getExamCountBetween(startMillis, now);

                // 3. Honor score average
                honorScoreAvg = Math.round(db.honorScoreDao().getAverageDisciplineScoreSince(startMillis));

                // 4. Goal achievement
                List<GoalEntity> goals = db.goalDao().getRecentGoals();
                if (goals != null) {
                    int goalDays = 0;
                    int achievedDays = 0;
                    for (GoalEntity g : goals) {
                        if (g == null || g.date < startMillis) continue;
                        if (g.targetProductiveMins <= 0 && g.targetFocusSessions <= 0) continue;
                        goalDays++;
                        boolean productiveOk = g.targetProductiveMins <= 0
                                || g.actualProductiveMins >= g.targetProductiveMins;
                        boolean sessionsOk = g.targetFocusSessions <= 0
                                || g.actualFocusSessions >= g.targetFocusSessions;
                        if (productiveOk && sessionsOk) achievedDays++;
                    }
                    if (goalDays > 0) {
                        goalAchievedPercent = Math.round(achievedDays * 100f / goalDays);
                    }
                }

                // 5. Screen time and app breakdown
                Map<String, UsageStats> stats = UsageUtil.getUsageStats(safeContext, startMillis, now);
                if (stats != null && !stats.isEmpty()) {
                    long totalMillisForPie = 0;
                    for (UsageStats s : stats.values()) {
                        totalMillisForPie += s.getTotalTimeInForeground();
                    }

                    List<Map.Entry<String, UsageStats>> sortedStats = new ArrayList<>(stats.entrySet());
                    sortedStats.sort((e1, e2) -> Long.compare(
                            e2.getValue().getTotalTimeInForeground(),
                            e1.getValue().getTotalTimeInForeground()));

                    int limit = 5;
                    for (int i = 0; i < sortedStats.size() && i < limit; i++) {
                        UsageStats stat = sortedStats.get(i).getValue();
                        long time = stat.getTotalTimeInForeground();
                        if (time <= 0) continue;

                        totalScreenMillis += time;
                        String pkg = sortedStats.get(i).getKey();
                        String appName = pkg;
                        Drawable icon = null;
                        try {
                            ApplicationInfo ai = packageManager.getApplicationInfo(pkg, 0);
                            appName = packageManager.getApplicationLabel(ai).toString();
                            icon = packageManager.getApplicationIcon(ai);
                        } catch (Exception ignored) {
                        }

                        AppUsageData data = new AppUsageData();
                        data.packageName = pkg;
                        data.appName = appName;
                        data.icon = icon;
                        data.timeInMinutes = time / 60000L;
                        data.percentage = totalMillisForPie > 0
                                ? ((float) time / totalMillisForPie) * 100f : 0f;

                        // Minutes as a float: whole-minute rounding used to collapse short
                        // sessions to zero and produced an empty pie chart.
                        pieEntries.add(new PieEntry(time / 60000f, appName, data));
                        colors.add(getColorForPackage(pkg));
                    }

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
                        data.timeInMinutes = otherMillis / 60000L;
                        data.percentage = totalMillisForPie > 0
                                ? ((float) otherMillis / totalMillisForPie) * 100f : 0f;

                        pieEntries.add(new PieEntry(otherMillis / 60000f, "Others", data));
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
            } catch (Exception e) {
                android.util.Log.e("AnalyticsFragment", "Failed to build analytics data", e);
            }

            final int finalFocusSessions = focusSessionsCount;
            final int finalExamSessions = examSessionsCount;
            final int finalHonorAvg = honorScoreAvg;
            final int finalGoalPercent = goalAchievedPercent;
            final long finalProductiveMillis = productiveMillis;
            // Same definition as Home: one non-overlapping app timeline, so the two screens
            // can never disagree about how long the phone was used.
            long timelineMillis = UsageUtil.getForegroundMillis(safeContext, startMillis, now);
            if (timelineMillis > 0) totalScreenMillis = timelineMillis;

            final long finalScreenMillis = totalScreenMillis;

            final List<BarEntry> barEntries = new ArrayList<>();
            barEntries.add(new BarEntry(1f, finalProductiveMillis / 3600000f));
            barEntries.add(new BarEntry(2f, finalScreenMillis / 3600000f));

            final BarDataSet barDataSet = new BarDataSet(barEntries, "Hours");
            barDataSet.setColors(Color.parseColor("#10B981"), Color.parseColor("#3B82F6"));
            barDataSet.setValueTextColor(Color.WHITE);
            barDataSet.setValueTextSize(11f);
            barDataSet.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return String.format(Locale.US, "%.1fh", value);
                }
            });

            final BarData barData = new BarData(barDataSet);
            barData.setBarWidth(0.5f);

            final PieDataSet pieDataSet = new PieDataSet(pieEntries, "Top Apps");
            pieDataSet.setColors(colors);
            pieDataSet.setValueTextColor(Color.WHITE);
            pieDataSet.setDrawValues(false);
            final PieData pieData = new PieData(pieDataSet);

            if (getActivity() == null) return;

            getActivity().runOnUiThread(() -> {
                if (pbAnalyticsLoading != null) pbAnalyticsLoading.setVisibility(View.GONE);

                if (tvFocusSessionsTotal != null) {
                    tvFocusSessionsTotal.setText(String.valueOf(finalFocusSessions));
                }
                if (tvExamSessionsTotal != null) {
                    tvExamSessionsTotal.setText(String.valueOf(finalExamSessions));
                }
                if (tvProductiveTimeAvg != null) {
                    tvProductiveTimeAvg.setText(formatDuration(finalProductiveMillis));
                }
                if (tvScreenTimeAvg != null) {
                    tvScreenTimeAvg.setText(formatDuration(finalScreenMillis));
                }
                if (tvHonorScoreAvg != null) {
                    tvHonorScoreAvg.setText(String.valueOf(finalHonorAvg));
                }
                if (tvGoalAchievementAvg != null) {
                    tvGoalAchievementAvg.setText(finalGoalPercent + "%");
                }

                try {
                    if (mainBarChart != null) {
                        mainBarChart.setData(barData);
                        mainBarChart.invalidate();
                    }
                    if (appUsagePieChart != null) {
                        appUsagePieChart.setData(pieData);
                        appUsagePieChart.invalidate();
                    }
                } catch (Exception e) {
                    android.util.Log.e("AnalyticsFragment", "Failed to render charts", e);
                }
            });
        });
    }

    /**
     * Whole-hour truncation made 45 minutes render as "0h".
     */
    private static String formatDuration(long millis) {
        long totalMinutes = millis / 60000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours <= 0) return minutes + "m";
        return hours + "h " + minutes + "m";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
