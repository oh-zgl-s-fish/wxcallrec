package com.zglinus.wxcallrec;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 录音文件列表；点一条从底部弹出半屏播放器。 */
public class RecordListActivity extends Activity {

    private final List<RecordStore.Item> items = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView tvEmpty;
    private PlayerSheet sheet;
    private View scrim;
    private boolean sheetOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Crash.install(this);
        setContentView(build());
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sheet != null) sheet.release();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(18), dp(16), 0);

        TextView title = new TextView(this);
        title.setText("录音");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        ListView lv = new ListView(this);
        lv.setDivider(null);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                RecordStore.Item it = items.get(position);
                LinearLayout row = new LinearLayout(RecordListActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(4), dp(12), dp(4), dp(12));

                TextView a = new TextView(RecordListActivity.this);
                a.setText(it.line1());
                a.setTextSize(16);
                row.addView(a);

                TextView b = new TextView(RecordListActivity.this);
                b.setText(it.line2());
                b.setTextSize(11);
                b.setTextColor(getColor(R.color.text_muted));
                b.setTypeface(Typeface.MONOSPACE);
                row.addView(b);
                return row;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> openSheet(items.get(pos)));
        col.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tvEmpty = new TextView(this);
        tvEmpty.setText("还没有录音");
        tvEmpty.setTextSize(13);
        tvEmpty.setTextColor(Color.GRAY);
        tvEmpty.setGravity(Gravity.CENTER);
        col.addView(tvEmpty);

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);
        // 点播放器外的屏幕 = 收起；关闭只走 ✕
        scrim.setOnClickListener(v -> {
            if (!sheetOpen) return;
            sheet.collapse();
        });
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sheet = new PlayerSheet(this);
        sheet.setListener(new PlayerSheet.Listener() {
            @Override
            public void onRequestClose() {
                closeSheet();
            }

            @Override
            public void onCollapsedChanged(boolean collapsed) {
                // 收起时遮罩让开，文件列表可以滑动
                if (collapsed) {
                    scrim.setVisibility(View.GONE);
                } else if (sheetOpen) {
                    scrim.setVisibility(View.VISIBLE);
                    scrim.animate().alpha(1f).setDuration(150).start();
                }
            }
        });
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(sheet, sp);
        sheet.setVisibility(View.GONE);

        return root;
    }

    private void reload() {
        items.clear();
        items.addAll(RecordStore.list(this));
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openSheet(RecordStore.Item item) {
        if (sheetOpen) return;
        sheetOpen = true;
        sheet.setVisibility(View.VISIBLE);
        sheet.post(() -> {
            if (sheet.getHeight() <= 0) return;
            sheet.setTranslationY(sheet.getHeight());
            sheet.animate().translationY(0).setDuration(220).start();
        });

        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(220).start();

        sheet.open(item);
    }

    private void closeSheet() {
        if (!sheetOpen) return;
        sheetOpen = false;
        sheet.release();
        sheet.animate().translationY(sheet.getHeight()).setDuration(180)
                .withEndAction(() -> sheet.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }
}
