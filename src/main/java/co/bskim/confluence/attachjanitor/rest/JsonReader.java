package co.bskim.confluence.attachjanitor.rest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설정 저장 요청에서 값 하나를 꺼낸다.
 *
 * <p>읽는 것은 <b>우리 설정 화면이 보내는 평평한 객체</b> 하나뿐이다. 숫자 여덟 개와
 * 불리언 하나가 전부라 JSON 라이브러리를 번들할 이유가 없다.
 *
 * <p>키를 못 찾으면 예외가 아니라 기본값을 돌려준다. 설정 화면이 필드 하나를 안 보냈다고
 * 나머지 설정까지 저장되지 않으면 곤란하다.
 */
public final class JsonReader
{
    private JsonReader()
    {
    }

    public static String string(String json, String key, String fallback)
    {
        Matcher matcher = matcher(json, key, "\"((?:[^\"\\\\]|\\\\.)*)\"");
        return matcher == null ? fallback : matcher.group(1).replace("\\\"", "\"");
    }

    public static long number(String json, String key, long fallback)
    {
        Matcher matcher = matcher(json, key, "(-?[0-9]+)");
        if (matcher == null)
        {
            return fallback;
        }
        try
        {
            return Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException error)
        {
            return fallback;
        }
    }

    public static boolean flag(String json, String key, boolean fallback)
    {
        Matcher matcher = matcher(json, key, "(true|false)");
        return matcher == null ? fallback : Boolean.parseBoolean(matcher.group(1));
    }

    private static Matcher matcher(String json, String key, String value)
    {
        if (json == null)
        {
            return null;
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*" + value)
                .matcher(json);
        return matcher.find() ? matcher : null;
    }
}
