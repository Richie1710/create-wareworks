package dev.wareworks.client.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the hand-made block models, shared by the tests that check them. The test classpath has no
 * JSON library on purpose: these tests run without Minecraft, so they stay fast and can be read on their own.
 */
final class ModelJson {
    private ModelJson() {
    }

    static Map<String, Object> read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "missing model " + path);
        return object(new JsonReader(Files.readString(path, StandardCharsets.UTF_8)).readDocument());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        assertTrue(value instanceof Map, "JSON object expected: " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        assertTrue(value instanceof List, "JSON array expected: " + value);
        return (List<Object>) value;
    }

    static double number(Object value) {
        assertTrue(value instanceof Double, "JSON number expected: " + value);
        return (Double) value;
    }

    /** Minimal JSON reader for the model files: objects, arrays, strings (simple escapes), numbers, booleans, null. */
    private static final class JsonReader {
        private final String text;
        private int position;

        JsonReader(String text) {
            this.text = text;
        }

        Object readDocument() {
            Object value = readValue();
            skipWhitespace();
            if (position != text.length())
                throw new IllegalArgumentException("trailing content at " + position);
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            char next = peek();
            return switch (next) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f', 'n' -> readLiteral();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (peek() == ',') {
                    position++;
                    continue;
                }
                expect('}');
                return result;
            }
        }

        private List<Object> readArray() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (peek() == ',') {
                    position++;
                    continue;
                }
                expect(']');
                return result;
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (peek() != '"') {
                char c = text.charAt(position++);
                if (c == '\\') {
                    char escaped = text.charAt(position++);
                    result.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> escaped;
                    });
                } else {
                    result.append(c);
                }
            }
            position++;
            return result.toString();
        }

        private Object readLiteral() {
            for (Map.Entry<String, Object> literal : Map.<String, Object>of("true", Boolean.TRUE, "false", Boolean.FALSE)
                    .entrySet()) {
                if (text.startsWith(literal.getKey(), position)) {
                    position += literal.getKey().length();
                    return literal.getValue();
                }
            }
            if (text.startsWith("null", position)) {
                position += "null".length();
                return null;
            }
            throw new IllegalArgumentException("unexpected literal at " + position);
        }

        private Double readNumber() {
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0)
                position++;
            if (start == position)
                throw new IllegalArgumentException("unexpected character '" + peek() + "' at " + position);
            return Double.parseDouble(text.substring(start, position));
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position)))
                position++;
        }

        private char peek() {
            if (position >= text.length())
                throw new IllegalArgumentException("unexpected end of JSON");
            return text.charAt(position);
        }

        private void expect(char expected) {
            if (peek() != expected)
                throw new IllegalArgumentException("expected '" + expected + "' at " + position + " but found '" + peek() + "'");
            position++;
        }
    }
}
