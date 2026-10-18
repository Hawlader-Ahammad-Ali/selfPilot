package com.example.selfpilot.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "frozen_apps")
public class FrozenAppEntity {
    @PrimaryKey
    @NonNull
    public String packageName;

    public String appName;
    public boolean isFrozen;
    public long frozenAt;
    public long unfrozenAt;
    public String restrictionMode;

    public FrozenAppEntity(@NonNull String packageName, String appName, boolean isFrozen, long frozenAt) {
        this.packageName = packageName;
        this.appName = appName;
        this.isFrozen = isFrozen;
        this.frozenAt = frozenAt;
        this.unfrozenAt = 0;
        this.restrictionMode = "STRICT_LOCK";
    }
}
