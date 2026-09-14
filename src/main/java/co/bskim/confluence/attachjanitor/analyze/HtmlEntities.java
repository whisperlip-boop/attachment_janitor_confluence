package co.bskim.confluence.attachjanitor.analyze;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confluence 저장 형식을 XML 파서에 넣기 전에 명명 엔티티를 없앤다.
 *
 * <p>저장 형식은 DTD 가 없는 XML 인데 본문에는 {@code &nbsp;} {@code &mdash;} 같은 HTML
 * 엔티티가 그대로 들어 있다(실측: 본문 144개 중 18개). 파서는 첫 번째 엔티티에서
 * 죽는다 — 이걸 처리하지 않으면 "분석 실패"가 소수가 아니라 전부가 된다.
 *
 * <p>DOCTYPE 내부 서브셋에 엔티티를 선언해 넣는 방법도 있지만 그러면 <b>표에 없는
 * 엔티티</b>(다른 앱이 넣은 것)에서 여전히 죽는다. 그래서 선언이 아니라 치환을 한다.
 * 아는 것은 숫자 참조로 바꾸고 모르는 것은 버린다 — 우리가 보는 것은 엘리먼트 구조와
 * 파일명뿐이라 본문 글자가 한 자 사라져도 판정이 달라지지 않는다.
 */
public final class HtmlEntities
{
    /** XML 이 스스로 아는 다섯 개. 건드리면 안 된다. */
    private static final Pattern ENTITY = Pattern.compile("&(#?[0-9a-zA-Z]+);");

    private static final Map<String, String> TABLE = load();

    private HtmlEntities()
    {
    }

    private static Map<String, String> load()
    {
        Map<String, String> table = new HashMap<String, String>();
        InputStream stream = HtmlEntities.class.getResourceAsStream("/html-entities.properties");
        if (stream == null)
        {
            return table;
        }
        try
        {
            Properties properties = new Properties();
            properties.load(stream);
            for (String name : properties.stringPropertyNames())
            {
                table.put(name, "&#" + properties.getProperty(name) + ";");
            }
        }
        catch (IOException error)
        {
            // 표가 없으면 알려진 엔티티도 버려진다. 파싱은 여전히 성공한다.
            return table;
        }
        finally
        {
            try
            {
                stream.close();
            }
            catch (IOException ignored)
            {
                // 읽었거나 못 읽었거나 둘 중 하나다.
            }
        }
        return table;
    }

    public static String neutralise(String xml)
    {
        if (xml == null || xml.indexOf('&') < 0)
        {
            return xml;
        }
        Matcher matcher = ENTITY.matcher(xml);
        StringBuffer out = new StringBuffer(xml.length());
        while (matcher.find())
        {
            String name = matcher.group(1);
            String replacement;
            if (name.charAt(0) == '#')
            {
                replacement = matcher.group();                 // 숫자 참조는 그대로 둔다
            }
            else if ("amp".equals(name) || "lt".equals(name) || "gt".equals(name)
                    || "quot".equals(name) || "apos".equals(name))
            {
                replacement = matcher.group();                 // XML 내장 다섯 개
            }
            else
            {
                String known = TABLE.get(name);
                replacement = known == null ? "" : known;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 표가 실제로 실렸는지 확인하는 용도. 0이면 리소스가 jar 에 안 들어간 것이다. */
    public static int size()
    {
        return TABLE.size();
    }
}
