package com.uragestudio.companion;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

/**
 * Small dependency-free Markdown presenter for mobile Chat.
 * It intentionally supports the formatting people use while chatting rather
 * than attempting to become a complete CommonMark parser.
 */
final class MarkdownRichText {
    static CharSequence render(Context context, String markdown) {
        MobileUiKit ui = new MobileUiKit(context);
        SpannableStringBuilder output = new SpannableStringBuilder();
        String[] lines = String.valueOf(markdown == null ? "" : markdown).replace("\r\n", "\n").split("\n", -1);
        boolean fenced = false;
        int codeStart = -1;
        for (String sourceLine : lines) {
            String line = sourceLine;
            if (line.trim().startsWith("```")) {
                if (!fenced) {
                    fenced = true;
                    codeStart = output.length();
                } else {
                    styleCode(context, output, codeStart, output.length());
                    fenced = false;
                    codeStart = -1;
                }
                continue;
            }

            int start = output.length();
            if (fenced) {
                output.append(line);
            } else {
                int headingLevel = headingLevel(line);
                if (headingLevel > 0) {
                    appendInline(context, output, line.substring(headingLevel + 1));
                    output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    output.setSpan(new RelativeSizeSpan(headingLevel == 1 ? 1.35f : headingLevel == 2 ? 1.22f : 1.12f),
                        start, output.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (line.startsWith("> ")) {
                    appendInline(context, output, line.substring(2));
                    output.setSpan(new StyleSpan(Typeface.ITALIC), start, output.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    output.setSpan(new ForegroundColorSpan(ui.textMutedColor()), start, output.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    output.setSpan(new LeadingMarginSpan.Standard(dp(context, 12)), start, output.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (line.matches("^\\s*[-*+]\\s+.*")) {
                    int marker = Math.max(line.indexOf("- "), Math.max(line.indexOf("* "), line.indexOf("+ ")));
                    output.append("• ");
                    appendInline(context, output, line.substring(marker + 2));
                } else {
                    appendInline(context, output, line);
                }
            }
            output.append('\n');
        }
        if (fenced && codeStart >= 0) styleCode(context, output, codeStart, output.length());
        if (output.length() > 0) output.delete(output.length() - 1, output.length());
        return output;
    }

    private static void appendInline(Context context, SpannableStringBuilder output, String line) {
        int cursor = 0;
        while (cursor < line.length()) {
            int token = nextToken(line, cursor);
            if (token < 0) {
                output.append(line.substring(cursor));
                return;
            }
            output.append(line, cursor, token);
            if (line.startsWith("**", token)) {
                int end = line.indexOf("**", token + 2);
                if (end > token + 2) {
                    int start = output.length();
                    output.append(line, token + 2, end);
                    output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    cursor = end + 2;
                    continue;
                }
            } else if (line.charAt(token) == '`') {
                int end = line.indexOf('`', token + 1);
                if (end > token + 1) {
                    int start = output.length();
                    output.append(line, token + 1, end);
                    styleInlineCode(context, output, start, output.length());
                    cursor = end + 1;
                    continue;
                }
            } else if (line.charAt(token) == '[') {
                int labelEnd = line.indexOf("](", token + 1);
                int urlEnd = labelEnd < 0 ? -1 : line.indexOf(')', labelEnd + 2);
                if (labelEnd > token + 1 && urlEnd > labelEnd + 2) {
                    int start = output.length();
                    output.append(line, token + 1, labelEnd);
                    output.setSpan(new UnderlineSpan(), start, output.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    output.setSpan(new ForegroundColorSpan(new MobileUiKit(context).accentStrongColor()), start, output.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    cursor = urlEnd + 1;
                    continue;
                }
            } else if (line.charAt(token) == '*') {
                int end = line.indexOf('*', token + 1);
                if (end > token + 1) {
                    int start = output.length();
                    output.append(line, token + 1, end);
                    output.setSpan(new StyleSpan(Typeface.ITALIC), start, output.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    cursor = end + 1;
                    continue;
                }
            }
            output.append(line.charAt(token));
            cursor = token + 1;
        }
    }

    private static int nextToken(String line, int start) {
        int best = -1;
        for (String token : new String[]{"**", "`", "[", "*"}) {
            int found = line.indexOf(token, start);
            if (found >= 0 && (best < 0 || found < best)) best = found;
        }
        return best;
    }

    private static int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && count < 3 && line.charAt(count) == '#') count++;
        return count > 0 && line.length() > count && line.charAt(count) == ' ' ? count : 0;
    }

    private static void styleCode(Context context, SpannableStringBuilder output, int start, int end) {
        if (end <= start) return;
        output.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        MobileUiKit ui = new MobileUiKit(context);
        output.setSpan(new ForegroundColorSpan(ui.textColor()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.setSpan(new BackgroundColorSpan(ui.surfaceHighColor()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.setSpan(new LeadingMarginSpan.Standard(dp(context, 12)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.setSpan(new RelativeSizeSpan(0.92f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void styleInlineCode(Context context, SpannableStringBuilder output, int start, int end) {
        output.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        MobileUiKit ui = new MobileUiKit(context);
        output.setSpan(new ForegroundColorSpan(ui.accentStrongColor()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.setSpan(new BackgroundColorSpan(ui.surfaceHighColor()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private MarkdownRichText() {}
}
