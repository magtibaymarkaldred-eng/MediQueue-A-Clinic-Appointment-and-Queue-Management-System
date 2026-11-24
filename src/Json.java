
package mediqueue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small JSON writer that requires no extra libraries.
 */
final class Json {
    private Json() { }

    static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();

        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }

        return m;
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;

            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                append(sb, e.getValue());
            }

            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;

            for (Object item : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                append(sb, item);
            }

            sb.append(']');
        } else if (v instanceof Object[]) {
            append(sb, Arrays.asList((Object[]) v));
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == '<' || c == '>'
                            || c == '&' || c == 0x2028
                            || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }

        sb.append('"');
    }
}
