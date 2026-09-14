package co.bskim.confluence.attachjanitor.web;

import com.atlassian.confluence.util.i18n.I18NBean;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * 화면이 보여줄 언어를 결정하고 그 언어로 문구를 얻는다.
 *
 * <p>이 앱의 화면들은 {@code ?lang=ko} / {@code ?lang=en} 으로 표시 언어를 고정할 수
 * 있다. 파라미터가 없으면 보는 사람의 Confluence 로케일을 따른다.
 *
 * <p>언어를 URL 파라미터로 두는 이유: "한국어로 열리는 링크"를 사내에 그대로 공유할 수
 * 있다. JS 로 두 언어를 렌더링하고 감추는 방식은 DOM 이 두 배가 되고 링크 공유가 안 된다.
 *
 * <p>모든 화면이 같은 규칙을 써야 하므로 여기 한 곳에 모았다. 화면마다 따로 구현하면
 * 한쪽만 고쳐서 절반은 한국어, 절반은 영어로 나오는 상태가 생긴다.
 */
public final class LocaleText
{
    public static final String KO = "ko";
    public static final String EN = "en";

    private final String lang;
    private final I18NBean bean;

    LocaleText(String lang, I18NBean bean)
    {
        this.lang = lang;
        this.bean = bean;
    }

    /** {@code lang} 파라미터 값을 정규화한다. 아는 값이 아니면 영어다. */
    public static String normalise(String requested, Locale viewerLocale)
    {
        if (requested != null && !requested.trim().isEmpty())
        {
            return KO.equalsIgnoreCase(requested.trim()) ? KO : EN;
        }
        return viewerLocale != null && KO.equals(viewerLocale.getLanguage()) ? KO : EN;
    }

    public static Locale toLocale(String lang)
    {
        return KO.equals(lang) ? Locale.KOREAN : Locale.ENGLISH;
    }

    public String getLang()
    {
        return lang;
    }

    public boolean isKorean()
    {
        return KO.equals(lang);
    }

    /** 지금 보고 있지 않은 쪽. 토글 링크에 쓴다. */
    public String otherLang()
    {
        return isKorean() ? EN : KO;
    }

    /** 이 언어로 문구를 얻는다. 실패하면 키를 그대로 낸다 — 빈 화면보다 낫다. */
    public String text(String key)
    {
        if (bean == null)
        {
            return key;
        }
        String value = bean.getText(key);
        return value == null ? key : value;
    }

    /**
     * 인자를 넣는 문구. {@code {0}} 자리에 들어간다.
     *
     * <p>{@code {0}} 이 있는 키를 1-arg 로 부르면 치환이 안 되고 화면에 {@code {0}} 이
     * 그대로 나온다. 형제 앱이 실제로 밟은 함정이라 여기 적어 둔다.
     */
    public String text(String key, Object... args)
    {
        if (bean == null)
        {
            return key;
        }
        String value = bean.getText(key, args);
        return value == null ? key : value;
    }

    /**
     * 이 접두사로 시작하는 모든 문구. 화면이 브라우저에 넘길 문구 뭉치를 만드는 데 쓴다.
     *
     * <p>키를 손으로 나열하는 대신 이걸 쓰는 이유: 나열은 문구를 추가할 때 한쪽을
     * 빼먹어 화면에 키가 그대로 나오는 사고를 만든다.
     */
    public Map<String, String> translationsForPrefix(String prefix)
    {
        if (bean == null)
        {
            return Collections.emptyMap();
        }
        Map<String, String> found = bean.getTranslationsForPrefix(prefix);
        return found == null ? Collections.<String, String>emptyMap() : found;
    }

    /** 링크에 붙일 {@code lang} 파라미터. 화면을 오갈 때 선택한 언어가 유지되어야 한다. */
    public String queryParam(boolean first)
    {
        return (first ? "?" : "&") + "lang=" + lang;
    }
}
