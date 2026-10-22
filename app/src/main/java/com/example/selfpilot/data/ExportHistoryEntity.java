package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "export_history")
public class ExportHistoryEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public long exportDate;
    public String filename;
    public String format;
    public String dateRange;

    public ExportHistoryEntity(long exportDate, String filename, String format, String dateRange) {
        this.exportDate = exportDate;
        this.filename = filename;
        this.format = format;
        this.dateRange = dateRange;
    }
}
