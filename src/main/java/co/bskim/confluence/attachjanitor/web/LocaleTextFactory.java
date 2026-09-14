package co.bskim.confluence.attachjanitor.web;

import com.atlassian.confluence.languages.LocaleManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.confluence.util.i18n.I18NBeanFactory;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Locale;

/**
 * 요청마다 {@link LocaleText} 를 만든다.
 *
 * <p>보는 사람의 로케일은 SAL 의 {@code UserProfile} 로는 알 수 없다.
 * {@code LocaleManager#getLocale} 이 {@code com.atlassian.user.User} 를 받으므로
 * {@code AuthenticatedUserThreadLocal} 이 주는 {@code ConfluenceUser} 를 넘긴다.
 */
@Named
public class LocaleTextFactory
{
    private final I18NBeanFactory i18nBeanFactory;
    private final LocaleManager localeManager;

    @Inject
    public LocaleTextFactory(@ComponentImport I18NBeanFactory i18nBeanFactory,
                             @ComponentImport LocaleManager localeManager)
    {
        this.i18nBeanFactory = i18nBeanFactory;
        this.localeManager = localeManager;
    }

    /** @param requested {@code lang} 쿼리 파라미터. 비어 있으면 보는 사람의 로케일을 쓴다. */
    public LocaleText forRequest(String requested)
    {
        String lang = LocaleText.normalise(requested, viewerLocale());
        return new LocaleText(lang, i18nBeanFactory.getI18NBean(LocaleText.toLocale(lang)));
    }

    private Locale viewerLocale()
    {
        try
        {
            ConfluenceUser user = AuthenticatedUserThreadLocal.get();
            return localeManager.getLocale(user);
        }
        catch (Throwable error)
        {
            // 로케일 하나 때문에 화면이 죽으면 안 된다. 모르면 영어다.
            return Locale.ENGLISH;
        }
    }
}
