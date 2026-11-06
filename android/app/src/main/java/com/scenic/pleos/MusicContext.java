package com.scenic.pleos;

/** Only coarse, non-coordinate context is sent to the music recommendation service. */
public final class MusicContext {
    public final String title,theme,preference;
    public final int durationMinutes;

    public MusicContext(String title,String theme,int durationMinutes) {
        this(title,theme,durationMinutes,"balanced");
    }
    public MusicContext(String title,String theme,int durationMinutes,String preference) {
        if(title==null||title.trim().isEmpty()||title.length()>120||hasControl(title)
                ||durationMinutes<1||durationMinutes>720)throw new IllegalArgumentException("Invalid music context");
        if(!"balanced".equals(preference)&&!"acoustic".equals(preference)&&!"chill".equals(preference)&&!"energetic".equals(preference)&&!"instrumental".equals(preference))throw new IllegalArgumentException("Invalid music preference");
        this.title=title.trim();this.theme=normalizeTheme(theme);this.durationMinutes=durationMinutes;this.preference=preference;
    }

    public static String normalizeTheme(String value) {
        if("sea".equals(value))return "coast";
        if("night".equals(value))return "city";
        if("all".equals(value)||"lake".equals(value)||"coast".equals(value)||"forest".equals(value)
                ||"mountain".equals(value)||"water".equals(value)||"countryside".equals(value)||"city".equals(value))return value;
        throw new IllegalArgumentException("Invalid music theme");
    }
    String key() {return title.length()+":"+title+":"+theme+":"+durationMinutes+("balanced".equals(preference)?"":":"+preference);}
    static boolean hasControl(String text){for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))return true;return false;}
}
