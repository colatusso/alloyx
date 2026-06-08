package alloyx;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the tokenizer against the catastrophic-recursion bug: the STRING pattern
 * must stay an unrolled loop, so a long string literal (embedded JSON/base64/SOQL —
 * common in real Apex) tokenizes linearly instead of overflowing the stack.
 */
class LexerTest {

    @Test
    void longStringLiteral_doesNotStackOverflow() {
        String literal = "'" + "a".repeat(200_000) + "'";
        var tokens = Lexer.tokenize("String x = " + literal + ";");
        assertTrue(
            tokens.stream().anyMatch(t -> t.kind().equals("STRING") && t.value().length() == literal.length()),
            "the long literal should tokenize as a single STRING");
    }

    @Test
    void stringWithEscapesAndQuotes_tokenizes() {
        var tokens = Lexer.tokenize("String x = 'O\\'Brien said \\\\ done';");
        assertTrue(tokens.stream().anyMatch(t -> t.kind().equals("STRING")),
            "escaped quotes/backslashes belong to one STRING token");
    }

    @Test
    void unterminatedQuote_doesNotStackOverflow() {
        assertDoesNotThrow(() -> Lexer.tokenize("x = '" + "b".repeat(200_000)));
    }
}
