package com.example.selfpilot.ui.analytics;

import android.content.Context;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.selfpilot.R;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

import java.util.Locale;

public class AppUsageMarkerView extends MarkerView {
    private final TextView tvName;
    private final TextView tvTime;
    private final ImageView ivIcon;

    public AppUsageMarkerView(Context context, int layoutResource) {
        super(context, layoutResource);
        tvName = findViewById(R.id.markerAppName);
        tvTime = findViewById(R.id.markerTime);
        ivIcon = findViewById(R.id.markerIcon);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        if (e.getData() instanceof AnalyticsFragment.AppUsageData) {
            AnalyticsFragment.AppUsageData data = (AnalyticsFragment.AppUsageData) e.getData();
            tvName.setText(data.appName);
            
            long h = data.timeInMinutes / 60;
            long m = data.timeInMinutes % 60;
            String timeStr = (h > 0 ? h + "h " : "") + m + "m";
            tvTime.setText(String.format(Locale.getDefault(), "%s (%.1f%%)", timeStr, data.percentage));
            
            if (data.icon != null) {
                ivIcon.setImageDrawable(data.icon);
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
        }
        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight());
    }
}
