package com.uragestudio.companion;

import android.view.View;
import android.widget.AdapterView;

import java.util.function.IntConsumer;

/** Removes repeated no-op Spinner listener plumbing. */
final class SimpleItemSelection implements AdapterView.OnItemSelectedListener {
    private final IntConsumer selection;

    SimpleItemSelection(IntConsumer selection) {
        this.selection = selection;
    }

    @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        selection.accept(position);
    }

    @Override public void onNothingSelected(AdapterView<?> parent) {}
}
