package project;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * 与图形界面相同的语言选择：系统语言为中文（zh-CN / zh-HK / zh-TW）时用中文，否则英文。
 */
public final class AppLocale {
    private final Locale locale;
    private final ResourceBundle messages;

    private AppLocale(Locale locale, ResourceBundle messages) {
        this.locale = locale;
        this.messages = messages;
    }

    public static AppLocale load() {
        return load(null);
    }

    public static AppLocale load(String override) {
        Locale locale = override == null || override.isBlank()
                ? detect()
                : parseOverride(override);
        ResourceBundle messages;
        try {
            messages = ResourceBundle.getBundle("messages", locale);
        } catch (Exception e) {
            locale = Locale.US;
            messages = ResourceBundle.getBundle("messages", locale);
        }
        return new AppLocale(locale, messages);
    }

    public static Locale detect() {
        Locale systemLocale = Locale.getDefault();
        if ("zh".equals(systemLocale.getLanguage())) {
            String country = systemLocale.getCountry().toLowerCase();
            if ("cn".equals(country) || "hk".equals(country) || "tw".equals(country)) {
                return Locale.SIMPLIFIED_CHINESE;
            }
        }
        return Locale.US;
    }

    private static Locale parseOverride(String override) {
        String value = override.trim().toLowerCase().replace('_', '-');
        if (value.startsWith("zh")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if (value.startsWith("en")) {
            return Locale.US;
        }
        return detect();
    }

    public Locale locale() {
        return locale;
    }

    public ResourceBundle bundle() {
        return messages;
    }

    public String displayName() {
        if ("zh".equals(locale.getLanguage())) {
            return "zh_CN";
        }
        return "en_US";
    }

    public String get(String key) {
        try {
            return messages.getString(key);
        } catch (Exception e) {
            return key;
        }
    }

    public String get(String key, Object... args) {
        try {
            return MessageFormat.format(messages.getString(key), args);
        } catch (Exception e) {
            return key;
        }
    }
}
