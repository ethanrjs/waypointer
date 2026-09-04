package com.babbur.waypointer.chat;

import com.babbur.waypointer.codec.AsciiStreamCodec;
import com.babbur.waypointer.codec.CatalogShareLink;
import com.babbur.waypointer.codec.CjkBase16384;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Finds route, configuration, and dungeon share strings in chat. Matches start
 * at a word or punctuation boundary, stay within the chat limit, and trim
 * trailing punctuation when needed.
 */
public final class CodecScanner {

    private static final int MIN_BODY = 3;
    private static final int MAX_MATCHES_PER_MESSAGE = 3;
    private static final int MAX_CODEC_CHARS = 256;
    private static final int MAX_SUFFIX_TRIMS = 3;
    private static final int CLASSIFICATION_CACHE_ENTRIES = 128;
    private static final int CLASSIFICATION_CACHE_MAX_CHARS = MAX_CODEC_CHARS;
    private static final ClassificationCache CLASSIFICATION_CACHE =
            new ClassificationCache(CLASSIFICATION_CACHE_ENTRIES,
                    CLASSIFICATION_CACHE_MAX_CHARS, CodecScanner::classifyShare);

    private CodecScanner() {}

    public record Match(int start, int end, String text, boolean valid,
                        UniversalShareCodec.Type type) {
        public Match(int start, int end, String text) {
            this(start, end, text, true, UniversalShareCodec.Type.WAYPOINTS);
        }

        public Match(int start, int end, String text, boolean valid) {
            this(start, end, text, valid,
                    valid ? UniversalShareCodec.Type.WAYPOINTS : null);
        }

        public int length() { return end - start; }
    }

    record Classification(UniversalShareCodec.Type type) {
        boolean valid() { return type != null; }
    }

    static final class ClassificationCache {
        private final int maxEntries;
        private final int maxPayloadChars;
        private final Function<String, UniversalShareCodec.Type> loader;
        private final LinkedHashMap<String, Classification> entries =
                new LinkedHashMap<>(16, 0.75f, true);

        ClassificationCache(int maxEntries, int maxPayloadChars,
                            Function<String, UniversalShareCodec.Type> loader) {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("cache capacity must be positive");
            }
            if (maxPayloadChars <= 0) {
                throw new IllegalArgumentException("cache key limit must be positive");
            }
            this.maxEntries = maxEntries;
            this.maxPayloadChars = maxPayloadChars;
            this.loader = loader;
        }

        synchronized Classification classify(String payload) {
            Classification cached = entries.get(payload);
            if (cached != null) return cached;

            Classification result = new Classification(loader.apply(payload));
            if (payload.length() <= maxPayloadChars) {
                entries.put(payload, result);
                if (entries.size() > maxEntries) {
                    entries.remove(entries.keySet().iterator().next());
                }
            }
            return result;
        }

