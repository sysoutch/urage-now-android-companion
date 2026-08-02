package com.uragestudio.companion;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;

/** Shared readable Spinner rows for the companion's dark interface. */
final class StyledSpinnerAdapter<T> extends ArrayAdapter<T> {
    private final Context context;

    StyledSpinnerAdapter(Context context, List<T> values) {
        super(context, android.R.layout.simple_spinner_dropdown_item, values);
        this.context = context;
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        return style(super.getView(position, convertView, parent), false);
    }

    @Override
    public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
        return style(super.getDropDownView(position, convertView, parent), true);
    }

    private View style(View view, boolean popup) {
        if (!(view instanceof TextView text)) {
            return view;
        }
        MobileUiKit ui = new MobileUiKit(context);
        float density = context.getResources().getDisplayMetrics().density;
        text.setTextColor(ui.textColor());
        text.setTextSize(16);
        text.setPadding(Math.round(14 * density), Math.round(12 * density),
            Math.round(14 * density), Math.round(12 * density));
        text.setBackgroundColor(popup ? ui.surfaceHighColor() : android.graphics.Color.TRANSPARENT);
        return text;
    }
}
