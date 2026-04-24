package com.example.featuredemo;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.linyilei.x2c.runtime.X2C;

import java.util.Arrays;
import java.util.List;

public class FeatureDemoActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        X2C.setContentView(this, R.layout.activity_feature_demo);

        bindList(R.id.feature_list, Arrays.asList(
                "card item 1：feature_card 被当前模块 X2C 命中",
                "card item 2：nested_card 来自 feature-demo -> feature-nested 传递依赖",
                "card item 3：RecyclerView 由 AttributeSet 构造",
                "card item 4：ConstraintLayout.LayoutParams 来自 XML"
        ));
        bindList(R.id.feature_activity_list, Arrays.asList(
                "activity item 1：activity_feature_demo 由 library X2CGroup 创建",
                "activity item 2：页面内 include 复用 feature_card",
                "activity item 3：点击下方按钮 finish 返回主模块"
        ));

        findViewById(R.id.feature_back).setOnClickListener(v -> finish());
    }

    private void bindList(int id, List<String> items) {
        RecyclerView recyclerView = findViewById(id);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new TextAdapter(items));
    }

    private static class TextAdapter extends RecyclerView.Adapter<TextHolder> {
        private final List<String> items;

        TextAdapter(List<String> items) {
            this.items = items;
        }

        @Override
        public TextHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            int padding = (int) (10 * parent.getResources().getDisplayMetrics().density);
            view.setPadding(padding, padding, padding, padding);
            view.setTextColor(0xFF24495E);
            view.setTextSize(13);
            return new TextHolder(view);
        }

        @Override
        public void onBindViewHolder(TextHolder holder, int position) {
            holder.textView.setText(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class TextHolder extends RecyclerView.ViewHolder {
        final TextView textView;

        TextHolder(TextView itemView) {
            super(itemView);
            textView = itemView;
        }
    }
}
