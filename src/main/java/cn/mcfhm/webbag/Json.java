package cn.mcfhm.webbag;

import java.util.ArrayDeque;

/**
 * 极简 JSON 构造器，避免引入额外依赖。
 */
final class Json {

    private final StringBuilder sb;
    private final ArrayDeque<Integer> counts = new ArrayDeque<>();
    private boolean namePending = false;

    Json(int capacity) {
        this.sb = new StringBuilder(capacity);
    }

    Json beginObject() {
        separator();
        sb.append('{');
        counts.push(0);
        return this;
    }

    Json endObject() {
        counts.pop();
        sb.append('}');
        return this;
    }

    Json beginArray() {
        separator();
        sb.append('[');
        counts.push(0);
        return this;
    }

    Json endArray() {
        counts.pop();
        sb.append(']');
        return this;
    }

    Json name(String name) {
        separator();
        sb.append(quote(name)).append(':');
        namePending = true;
        return this;
    }

    Json value(String value) {
        separator();
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(quote(value));
        }
        return this;
    }

    Json value(int value) {
        separator();
        sb.append(value);
        return this;
    }

    Json value(long value) {
        separator();
        sb.append(value);
        return this;
    }

    Json value(double value) {
        separator();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            sb.append('0');
        } else {
            sb.append(Math.round(value * 100.0) / 100.0);
        }
        return this;
    }

    Json value(boolean value) {
        separator();
        sb.append(value);
        return this;
    }

    Json nullValue() {
        separator();
        sb.append("null");
        return this;
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    private void separator() {
        if (namePending) {
            namePending = false;
            return;
        }
        if (counts.isEmpty()) {
            return;
        }
        int count = counts.pop();
        if (count > 0) {
            sb.append(',');
        }
        counts.push(count + 1);
    }

    private static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2);
        out.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
