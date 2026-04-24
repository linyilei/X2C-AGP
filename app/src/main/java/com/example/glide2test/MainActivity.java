package com.example.glide2test;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.linyilei.x2c.runtime.X2C;
import com.example.featuredemo.FeatureDemoActivity;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        X2C.setContentView(this, R.layout.activity_main);

        RecyclerView list = findViewById(R.id.main_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new TextAdapter(Arrays.asList(
                "主模块 item 1：activity_main 命中 app 生成的 X2CGroup",
                "主模块 item 2：feature_card 通过 root 索引懒加载 library group",
                "主模块 item 3：普通 include 作为整体参与 ConstraintLayout 约束"
        )));

        RecyclerView featureList = findViewById(com.example.featuredemo.R.id.feature_list);
        featureList.setLayoutManager(new LinearLayoutManager(this));
        featureList.setAdapter(new TextAdapter(Arrays.asList(
                "子模块卡片 item 1：library layout 被 app root 索引路由命中",
                "子模块卡片 item 2：RecyclerView 使用 AttributeSet 创建"
        )));

        findViewById(R.id.open_feature).setOnClickListener(v ->
                startActivity(new Intent(this, FeatureDemoActivity.class)));
    }

    private static class TextAdapter extends RecyclerView.Adapter<TextHolder> {
        private final List<String> items;

        TextAdapter(List<String> items) {
            this.items = items;
        }

        @Override
        public TextHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            int padding = (int) (12 * parent.getResources().getDisplayMetrics().density);
            view.setPadding(padding, padding, padding, padding);
            view.setTextColor(0xFF314039);
            view.setTextSize(14);
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
