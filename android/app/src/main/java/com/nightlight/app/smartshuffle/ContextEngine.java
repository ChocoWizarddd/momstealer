package com.nightlight.app.smartshuffle;

import android.content.Context;

import com.nightlight.app.data.api.dto.WeatherDtos;

import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the "listening environment" for Home and Smart Shuffle from soft
 * contextual signals: local time of day, optional weather (via the backend),
 * and the user's explicit mood when one is active. These are ranking hints,
 * never hard filters — and explicit user mood always wins.
 */
public final class ContextEngine {

    public enum TimeOfDay { EARLY_MORNING, MORNING, AFTERNOON, EVENING, NIGHT, LATE_NIGHT }

    /** Everything Home and the shuffle engine need to know about right now. */
    public static final class Environment {
        public final TimeOfDay timeOfDay;
        public final String greeting;
        /** Title for the "For you right now" Home section. */
        public final String title;
        /** Small subtitle under the title (e.g. "Rain in Haldwani · 25°C"). */
        public final String subtitle;
        /** Mood keys worth leaning into right now (never authoritative). */
        public final Set<String> moodHints;
        public final String explicitMood; // may be null

        Environment(TimeOfDay timeOfDay, String greeting, String title, String subtitle,
                    Set<String> moodHints, String explicitMood) {
            this.timeOfDay = timeOfDay;
            this.greeting = greeting;
            this.title = title;
            this.subtitle = subtitle;
            this.moodHints = moodHints;
            this.explicitMood = explicitMood;
        }
    }

    private static final String[] MOOD_LABELS = {
            "Love", "Sad", "Chill", "Happy", "Energy", "Workout", "Party", "Focus"
    };
    private static final String[] MOOD_KEYS = {
            ListeningContext.LOVE, ListeningContext.SAD, ListeningContext.CHILL,
            ListeningContext.HAPPY, ListeningContext.ENERGY, ListeningContext.WORKOUT,
            ListeningContext.PARTY, ListeningContext.FOCUS
    };

    private ContextEngine() {
    }

    /** The mood chips shown on Home. Returns [label, label, key] rows. */
    public static String[][] moodChips() {
        String[][] chips = new String[MOOD_KEYS.length][];
        for (int i = 0; i < MOOD_KEYS.length; i++) {
            chips[i] = new String[]{MOOD_LABELS[i], MOOD_LABELS[i], MOOD_KEYS[i]};
        }
        return chips;
    }

