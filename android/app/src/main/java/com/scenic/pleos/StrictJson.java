package com.scenic.pleos;

/** Bounds nesting and rejects the JavaScript extensions accepted by Android's JSONTokener. */
final class StrictJson {
    private StrictJson() {}

    static boolean isValid(String text) {
        if (text == null) return false;
        try {
            Parser parser = new Parser(text);
            parser.value(0);
            parser.whitespace();
            return parser.position == text.length();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final class Parser {
        final String text;
        int position;
        Parser(String text) { this.text = text; }
        void require(boolean ok) { if (!ok) throw new IllegalArgumentException(); }
        char peek() { return position < text.length() ? text.charAt(position) : '\0'; }
        boolean take(char c) {
            if (position < text.length() && text.charAt(position) == c) { position++; return true; }
            return false;
        }
        void whitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                position++;
            }
        }
        void value(int depth) {
            require(depth <= 64);
            whitespace();
            char c = peek();
            if (c == '{') object(depth + 1);
            else if (c == '[') array(depth + 1);
            else if (c == '"') string();
            else if (c == 't') literal("true");
            else if (c == 'f') literal("false");
            else if (c == 'n') literal("null");
            else number();
        }
        void object(int depth) {
            require(depth <= 64);
            position++;
            whitespace();
            if (take('}')) return;
            do {
                whitespace();
                string();
                whitespace();
                require(take(':'));
                value(depth);
                whitespace();
                if (take('}')) return;
                require(take(','));
            } while (true);
        }
        void array(int depth) {
            require(depth <= 64);
            position++;
            whitespace();
            if (take(']')) return;
            do {
                value(depth);
                whitespace();
                if (take(']')) return;
                require(take(','));
            } while (true);
        }
        void string() {
            require(take('"'));
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return;
                require(c >= 0x20);
                if (c == '\\') {
                    require(position < text.length());
                    char escape = text.charAt(position++);
                    if (escape == 'u') {
                        require(position + 4 <= text.length());
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(position++);
                            require((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f') || (hex >= 'A' && hex <= 'F'));
                        }
                    } else require("\"\\/bfnrt".indexOf(escape) >= 0);
                }
            }
            require(false);
        }
        void literal(String expected) {
            require(text.regionMatches(position, expected, 0, expected.length()));
            position += expected.length();
        }
        void number() {
            take('-');
            if (!take('0')) {
                require(peek() >= '1' && peek() <= '9');
                digits();
            }
            if (take('.')) {
                require(digit(peek()));
                digits();
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                require(digit(peek()));
                digits();
            }
        }
        void digits() { while (digit(peek())) position++; }
        boolean digit(char c) { return c >= '0' && c <= '9'; }
    }
}
