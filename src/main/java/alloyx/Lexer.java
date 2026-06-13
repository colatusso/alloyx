// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Lexer {
    // start = offset in source, used to slice exact SOQL text out of [ ... ].
    record Token(String kind, String value, int start) {}

    private static final String[] KINDS =
        {"COMMENTLINE", "COMMENTBLOCK", "WS", "NUMBER", "STRING", "DQUOTE", "IDENT", "OP"};

    private static final Pattern MASTER = Pattern.compile(String.join("|",
        "(?<COMMENTLINE>//[^\\n]*)",
        "(?<COMMENTBLOCK>/\\*.*?\\*/)",
        "(?<WS>\\s+)",
        "(?<NUMBER>\\d+\\.\\d+|\\d+)",
        // unrolled loop ('[^'\]*(?:\.[^'\]*)*') — NOT '(?:[^'\]|\.)*', whose
        // alternation-in-a-star recurses per char and stack-overflows on long literals
        "(?<STRING>'[^'\\\\]*(?:\\\\.[^'\\\\]*)*')",
        // a bare " outside a single-quoted string — Apex has no double-quote literals;
        // emit it as a token so the parser can flag it instead of silently dropping it
        // (which would leave the inner text lexed as a number/identifier)
        "(?<DQUOTE>\")",
        "(?<IDENT>[A-Za-z_]\\w*)",
        // Multi-char operators are listed LONGEST-FIRST so the regex alternation can't
        // tokenize a prefix and leave a stray tail: === before ==, !== before !=, ?? before
        // ?/:. <> is Apex's legacy inequality (== !=); Apex has no diamond <> generics, so a
        // standalone <> is always the operator (a real generic always has a type between < >).
        "(?<OP>===|!==|=>|==|!=|<>|<=|>=|&&|\\|\\||\\?\\?|\\+\\+|--|\\+=|-=|\\*=|/=|[-+*/=<>!{}()\\[\\];,.@?:&|^~])"
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
