package com.example.selfpilot.ui.analytics;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.selfpilot.R;
import com.example.selfpilot.util.SystemBarInsets;
import com.example.selfpilot.util.UsageUtil;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hour-by-hour screen time for today, the same shape the phone's own screen-time screen uses.
 * Numbers come from the shared foreground timeline, so the bars always add up to the total
 * shown on the Home card.
 */
public class ScreenTimeActivity extends AppCompatActivity {

    private static final String TAG = "SELF_PILOT_SCREENTIME";

    private TextView tvTotal;
    private TextView tvSubtitle;
    private TextView tvPeak;
    private TextView tvActiveHours;
    private BarChart chart;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, ScreenTimeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_time);
        SystemBarInsets.apply(findViewById(R.id.screenTimeRoot));

        tvTotal = findViewById(R.id.tvScreenTotal);
        tvSubtitle = findViewById(R.id.tvScreenSubtitle);
        tvPeak = findViewById(R.id.tvScreenPeak);
        tvActiveHours = findViewById(R.id.tvScreenActiveHours);
        chart = findViewById(R.id.chartHourly);

        ImageView back = findViewById(R.id.btnScreenTimeBack);
        back.setOnClickListener(v -> finish());

        setupChart();
        load();
    }

    private void setupChart() {
        chart.setDrawGridBackground(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setScaleEnabled(false);
        chart.setPinchZoom(false);
        chart.setNoDataText("No usage recorded yet today");
        chart.setNoDataTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        chart.setExtraBottomOffset(6f);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setLabelCount(8, false);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        xAxis.setTextSize(10f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int hour = (int) Math.floor(value);
                if (hour < 0 || hour > 23) return "";
                return String.format(Locale.getDefault(), "%02d:00", hour);
            }
        });

        chart.getAxisRight().setEnabled(false);

        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setDrawGridLines(true);
        chart.getAxisLeft().setGridColor(ContextCompat.getColor(this, R.color.card_border));
        chart.getAxisLeft().setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        chart.getAxisLeft().setTextSize(10f);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return ((int) value) + "m";
            }
        });
    }

    private void load() {
        executor.execute(() -> {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startOfDay = calendar.getTimeInMillis();
            long now = System.currentTimeMillis();

            long[] hourly = UsageUtil.getHourlyUsage(getApplicationContext(), startOfDay, now);

            long total = 0L;
            int activeHours = 0;
            int peakHour = -1;
            long peakMillis = 0L;
            for (int h = 0; h < hourly.length; h++) {
                total += hourly[h];
                if (hourly[h] > 60_000L) activeHours++;
                if (hourly[h] > peakMillis) {
                    peakMillis = hourly[h];
                    peakHour = h;
                }
            }

            final long totalMillis = total;
            final int active = activeHours;
            final int peak = peakHour;
            final long peakValue = peakMillis;
            final long[] data = hourly;

            runOnUiThread(() -> bind(data, totalMillis, active, peak, peakValue));
        });
    }

    private void bind(long[] hourly, long totalMillis, int activeHours, int peakHour, long peakMillis) {
        if (isFinishing()) return;

        long minutes = totalMillis / 60000L;
        tvTotal.setText((minutes / 60) + "h " + (minutes % 60) + "m");

        Calendar calendar = Calendar.getInstance();
        String date = String.format(Locale.getDefault(), "%d %s",
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault()));
        tvSubtitle.setText("Today, " + date + " • hour by hour");

        if (peakHour < 0 || peakMillis < 60_000L) {
            tvPeak.setText("--");
        } else {
            long peakMinutes = peakMillis / 60000L;
            tvPeak.setText(String.format(Locale.getDefault(), "%02d:00 • %dm", peakHour, peakMinutes));
        }
        tvActiveHours.setText(String.valueOf(activeHours));

        List<BarEntry> entries = new ArrayList<>();
        for (int h = 0; h < hourly.length; h++) {
            entries.add(new BarEntry(h, hourly[h] / 60000f));
        }

        BarDataSet set = new BarDataSet(entries, "Minutes");
        set.setColor(ContextCompat.getColor(this, R.color.purple_accent));
        set.setDrawValues(false);
        set.setHighLightColor(ContextCompat.getColor(this, R.color.electric_blue));

        BarData barData = new BarData(set);
        barData.setBarWidth(0.62f);

        chart.setData(barData);
        chart.setFitBars(true);
        chart.animateY(450);
        chart.invalidate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
