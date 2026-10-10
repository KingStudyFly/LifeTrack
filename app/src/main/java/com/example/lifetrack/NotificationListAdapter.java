package com.example.lifetrack;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时提醒列表 Adapter。
 *
 * 关键点：
 * - ViewHolder 复用：SwitchCompat 的 setOnCheckedChangeListener 必须在 setChecked 前
 *   置 null，否则滚动时复用会触发旧 listener 误回调。
 * - 卡片点击与开关点击分离：开关拦截点击事件，避免点击开关时同时触发卡片点击。
 */
public class NotificationListAdapter extends RecyclerView.Adapter<NotificationListAdapter.VH> {

    public interface Listener {
        /** 卡片点击（编辑） */
        void onItemClick(NotificationItem item);

        /** 开关切换 */
        void onEnabledToggle(NotificationItem item, boolean enabled);
    }

    private final List<NotificationItem> items = new ArrayList<>();
    private final Listener listener;

    public NotificationListAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<NotificationItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_notification, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        NotificationItem item = items.get(position);
        holder.bind(item, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvTime;
        final TextView tvContent;
        final TextView tvAlarmBadge;
        final SwitchCompat switchEnabled;

        VH(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvContent = itemView.findViewById(R.id.tvContent);
            tvAlarmBadge = itemView.findViewById(R.id.tvCalendarBadge);
            switchEnabled = itemView.findViewById(R.id.switchEnabled);
        }

        void bind(NotificationItem item, Listener listener) {
            tvTime.setText(item.getTimeText());
            tvContent.setText(item.getContent());
            // 已开启闹钟的条目显示一个小标识
            tvAlarmBadge.setVisibility(item.isEnabled() ? View.VISIBLE : View.GONE);

            // 先解绑旧 listener，避免复用 ViewHolder 时 setChecked 触发回调
            switchEnabled.setOnCheckedChangeListener(null);
            switchEnabled.setChecked(item.isEnabled());
            switchEnabled.setOnCheckedChangeListener((v, checked) -> {
                if (listener != null) listener.onEnabledToggle(item, checked);
            });

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
            });
        }
    }
}
