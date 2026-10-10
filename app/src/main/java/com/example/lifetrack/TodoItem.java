package com.example.lifetrack;

public class TodoItem {
    public final long id;
    public final String date;
    public final String time;
    public final String title;
    public final String description;
    public final boolean completed;
    public final String source;

    public TodoItem(long id, String date, String time, String title, String description,
                    boolean completed, String source) {
        this.id = id;
        this.date = date;
        this.time = time;
        this.title = title;
        this.description = description;
        this.completed = completed;
        this.source = source;
    }
}
