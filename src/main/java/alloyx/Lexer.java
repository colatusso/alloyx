package alloyx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Lexer {
    // start = offset in source, used to slice exact SOQL text out of [ ... ].
    record Token(String kind, String value, int start) {}

    private static final String[] KINDS =
        {"COMMENTLINE", "COMMENTBLOCK", "WS", "NUMBER", "STRING", "IDENT", "OP"};

    private static final Pattern MASTER = Pattern.compile(String.join("|",
        "(?<COMMENTLINE>//[^\\n]*)",
        "(?<COMMENTBLOCK>/\\*.*?\\*/)",
        "(?<WS>\\s+)",
        "(?<NUMBER>\\d+\\.\\d+|\\d+)",
        // unrolled loop ('[^'\]*(?:\.[^'\]*)*') — NOT '(?:[^'\]|\.)*', whose
        // alternation-in-a-star recurses per char and stack-overflows on long literals
        "(?<STRING>'[^'\\\\]*(?:\\\\.[^'\\\\]*)*')",
        "(?<IDENT>[A-Za-z_]\\w*)",
        "(?<OP>=>|==|!=|<=|>=|&&|\\|\\||\\+\\+|--|\\+=|-=|\\*=|/=|[-+*/=<>!{}()\\[\\];,.@?:&|^~])"
    ), Pattern.DOTALL);

    static List<Token> tokenize(String src) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = MASTER.matcher(src);
        while (m.find()) {
            String kind = null;
            for (String k : KINDS) {
                if (m.group(k) != null) {
                    kind = k;
                    break;
                }
            }
            if (kind == null || kind.equals("WS")
                || kind.equals("COMMENTLINE") || kind.equals("COMMENTBLOCK")) {
                continue;
            }
            tokens.add(new Token(kind, m.group(), m.start()));
        }
        tokens.add(new Token("EOF", "", src.length()));
        return tokens;
    }
}
