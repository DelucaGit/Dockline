package io.github.delucagit.dockline.log;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks tokens in any text before it leaves the backend: every log line written to a file or the
 * console (through {@link MaskingConverter}) and every log entry returned by the REST API.
 *
 * <p>It recognises GitHub tokens ({@code ghp_}, {@code gho_}, {@code ghu_}, {@code ghs_}, {@code ghr_},
 * {@code github_pat_}), {@code Authorization} header values, {@code Bearer ...} values (this is how
 * Hostinger API tokens are sent) and {@code token=}/{@code "access_token":} style key/value pairs.
 * On top of that, code that stores a real token registers it with {@link #register(String)}, so that
 * exact value is masked wherever it shows up, whatever its format.
 *
 * <p>Static on purpose: Logback creates its converters itself, outside Spring.
 */
public final class SecretMasker {

    /** What a masked secret is replaced with. */
    public static final String MASK = "****";

    /** Shorter values are not registered, so masking cannot garble ordinary text. */
    private static final int MIN_KNOWN_SECRET_LENGTH = 8;

    private static final Set<String> KNOWN_SECRETS = ConcurrentHashMap.newKeySet();

    /** Each pattern keeps group 1 (the label) and replaces the rest of the match with the mask. */
    private static final List<Pattern> PATTERNS = List.of(
            // Authorization: Bearer abc / Authorization: token ghp_... / "authorization":"Basic xyz"
            Pattern.compile("(?i)(authorization\"?\\s*[:=]\\s*\"?)[^\\r\\n\",}]+"),
            // Bearer abc (Hostinger API tokens, OAuth access tokens)
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9\\-._~+/=|:]+"),
            // GitHub tokens: classic/OAuth/user/server/refresh and fine-grained
            Pattern.compile("\\b(gh[pousr]_)[A-Za-z0-9]{16,}"),
            Pattern.compile("\\b(github_pat_)[A-Za-z0-9_]{16,}"),
            // token=abc, "access_token":"abc", api_key: abc, client_secret=abc, password=abc
            Pattern.compile("(?i)(\"?\\b(?:access_token|refresh_token|token|api_key|apikey|client_secret|secret|password)\"?\\s*[:=]\\s*\"?)[^\\s\",&}]+"));

    private SecretMasker() {}

    /** Returns the text with every known secret and token-looking value replaced by {@link #MASK}. Null stays null. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : KNOWN_SECRETS) {
            result = result.replace(secret, MASK);
        }
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK));
            }
        }
        return result;
    }

    /** Masks this exact value from now on, wherever it appears (e.g. a token the user just saved). */
    public static void register(String secret) {
        if (secret != null && secret.strip().length() >= MIN_KNOWN_SECRET_LENGTH) {
            KNOWN_SECRETS.add(secret.strip());
        }
    }

    /** Stops treating this value as a secret (e.g. after the user removed the token). */
    public static void unregister(String secret) {
        if (secret != null) {
            KNOWN_SECRETS.remove(secret.strip());
        }
    }
}
