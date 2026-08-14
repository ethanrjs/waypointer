package com.babbur.waypointer.i18n;

import net.minecraft.client.Minecraft;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Locale-aware number formatting for display text only. */
public final class LocalizedNumberFormatter {

    private static final String DEFAULT_LOCALE = "en_us";
    private static final Map<String, LocalizedNumberFormatter> FORMATTERS =
            new ConcurrentHashMap<>();

    private final Locale locale;
    private final DecimalFormatSymbols symbols;

    private LocalizedNumberFormatter(Locale locale) {
        this.locale = locale;
        this.symbols = DecimalFormatSymbols.getInstance(locale);
    }

    public static LocalizedNumberFormatter active() {
        Minecraft minecraft = Minecraft.getInstance();
        String localeCode = minecraft == null || minecraft.getLanguageManager() == null
                ? DEFAULT_LOCALE
                : minecraft.getLanguageManager().getSelected();
        return forMinecraftLocale(localeCode);
    }

    public static LocalizedNumberFormatter forMinecraftLocale(String localeCode) {
        String normalized = localeCode == null || localeCode.isBlank()
                ? DEFAULT_LOCALE
                : localeCode.trim().toLowerCase(Locale.ROOT);
        return FORMATTERS.computeIfAbsent(normalized, code -> {
            Locale locale = Locale.forLanguageTag(code.replace('_', '-'));
            if (locale.getLanguage().isBlank()) locale = Locale.US;
            return new LocalizedNumberFormatter(locale);
        });
    }

    public String integer(long value) {
        NumberFormat format = NumberFormat.getIntegerInstance(locale);
        format.setGroupingUsed(false);
        return format.format(value);
    }

    public String oneDecimal(double value) {
        DecimalFormat format = new DecimalFormat("0.0", symbols);
        format.setGroupingUsed(false);
        return format.format(value);
    }

    /** Localizes the digits and structural decimal separator in labels such as {@code #1.2}. */
    public String waypointOrdinal(String asciiLabel) {
        if (asciiLabel == null || asciiLabel.isEmpty()) return "";
        char zeroDigit = symbols.getZeroDigit();
        char decimalSeparator = symbols.getDecimalSeparator();
        StringBuilder localized = new StringBuilder(asciiLabel.length());
        for (int i = 0; i < asciiLabel.length(); i++) {
            char value = asciiLabel.charAt(i);
            if (value >= '0' && value <= '9') {
                localized.append((char) (zeroDigit + value - '0'));
            } else if (value == '.') {
                localized.append(decimalSeparator);
            } else {
                localized.append(value);
            }
        }
        return localized.toString();
    }
}
