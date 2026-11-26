package remates.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text and regex helpers used by the parser: search/match shortcuts, strip with a character set, safe substrings
 * and empty-string checks.
 *
 * <p>Every pattern is compiled with {@link Pattern#UNICODE_CHARACTER_CLASS} so that {@code \b}, {@code \w},
 * {@code \s} and {@code \d} treat accented letters (á, é, ñ...) as letters. Without it, {@code \b} next
 * to "É" or "ó" behaves differently and fields such as the province are lost.
 */
final class Cadenas {
    private Cadenas() {
    }

    private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;

    /** Case-sensitive pattern. */
    static Pattern re(String regex) {
        return Pattern.compile(regex, UNICODE);
    }

    /** Case-insensitive pattern. */
    static Pattern rei(String regex) {
        return Pattern.compile(regex, UNICODE | Pattern.CASE_INSENSITIVE);
    }

    /** First match anywhere, or {@code null}. */
    static Matcher search(Pattern p, String s) {
        return search(p, s, 0);
    }

    /** {@code pattern.search(s, pos)}: positions in the result stay absolute. */
    static Matcher search(Pattern p, String s, int from) {
        if (s == null || from > s.length()) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find(from) ? m : null;
    }

    /** Match anchored at the start of the string, or {@code null}. */
    static Matcher match(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.lookingAt() ? m : null;
    }

    /** {@code re.fullmatch}. */
    static boolean fullmatch(Pattern p, String s) {
        return p.matcher(s).matches();
    }

    /** {@code re.sub(p, repl, s)} with a literal replacement. */
    static String sub(Pattern p, String repl, String s) {
        return p.matcher(s).replaceAll(Matcher.quoteReplacement(repl));
    }

    /** {@code re.split(p, s, maxsplit=1)[0]}: text before the first match. */
    static String beforeFirst(Pattern p, String s) {
        Matcher m = search(p, s);
        return m == null ? s : s.substring(0, m.start());
    }

    /** Substring {@code [start, end)} for non-negative indexes (clamped, never throws). */
    static String slice(String s, int start, int end) {
        int len = s.length();
        int a = Math.min(Math.max(start, 0), len);
        int b = Math.min(Math.max(end, 0), len);
        return b <= a ? "" : s.substring(a, b);
    }

    /** Substring from {@code start} (clamped, never throws). */
    static String slice(String s, int start) {
        return slice(s, start, s.length());
    }

    /** Whitespace test for a single char (includes non-breaking spaces). */
    static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }

    /** {@code s.strip()}. */
    static String strip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isSpace(s.charAt(a))) {
            a++;
        }
        while (b > a && isSpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    /** {@code s.strip(chars)}. */
    static String strip(String s, String chars) {
        return rstrip(lstrip(s, chars), chars);
    }

    /** {@code s.lstrip(chars)}. */
    static String lstrip(String s, String chars) {
        int a = 0;
        while (a < s.length() && chars.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        return s.substring(a);
    }

    /** {@code s.rstrip(chars)}. */
    static String rstrip(String s, String chars) {
        int b = s.length();
        while (b > 0 && chars.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(0, b);
    }

    /** True when the string is not null and not empty. */
    static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    /** First non-empty string, or "" when all are null/empty. */
    static String or(String... values) {
        for (String v : values) {
            if (truthy(v)) {
                return v;
            }
        }
        return "";
    }

    /** True when the integer is not null and not 0. */
    static boolean truthy(Integer n) {
        return n != null && n != 0;
    }

    static boolean isDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }
}
