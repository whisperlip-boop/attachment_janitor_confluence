package co.bskim.confluence.attachjanitor.rest;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * {@code "ids": [1, 2, 3]} 같은 숫자 배열을 읽는다.
     *
     * <p>구버전 정리가 첨부 id 목록을 받는 데 쓴다. 숫자만 받는다 — 문자열이 섞여
     * 들어오면 통째로 빈 목록이 된다. 지우는 요청에서 <b>일부만 알아듣고 진행하는 것</b>이
     * 못 알아듣는 것보다 나쁘기 때문이다.
     *
     * @param limit 이보다 많으면 빈 목록. 한 번에 지나치게 많은 요청을 막는다
     */
    public static List<Long> numbers(String json, String key, int limit)
    {
        List<Long> out = new ArrayList<Long>();
        if (json == null)
        {
            return out;
        }
        Matcher array = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(json);
        if (!array.find())
        {
            return out;
        }
        String body = array.group(1).trim();
        if (body.isEmpty())
        {
            return out;
        }
        for (String part : body.split(","))
        {
            String token = part.trim();
            if (!token.matches("[0-9]{1,18}"))
            {
                return new ArrayList<Long>();
            }
            out.add(Long.valueOf(token));
            if (out.size() > limit)
            {
                return new ArrayList<Long>();
            }
        }
        return out;
    }

    /**
     * {@code "expect": ["12:1,2,3", "13:4"]} 같은 문자열 배열을 읽는다.
     *
     * <p>구버전 정리의 <b>대조용</b>이다. 화면이 미리보기에서 본 것을 그대로 돌려보내고
     * 서버가 실행 직전에 다시 계산한 것과 견준다. 이게 없으면 "실행 시점에 다시 확인"이
     * 서버가 방금 계산한 것끼리 비교하는 빈 절차가 된다.
     *
     * <p>안전한 문자에서만 읽는다 — 숫자·쉼표·콜론. 그 밖의 것이 섞이면 통째로 빈 목록이다.
     */
    public static List<String> plainStrings(String json, String key, int limit)
    {
        List<String> out = new ArrayList<String>();
        if (json == null)
        {
            return out;
        }
        Matcher array = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(json);
        if (!array.find())
        {
            return out;
        }
        String body = array.group(1).trim();
        if (body.isEmpty())
        {
            return out;
        }
        Matcher item = Pattern.compile("\"([0-9:,]{1,512})\"").matcher(body);
        int seen = 0;
        while (item.find())
        {
            out.add(item.group(1));
            seen++;
            if (seen > limit)
            {
                return new ArrayList<String>();
            }
        }
        return out;
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