        synchronized int size() {
            return entries.size();
        }
    }

    public static List<Match> scan(String message) {
        List<Match> codes = scanClassified(message, CodecScanner::classifyShareCached);
        List<Match> links = scanCatalogLinks(message);
        if (links.isEmpty()) return codes;
        return merge(codes, links);
    }

    /** {@code waypointermod.com/r/<id>} links are the same share as a kind-6 reference code. */
    private static List<Match> scanCatalogLinks(String message) {
        if (message == null || message.isEmpty()) return List.of();
        List<Match> out = new ArrayList<>();
        java.util.regex.Matcher matcher = CatalogShareLink.find(message);
        while (matcher.find() && out.size() < MAX_MATCHES_PER_MESSAGE) {
            if (!isAtLinkBoundary(message, matcher.start())) continue;
            out.add(new Match(matcher.start(), matcher.end(), matcher.group(), true,
                    UniversalShareCodec.Type.CATALOG));
        }
        return out;
    }

    private static boolean isAtLinkBoundary(String s, int i) {
        if (i == 0) return true;
        char prev = s.charAt(i - 1);
        return !Character.isLetterOrDigit(prev) && prev != '/' && prev != '.';
    }

    /** Earliest-start order, dropping overlaps and keeping the message-wide cap. */
    private static List<Match> merge(List<Match> first, List<Match> second) {
        List<Match> all = new ArrayList<>(first);
        all.addAll(second);
        all.sort(java.util.Comparator.comparingInt(Match::start));
        List<Match> out = new ArrayList<>();
        int lastEnd = -1;
        for (Match match : all) {
            if (match.start() < lastEnd) continue;
            out.add(match);
            lastEnd = match.end();
            if (out.size() == MAX_MATCHES_PER_MESSAGE) break;
        }
        return out;
    }

    static List<Match> scan(String message, Predicate<String> validator) {
        return scanClassified(message, payload -> validator.test(payload)
                ? UniversalShareCodec.Type.WAYPOINTS
                : null);
    }

    private static List<Match> scanClassified(
            String message, Function<String, UniversalShareCodec.Type> classifier) {
        if (message == null || message.isEmpty()) return List.of();

        List<Match> out = new ArrayList<>();
        int i = 0;
        while (i < message.length() && out.size() < MAX_MATCHES_PER_MESSAGE) {
            if (!matchMagicAt(message, i) || !isAtWordBoundary(message, i)) { i++; continue; }

            int bodyStart = i + WaypointCodec.MAGIC.length();
            int bodyEnd = bodyStart;
            int candidateLimit = Math.min(message.length(), i + MAX_CODEC_CHARS);
            while (bodyEnd < candidateLimit && isCodecBodyChar(message.charAt(bodyEnd))) {
                bodyEnd++;
            }
            int greedyBodyEnd = bodyEnd;
            UniversalShareCodec.Type type = bodyEnd - bodyStart >= MIN_BODY
                    ? classifier.apply(message.substring(i, bodyEnd))
                    : null;
            if (type == null) {
                for (int trims = 0;
                     trims < MAX_SUFFIX_TRIMS
                             && bodyEnd > bodyStart + MIN_BODY
                             && isClauseSuffixDelim(message.charAt(bodyEnd - 1));
                     trims++) {
                    bodyEnd--;
                    type = classifier.apply(message.substring(i, bodyEnd));
                    if (type != null) break;
                }
            }
            if (type == null) {
                // Surface broken codes too, so the user sees an import error.
                bodyEnd = greedyBodyEnd;
            }
            int bodyLen = bodyEnd - bodyStart;
            if (bodyLen >= MIN_BODY) {
                out.add(new Match(i, bodyEnd, message.substring(i, bodyEnd),
                        type != null, type));
                i = bodyEnd;
            } else {
                i += WaypointCodec.MAGIC.length();
            }
        }
        return out;
    }

    private static UniversalShareCodec.Type classifyShare(String payload) {
        try {
            return UniversalShareCodec.decode(payload).type();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static UniversalShareCodec.Type classifyShareCached(String payload) {
        return CLASSIFICATION_CACHE.classify(payload).type();
    }

    private static boolean matchMagicAt(String s, int i) {
        return s.regionMatches(i, WaypointCodec.MAGIC, 0, WaypointCodec.MAGIC.length());
    }

    private static boolean isAtWordBoundary(String s, int i) {
        if (i == 0) return true;
        char prev = s.charAt(i - 1);
        if (!isCodecBodyChar(prev)) return true;
        return isClauseBoundaryBeforeMagic(prev);
    }

    private static boolean isCodecBodyChar(char c) {
        return AsciiStreamCodec.isAlphabetChar(c) || CjkBase16384.isAlphabetChar(c);
    }

    private static boolean isClauseBoundaryBeforeMagic(char c) {
        return switch (c) {
            case ',', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}', '\'', '"', '-' -> true;
            default -> false;
        };
    }

    private static boolean isClauseSuffixDelim(char c) {
        return switch (c) {
            case ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"' -> true;
            default -> false;
        };
    }
}
