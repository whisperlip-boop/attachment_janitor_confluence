package co.bskim.confluence.attachjanitor.model;

import co.bskim.confluence.attachjanitor.web.Screen;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 설명서 표와 화면 필터는 열거형을 순회해 만든다. 그래서 <b>열거형에 값을 추가하고
 * i18n 키를 빼먹으면 설명서에 키가 그대로 찍힌다.</b>
 *
 * <p>이 테스트가 그 사고를 막는다. 설명서와 실제 동작이 어긋나는 것은 설명서가 없는
 * 것보다 나쁘다는 것이 이 계열 앱의 원칙이고, 원칙은 검사가 있어야 지켜진다.
 */
public class BundleKeysTest
{
    private static Properties bundle(String name) throws IOException
    {
        InputStream stream = BundleKeysTest.class.getResourceAsStream("/" + name);
        assertNotNull(name + " 이 리소스에 없다", stream);
        try
        {
            Properties properties = new Properties();
            // 생성된 번들은 ISO-8859-1 + \\uXXXX 다. Properties 가 그대로 읽는다.
            properties.load(new InputStreamReader(stream, "ISO-8859-1"));
            return properties;
        }
        finally
        {
            stream.close();
        }
    }

    private static void requireAll(Properties properties, List<String> keys, String where)
    {
        List<String> missing = new ArrayList<String>();
        for (String key : keys)
        {
            if (!properties.containsKey(key))
            {
                missing.add(key);
            }
        }
        assertEquals(where + " 에 빠진 키", "[]", missing.toString());
    }

    private static List<String> derivedKeys()
    {
        List<String> keys = new ArrayList<String>();
        for (Label label : Label.values())
        {
            keys.add(label.i18nKey("name"));
            keys.add(label.i18nKey("cond"));
            keys.add(label.i18nKey("act"));
        }
        // 이력을 검사하지 않았을 때 [고아] 가 바꿔 다는 이름. 화면이 직접 만든다.
        keys.add("aj.label.name.orphan_unchecked");

        for (Badge badge : Badge.values())
        {
            keys.add(badge.i18nKey("name"));
            keys.add(badge.i18nKey("desc"));
        }
        keys.add("aj.badge.name.duplicate_maybe");

        for (RefKind kind : RefKind.values())
        {
            keys.add(kind.i18nKey());
            keys.add("aj.help.ref.desc." + kind.code());
        }
        for (Screen screen : Screen.values())
        {
            if (screen.isTab())
            {
                keys.add(screen.labelKey());
            }
        }
        for (ScanProgress.Phase phase : ScanProgress.Phase.values())
        {
            keys.add("aj.phase." + phase.name().toLowerCase());
        }
        return keys;
    }

    @Test
    public void englishBundleCoversEveryEnum() throws IOException
    {
        requireAll(bundle("attachment-janitor.properties"), derivedKeys(), "영어 번들");
    }

    @Test
    public void koreanBundleCoversEveryEnum() throws IOException
    {
        requireAll(bundle("attachment-janitor_ko.properties"), derivedKeys(), "한국어 번들");
    }

    @Test
    public void bothBundlesHaveTheSameKeys() throws IOException
    {
        Properties english = bundle("attachment-janitor.properties");
        Properties korean = bundle("attachment-janitor_ko.properties");

        List<String> onlyEnglish = new ArrayList<String>();
        for (String key : english.stringPropertyNames())
        {
            if (!korean.containsKey(key))
            {
                onlyEnglish.add(key);
            }
        }
        List<String> onlyKorean = new ArrayList<String>();
        for (String key : korean.stringPropertyNames())
        {
            if (!english.containsKey(key))
            {
                onlyKorean.add(key);
            }
        }
        assertEquals("영어에만 있는 키", "[]", onlyEnglish.toString());
        assertEquals("한국어에만 있는 키", "[]", onlyKorean.toString());
    }

    @Test
    public void cleanupRanksAreDistinct()
    {
        // 정렬이 흔들리지 않으려면 순위가 겹치면 안 된다.
        List<Integer> seen = new ArrayList<Integer>();
        for (Label label : Label.values())
        {
            assertEquals(label + " 의 정리 순위가 겹친다", false,
                    seen.contains(Integer.valueOf(label.cleanupRank())));
            seen.add(Integer.valueOf(label.cleanupRank()));
        }
    }

    @Test
    public void badgeBitsAreDistinctPowersOfTwo()
    {
        int seen = 0;
        for (Badge badge : Badge.values())
        {
            assertEquals(badge + " 의 비트가 2의 거듭제곱이 아니다", 0,
                    badge.bit() & (badge.bit() - 1));
            assertEquals(badge + " 의 비트가 겹친다", 0, seen & badge.bit());
            seen |= badge.bit();
        }
    }
}
