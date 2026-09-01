package com.obsroman.tracelog.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK 内置的极简 JSON 读写器（零第三方依赖）。
 * 仅覆盖本 SDK 需要的范围：写 LogRecord、解析服务端统一响应（code/message/data.accepted/rejected）。
 */
final class Json {

    private Json() {
    }

    // ---------- 写 ----------

    static String write(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, map);
        return sb.toString();
    }

    static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Double || value instanceof Float) {
            sb.append(value);
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------- 读 ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object parsed = new Parser(text).parseValue();
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        throw new TraceLogException("unexpected json payload: " + snippet(text));
    }

    private static String snippet(String text) {
        return text == null ? "null" : text.substring(0, Math.min(120, text.length()));
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> expect("true", Boolean.TRUE);
                case 'f' -> expect("false", Boolean.FALSE);
                case 'n' -> expect("null", null);
                default -> parseNumber();
            };
        }

        private Object expect(String literal, Object value) {
            if (text.startsWith(literal, index)) {
                index += literal.length();
                return value;
            }
            throw new TraceLogException("malformed json at " + index);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            index++; // {
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expectLiteral(':');
                index++;
                map.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    index++;
                } else if (c == '}') {
                    index++;
                    return map;
                } else {
                    throw new TraceLogException("malformed json object at " + index);
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            index++; // [
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    index++;
                } else if (c == ']') {
                    index++;
                    return list;
                } else {
                    throw new TraceLogException("malformed json array at " + index);
                }
            }
        }

        private String parseString() {
            expectLiteral('"');
            index++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(index);
                if (c == '"') {
                    index++;
                    return sb.toString();
                }
                if (c == '\\') {
                    char next = text.charAt(index + 1);
                    switch (next) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(text.substring(index + 2, index + 6), 16));
                            index += 4;
                        }
                        default -> throw new TraceLogException("bad escape at " + index);
                    }
                    index += 2;
                } else {
                    sb.append(c);
                    index++;
                }
            }
        }

        private Number parseNumber() {
            int start = index;
            while (index < text.length() && "-+.eE0123456789".indexOf(text.charAt(index)) >= 0) {
                index++;
            }
            String token = text.substring(start, index);
            if (token.isEmpty()) {
                throw new TraceLogException("malformed number at " + start);
            }
            if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                return Long.parseLong(token);
            }
            return Double.parseDouble(token);
        }

        private void expectLiteral(char c) {
            if (peek() != c) {
                throw new TraceLogException("expected '" + c + "' at " + index);
            }
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private char peek() {
            if (index >= text.length()) {
                throw new TraceLogException("unexpected end of json");
            }
            return text.charAt(index);
        }
    }
}