    public static TimeOfDay timeOfDay() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return TimeOfDay.LATE_NIGHT;
        if (hour < 9) return TimeOfDay.EARLY_MORNING;
        if (hour < 12) return TimeOfDay.MORNING;
        if (hour < 17) return TimeOfDay.AFTERNOON;
        if (hour < 21) return TimeOfDay.EVENING;
        return TimeOfDay.NIGHT;
    }

    public static String greeting() {
        TimeOfDay t = timeOfDay();
        switch (t) {
            case EARLY_MORNING:
            case MORNING:
                return "Good morning";
            case AFTERNOON:
                return "Good afternoon";
            default:
                return "Good evening";
        }
    }

    /** Builds the environment; weather may be null (Home keeps working). */
    public static Environment build(Context app, WeatherDtos.WeatherDto weather) {
        TimeOfDay t = timeOfDay();
        String explicitMood = com.nightlight.app.util.AccountPrefs.effectiveMood(app);
        Set<String> hints = new LinkedHashSet<>();
        if (explicitMood != null) {
            hints.add(explicitMood);
        }
        addTimeHints(t, hints);
        addWeatherHints(weather, hints);

        String title = titleFor(t, weather, explicitMood);
        String subtitle = subtitleFor(weather);
        return new Environment(t, greeting(), title, subtitle, hints, explicitMood);
    }

    private static String titleFor(TimeOfDay t, WeatherDtos.WeatherDto w, String explicitMood) {
        if (explicitMood != null) {
            String label = labelForKey(explicitMood);
            return label + " — songs for your mood";
        }
        String cond = w != null ? w.condition : "UNKNOWN";
        boolean night = t == TimeOfDay.NIGHT || t == TimeOfDay.LATE_NIGHT;
        if ("RAIN".equals(cond) || "HEAVY_RAIN".equals(cond) || "THUNDERSTORM".equals(cond)) {
            return night ? "Rainy night — soft songs for tonight" : "Rainy — calm songs";
        }
        if ("SNOW".equals(cond) || "COLD".equals(cond)) {
            return "Cold — cozy sounds";
        }
        if ("SUNNY".equals(cond) || "HOT".equals(cond)) {
            if (t == TimeOfDay.EARLY_MORNING || t == TimeOfDay.MORNING) {
                return "Sunny morning — start your day";
            }
            return "Sunny — feel-good songs";
        }
        if ("FOG".equals(cond) || "CLOUDY".equals(cond) || "PARTLY_CLOUDY".equals(cond)) {
            return night ? "Quiet night — ambient moods" : "Overcast — mellow picks";
        }
        switch (t) {
            case EARLY_MORNING:
                return "Early morning — soft start";
            case MORNING:
                return "Morning — fresh picks";
            case AFTERNOON:
                return "Afternoon — keep it going";
            case EVENING:
                return "Evening — wind down";
            case NIGHT:
                return "Night — unwind";
            case LATE_NIGHT:
                return "Late night — deep calm";
        }
        return "For you right now";
    }

    private static String subtitleFor(WeatherDtos.WeatherDto w) {
        if (w == null || w.label == null || w.label.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(w.label);
        if (w.city != null && !w.city.isEmpty()) {
            sb.append(" in ").append(w.city);
        }
        if (w.tempC != null) {
            sb.append(" · ").append(Math.round(w.tempC)).append("°C");
        }
        return sb.toString();
    }

    private static void addTimeHints(TimeOfDay t, Set<String> hints) {
        switch (t) {
            case EARLY_MORNING:
            case MORNING:
                hints.add(ListeningContext.HAPPY);
                break;
            case AFTERNOON:
                hints.add(ListeningContext.ENERGY);
                break;
            case EVENING:
                hints.add(ListeningContext.CHILL);
                break;
            case NIGHT:
            case LATE_NIGHT:
                hints.add(ListeningContext.CHILL);
                hints.add(ListeningContext.LOVE);
                break;
        }
    }

    private static void addWeatherHints(WeatherDtos.WeatherDto w, Set<String> hints) {
        if (w == null) {
            return;
        }
        String cond = w.condition;
        if ("RAIN".equals(cond) || "HEAVY_RAIN".equals(cond) || "THUNDERSTORM".equals(cond)) {
            hints.add(ListeningContext.CHILL);
            hints.add(ListeningContext.SAD);
        } else if ("SUNNY".equals(cond) || "HOT".equals(cond)) {
            hints.add(ListeningContext.HAPPY);
            hints.add(ListeningContext.ENERGY);
        } else if ("SNOW".equals(cond) || "COLD".equals(cond)) {
            hints.add(ListeningContext.LOVE);
            hints.add(ListeningContext.ACOUSTIC);
        } else if ("FOG".equals(cond) || "CLOUDY".equals(cond) || "PARTLY_CLOUDY".equals(cond)) {
            hints.add(ListeningContext.CHILL);
            hints.add(ListeningContext.FOCUS);
        }
    }

    private static String labelForKey(String key) {
        for (int i = 0; i < MOOD_KEYS.length; i++) {
            if (MOOD_KEYS[i].equals(key)) {
                return MOOD_LABELS[i];
            }
        }
        return key;
    }

    /** Read-only view for tests. */
    public static Set<String> emptyHints() {
        return Collections.emptySet();
    }
}