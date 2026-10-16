package com.example.selfpilot.ui.apps;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoDao;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.util.UsageUtil;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppsFragment extends Fragment {

    private AppListAdapter adapter;
    private AppDatabase db;
    private ExecutorService executorService;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_apps, container, false);
        RecyclerView recyclerView = view.findViewById(R.id.recycler_view_apps);
        
        db = AppDatabase.getDatabase(requireContext());
        executorService = Executors.newSingleThreadExecutor();
        
        adapter = new AppListAdapter(requireContext().getPackageManager(), db);
        recyclerView.setAdapter(adapter);

        loadApps();

        return view;
    }

    private void loadApps() {
        executorService.execute(() -> {
            PackageManager pm = requireContext().getPackageManager();
            List<ApplicationInfo> installedApps = UsageUtil.getInstalledApps(requireContext());
            AppInfoDao dao = db.appInfoDao();

            for (ApplicationInfo appInfo : installedApps) {
                String packageName = appInfo.packageName;
                String appName = appInfo.loadLabel(pm).toString();
                
                AppInfoEntity existing = dao.getAppInfo(packageName);
                if (existing == null) {
                    dao.insert(new AppInfoEntity(packageName, appName, false));
                }
            }

            List<AppInfoEntity> allApps = dao.getAllApps();
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> adapter.setAppList(allApps));
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
