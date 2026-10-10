package com.example.lifetrack;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CalendarAdapter extends BaseAdapter {

    private final Context context;
    private final List<DayItem> days = new ArrayList<>();
    private int selectedPosition = -1;
    private int todayPosition = -1;
    private final Set<Integer> diaryDays = new HashSet<>();
    private final Map<Integer, Integer> diaryDayCounts = new HashMap<>();
    private final OnDayClickListener listener;

    // 颜色缓存：在构造时一次性读取，避免 getView 每次都查资源
    private final int colorTextHint;
    private final int colorWhite;
    private final int colorGreenPrimary;
    private final int colorTextPrimary;

    // 预加载 drawable：避免 getView 每次都 inflate 新 Drawable
    private final Drawable dotEventDrawable;
    private final Drawable dotDiaryDrawable;

    public interface OnDayClickListener {
        void onDayClick(int year, int month, int day);
    }

    public static class DayItem {
        public int day;
        public int year;
        public int month;
        public boolean isCurrentMonth;

        public DayItem(int year, int month, int day, boolean isCurrentMonth) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.isCurrentMonth = isCurrentMonth;
        }
    }

    public CalendarAdapter(Context context, OnDayClickListener listener) {
        this.context = context;
        this.listener = listener;
        this.colorTextHint = ContextCompat.getColor(context, R.color.text_hint);
        this.colorWhite = ContextCompat.getColor(context, R.color.white);
        this.colorGreenPrimary = ContextCompat.getColor(context, R.color.green_primary);
        this.colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary);
        this.dotEventDrawable = ContextCompat.getDrawable(context, R.drawable.dot_event).mutate();
        this.dotDiaryDrawable = ContextCompat.getDrawable(context, R.drawable.dot_diary).mutate();
    }

    public void setMonth(int year, int month) {
        days.clear();
        diaryDays.clear();
        diaryDayCounts.clear();
        selectedPosition = -1;
        todayPosition = -1;

        // 使用 Locale.US 仅用于日期计算稳定性；表头与网格均为周一起始（见 buildWeekHeader）
        Calendar cal = Calendar.getInstance(java.util.Locale.US);
        int todayYear = cal.get(Calendar.YEAR);
        int todayMonth = cal.get(Calendar.MONTH);
        int todayDay = cal.get(Calendar.DAY_OF_MONTH);

        cal.set(year, month, 1);
        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        // 周一起始：将星期值换算成 1 号前需要补足的天数（周日补 6 天，周一补 0 天，依次类推）
        int mondayOffset = firstDayOfWeek == Calendar.SUNDAY ? 6 : firstDayOfWeek - Calendar.MONDAY;

        Calendar prevCal = Calendar.getInstance(java.util.Locale.US);
        prevCal.set(year, month, 1);
        prevCal.add(Calendar.MONTH, -1);
        int prevMonthDays = prevCal.getActualMaximum(Calendar.DAY_OF_MONTH);

        for (int i = mondayOffset - 1; i >= 0; i--) {
            int d = prevMonthDays - i;
            int m = month - 1;
            int y = year;
            if (m < 0) { m = 11; y--; }
            days.add(new DayItem(y, m, d, false));
        }

        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int d = 1; d <= daysInMonth; d++) {
            DayItem item = new DayItem(year, month, d, true);
            int pos = days.size();
            if (year == todayYear && month == todayMonth && d == todayDay) {
                todayPosition = pos;
                selectedPosition = pos;
            }
            days.add(item);
        }

        int totalCells = (int) Math.ceil((days.size()) / 7.0) * 7;
        Calendar nextCal = Calendar.getInstance(java.util.Locale.US);
        nextCal.set(year, month, 1);
        nextCal.add(Calendar.MONTH, 1);
        int nextDay = 1;
        while (days.size() < totalCells) {
            int m = month + 1;
            int y = year;
            if (m > 11) { m = 0; y++; }
            days.add(new DayItem(y, m, nextDay++, false));
        }

        notifyDataSetChanged();
    }

    public void setDiaryDays(Set<Integer> daysInMonth) {
        diaryDays.clear();
        diaryDays.addAll(daysInMonth);
        notifyDataSetChanged();
    }

    public void setDiaryDayCounts(Map<Integer, Integer> counts) {
        diaryDayCounts.clear();
        if (counts != null) {
            diaryDayCounts.putAll(counts);
        }
        notifyDataSetChanged();
    }

    public void setSelectedDay(int day) {
        for (int i = 0; i < days.size(); i++) {
            DayItem item = days.get(i);
            if (item.isCurrentMonth && item.day == day) {
                selectedPosition = i;
                notifyDataSetChanged();
                return;
            }
        }
    }

    /**
     * 保存当前选中位置，供宿主 Fragment 在 onSaveInstanceState 中调用。
     * 返回值为 selectedPosition，未选中时为 -1。
     * <p>
     * 注意：本方法仅返回选中位置，不包含 todayPosition、diaryDays、diaryDayCounts 等状态。
     * 宿主在恢复后需重新调用 setMonth + setDiaryDays + setDiaryDayCounts 重建数据。
     */
    public int saveState() {
        return selectedPosition;
    }

    /**
     * 恢复选中位置，供宿主 Fragment 在 onViewStateRestored 中调用。
     * 仅当位置合法且仍属于当月时才恢复，避免恢复到补位的非当月格子。
     */
    public void restoreState(int position) {
        if (position < 0 || position >= days.size()) return;
        DayItem item = days.get(position);
        if (item != null && item.isCurrentMonth) {
            selectedPosition = position;
            notifyDataSetChanged();
        }
    }

    public DayItem getSelectedDay() {
        if (selectedPosition >= 0 && selectedPosition < days.size()) {
            return days.get(selectedPosition);
        }
        return null;
    }

    @Override
    public int getCount() {
        return days.size();
    }

    @Override
    public Object getItem(int position) {
        if (position < 0 || position >= days.size()) return null;
        return days.get(position);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= days.size()) return -1L;
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        Object tag = convertView != null ? convertView.getTag() : null;
        if (!(tag instanceof ViewHolder)) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_calendar_day, parent, false);
            holder = new ViewHolder();
            holder.tvDay = convertView.findViewById(R.id.tvDay);
            holder.bgSelected = convertView.findViewById(R.id.bgSelected);
            holder.bgToday = convertView.findViewById(R.id.bgToday);
            holder.dotDiary = convertView.findViewById(R.id.dotDiary);
            // 仅在创建时设置 listener，避免每次 getView 重复创建 lambda
            convertView.setOnClickListener(v -> {
                Object t = v.getTag();
                ViewHolder h = (t instanceof ViewHolder) ? (ViewHolder) t : null;
                if (h == null || h.position < 0 || h.position >= days.size()) return;
                DayItem it = days.get(h.position);
                if (it == null || !it.isCurrentMonth) return;
                selectedPosition = h.position;
                notifyDataSetChanged();
                if (listener != null) {
                    listener.onDayClick(it.year, it.month, it.day);
                }
            });
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) tag;
        }

        // 更新 holder 当前位置，供 listener 使用
        holder.position = position;

        DayItem item = days.get(position);
        holder.tvDay.setText(String.valueOf(item.day));

        holder.bgSelected.setVisibility(View.GONE);
        holder.bgToday.setVisibility(View.GONE);

        if (!item.isCurrentMonth) {
            holder.tvDay.setTextColor(colorTextHint);
            if (holder.lastTface != 1) {
                holder.tvDay.setTypeface(Typeface.DEFAULT);
                holder.lastTface = 1;
            }
            holder.dotDiary.setVisibility(View.INVISIBLE);
        } else {
            if (position == selectedPosition) {
                holder.bgSelected.setVisibility(View.VISIBLE);
                holder.tvDay.setTextColor(colorWhite);
                if (holder.lastTface != 2) {
                    holder.tvDay.setTypeface(null, Typeface.BOLD);
                    holder.lastTface = 2;
                }
            } else if (position == todayPosition) {
                holder.bgToday.setVisibility(View.VISIBLE);
                holder.tvDay.setTextColor(colorGreenPrimary);
                if (holder.lastTface != 2) {
                    holder.tvDay.setTypeface(null, Typeface.BOLD);
                    holder.lastTface = 2;
                }
            } else {
                holder.tvDay.setTextColor(colorTextPrimary);
                if (holder.lastTface != 3) {
                    holder.tvDay.setTypeface(null, Typeface.NORMAL);
                    holder.lastTface = 3;
                }
            }

            Integer count = diaryDayCounts.get(item.day);
            if (count != null && count > 0) {
                holder.dotDiary.setVisibility(View.VISIBLE);
                float scale = Math.min(count / 5f, 1f);
                holder.dotDiary.setScaleX(0.5f + 0.5f * scale);
                holder.dotDiary.setScaleY(0.5f + 0.5f * scale);
                // 使用预加载 drawable 替代每次 setBackgroundResource
                holder.dotDiary.setBackground(scale >= 0.6f ? dotEventDrawable : dotDiaryDrawable);
            } else {
                holder.dotDiary.setVisibility(View.INVISIBLE);
                holder.dotDiary.setScaleX(1f);
                holder.dotDiary.setScaleY(1f);
            }
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView tvDay;
        View bgSelected;
        View bgToday;
        View dotDiary;
        int position = -1;
        // 缓存上次 typeface 状态，避免每次 getView 都调用 setTypeface
        // 0=未设置(强制刷新) 1=DEFAULT 2=BOLD 3=NORMAL(via null)
        int lastTface = 0;
    }
}
