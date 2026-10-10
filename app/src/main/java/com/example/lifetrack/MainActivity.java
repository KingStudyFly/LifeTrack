package com.example.lifetrack;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    /** 桌面小组件等外部入口指定初始 Tab */
    public static final String EXTRA_TAB = "extra_tab";
    public static final int TAB_RECORD = 0;
    public static final int TAB_SCHEDULE = 1;

    private RecordFragment recordFragment;
    private ScheduleFragment scheduleFragment;
    private TrackFragment trackFragment;
    private DiaryFragment diaryFragment;
    private InsightFragment insightFragment;
    private ProfileFragment profileFragment;
    private Fragment activeFragment;

    // 底部导航项（自定义 6 项，BottomNavigationView 最多支持 5 项）
    private static final int[] NAV_ITEM_IDS = {
            R.id.nav_record, R.id.nav_schedule, R.id.nav_track,
            R.id.nav_diary, R.id.nav_insight, R.id.nav_profile
    };
    private static final int[] NAV_ICON_IDS = {
            R.id.nav_icon_record, R.id.nav_icon_schedule, R.id.nav_icon_track,
            R.id.nav_icon_diary, R.id.nav_icon_insight, R.id.nav_icon_profile
    };
    private static final int[] NAV_LABEL_IDS = {
            R.id.nav_label_record, R.id.nav_label_schedule, R.id.nav_label_track,
            R.id.nav_label_diary, R.id.nav_label_insight, R.id.nav_label_profile
    };

    // 顶部模式指示器 6 个圆点
    private static final int[] DOT_IDS = {
            R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4, R.id.dot5
    };
    private int activeDotIndex = 0;

    private volatile AnalyticsDao analyticsDao;
    private long foregroundStartTimeMs = 0;

    @Override
    protected void onResume() {
        super.onResume();
        // analyticsDao 改为 onCreate 异步初始化，避免主线程构造数据库；此处仅提交异步任务并判空
        foregroundStartTimeMs = System.currentTimeMillis();

        final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        AppExecutors.io().execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (analyticsDao != null) {
                analyticsDao.incrementOpenCount(today, DiaryDbHelper.COLUMN_DU_APP_OPENS);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (analyticsDao != null && foregroundStartTimeMs > 0) {
            saveCurrentForegroundTime(null);
            foregroundStartTimeMs = 0;
        }
    }

    /**
     * 将本次前台时长写入数据库，完成后执行 afterWrite 回调。
     * afterWrite 在 IO 线程执行，调用方应确保回调内部不直接操作 UI。
     * afterWrite 为 null 时表示无需后续操作。
     */
    private void saveCurrentForegroundTime(Runnable afterWrite) {
        if (analyticsDao == null || foregroundStartTimeMs <= 0) {
            if (afterWrite != null) afterWrite.run();
            return;
        }
        long elapsedSeconds = (System.currentTimeMillis() - foregroundStartTimeMs) / 1000;
        if (elapsedSeconds > 0) {
            final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            AppExecutors.io().execute(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (analyticsDao != null) {
                    analyticsDao.addForegroundSeconds(today, elapsedSeconds);
                }
                if (afterWrite != null) afterWrite.run();
            });
            foregroundStartTimeMs = System.currentTimeMillis();
        } else {
            if (afterWrite != null) afterWrite.run();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View rootView = findViewById(R.id.main);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        // 异步初始化 AnalyticsDao 避免主线程构造数据库，使用 applicationContext 防止持有 Activity Context
        AppExecutors.io().execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            analyticsDao = new AnalyticsDao(getApplicationContext());
        });

        if (savedInstanceState == null) {
            recordFragment = new RecordFragment();
            scheduleFragment = new ScheduleFragment();
            trackFragment = new TrackFragment();
            diaryFragment = new DiaryFragment();
            insightFragment = new InsightFragment();
            profileFragment = new ProfileFragment();
            activeFragment = recordFragment;

            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragmentContainer, profileFragment, "profile")
                    .hide(profileFragment)
                    .add(R.id.fragmentContainer, insightFragment, "insight")
                    .hide(insightFragment)
                    .add(R.id.fragmentContainer, diaryFragment, "diary")
                    .hide(diaryFragment)
                    .add(R.id.fragmentContainer, trackFragment, "track")
                    .hide(trackFragment)
                    .add(R.id.fragmentContainer, scheduleFragment, "schedule")
                    .hide(scheduleFragment)
                    .add(R.id.fragmentContainer, recordFragment, "record")
                    .commit();
        } else {
            recordFragment = (RecordFragment) getSupportFragmentManager().findFragmentByTag("record");
            scheduleFragment = (ScheduleFragment) getSupportFragmentManager().findFragmentByTag("schedule");
            trackFragment = (TrackFragment) getSupportFragmentManager().findFragmentByTag("track");
            diaryFragment = (DiaryFragment) getSupportFragmentManager().findFragmentByTag("diary");
            insightFragment = (InsightFragment) getSupportFragmentManager().findFragmentByTag("insight");
            profileFragment = (ProfileFragment) getSupportFragmentManager().findFragmentByTag("profile");
            // 恢复 activeFragment：找到当前可见的 Fragment
            if (recordFragment != null && !recordFragment.isHidden()) activeFragment = recordFragment;
            else if (scheduleFragment != null && !scheduleFragment.isHidden()) activeFragment = scheduleFragment;
            else if (trackFragment != null && !trackFragment.isHidden()) activeFragment = trackFragment;
            else if (diaryFragment != null && !diaryFragment.isHidden()) activeFragment = diaryFragment;
            else if (insightFragment != null && !insightFragment.isHidden()) activeFragment = insightFragment;
            else if (profileFragment != null && !profileFragment.isHidden()) activeFragment = profileFragment;
            else {
                // fallback：所有 Fragment 都处于 hidden 状态时显式 show recordFragment，
                // 避免后续 switchFragment 因 activeFragment 不可见导致动画异常
                activeFragment = recordFragment;
                getSupportFragmentManager().beginTransaction().show(recordFragment).commit();
            }
            // 旋转/进程恢复后同步顶部模式指示器圆点高亮
            activeDotIndex = 0;
            updateModeIndicator(getTabIndex(activeFragment));
        }

        initBottomNav();
        updateNavSelection(activeFragment);
        handleTabIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTabIntent(intent);
    }

    /**
     * 处理外部（如桌面小组件）传入的目标 Tab。
     * 需在 Fragment 完成提交后再切换，否则 show/hide 会与初始事务竞争。
     */
    private void handleTabIntent(Intent intent) {
        if (intent == null) return;
        int tab = intent.getIntExtra(EXTRA_TAB, -1);
        if (tab < 0) return;
        intent.removeExtra(EXTRA_TAB);
        getSupportFragmentManager().executePendingTransactions();
        Fragment target = fragmentAtIndex(tab);
        if (target != null && target != activeFragment) {
            switchFragment(target);
        }
    }

    /**
     * 自定义底部导航：为 6 个导航项绑定 Fragment 切换点击事件。
     */
    private void initBottomNav() {
        Fragment[] targets = {recordFragment, scheduleFragment, trackFragment,
                diaryFragment, insightFragment, profileFragment};
        for (int i = 0; i < NAV_ITEM_IDS.length; i++) {
            final int index = i;
            View item = findViewById(NAV_ITEM_IDS[i]);
            if (item == null) continue;
            item.setOnClickListener(v -> {
                if (targets[index] != null && targets[index] != activeFragment) {
                    switchFragment(targets[index]);
                }
            });
        }
    }

    /**
     * 更新底部导航选中态：选中项图标与文字为 primary 绿，其余为次级灰。
     */
    private void updateNavSelection(Fragment target) {
        int selected = ContextCompat.getColor(this, R.color.green_primary);
        int unselected = ContextCompat.getColor(this, R.color.text_secondary);
        for (int i = 0; i < NAV_ITEM_IDS.length; i++) {
            ImageView icon = findViewById(NAV_ICON_IDS[i]);
            TextView label = findViewById(NAV_LABEL_IDS[i]);
            boolean isSelected = fragmentAtIndex(i) == target;
            int color = isSelected ? selected : unselected;
            if (icon != null) icon.setColorFilter(color);
            if (label != null) label.setTextColor(color);
        }
    }

    private Fragment fragmentAtIndex(int index) {
        switch (index) {
            case 0: return recordFragment;
            case 1: return scheduleFragment;
            case 2: return trackFragment;
            case 3: return diaryFragment;
            case 4: return insightFragment;
            case 5: return profileFragment;
            default: return null;
        }
    }

    private int getTabIndex(Fragment fragment) {
        if (fragment == recordFragment) return 0;
        if (fragment == scheduleFragment) return 1;
        if (fragment == trackFragment) return 2;
        if (fragment == diaryFragment) return 3;
        if (fragment == insightFragment) return 4;
        if (fragment == profileFragment) return 5;
        return 0;
    }

    /**
     * 更新顶部模式指示器圆点高亮。
     * spec：当前页对应圆点为 green-primary，其余为 rgba(0,0,0,0.15)。
     */
    private void updateModeIndicator(int newIndex) {
        if (newIndex == activeDotIndex) return;
        if (newIndex < 0 || newIndex >= DOT_IDS.length) return;

        View oldDot = findViewById(DOT_IDS[activeDotIndex]);
        View newDot = findViewById(DOT_IDS[newIndex]);
        if (oldDot != null) {
            oldDot.setBackgroundResource(R.drawable.dot_mode_indicator_inactive);
        }
        if (newDot != null) {
            newDot.setBackgroundResource(R.drawable.dot_mode_indicator_active);
        }
        activeDotIndex = newIndex;
    }

    private void switchFragment(Fragment target) {
        if (target == null || activeFragment == null || target == activeFragment) return;

        View targetView = target.getView();
        View activeView = activeFragment.getView();

        getSupportFragmentManager().beginTransaction()
                .hide(activeFragment)
                .show(target)
                .commitAllowingStateLoss();

        // 判断切换方向：根据 tab index 判断左滑还是右滑
        int fromIndex = getTabIndex(activeFragment);
        int toIndex = getTabIndex(target);
        boolean goingRight = toIndex > fromIndex;
        float slideOffset = getResources().getDimension(R.dimen.fragment_slide_offset);
        int activeAnimDuration = getResources().getInteger(R.integer.fragment_anim_duration);
        int targetAnimDuration = getResources().getInteger(R.integer.fragment_anim_duration_target);
        float activeSlideX = goingRight ? -slideOffset : slideOffset;
        float targetSlideX = goingRight ? slideOffset : -slideOffset;

        if (activeView != null) {
            activeView.animate()
                    .alpha(0f)
                    .translationX(activeSlideX)
                    .setDuration(activeAnimDuration)
                    .withEndAction(() -> {
                        activeView.setAlpha(1f);
                        activeView.setTranslationX(0f);
                    })
                    .start();
        }

        if (targetView != null) {
            targetView.setAlpha(0f);
            targetView.setTranslationX(targetSlideX);
            targetView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(targetAnimDuration)
                    .start();
        }

        activeFragment = target;

        // 同步顶部模式指示器圆点与底部导航选中态
        updateModeIndicator(getTabIndex(target));
        updateNavSelection(target);

        if (analyticsDao != null) {
            final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            final String column;
            if (target == recordFragment) column = DiaryDbHelper.COLUMN_DU_RECORD_OPENS;
            else if (target == trackFragment) column = DiaryDbHelper.COLUMN_DU_TRACK_OPENS;
            else if (target == diaryFragment) column = DiaryDbHelper.COLUMN_DU_DIARY_OPENS;
            else if (target == profileFragment) column = DiaryDbHelper.COLUMN_DU_PROFILE_OPENS;
            else column = null;
            if (column != null) {
                AppExecutors.io().execute(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    analyticsDao.incrementOpenCount(today, column);
                });
            }
        }
    }
}
