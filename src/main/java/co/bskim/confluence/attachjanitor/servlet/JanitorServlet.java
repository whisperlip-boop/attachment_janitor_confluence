package co.bskim.confluence.attachjanitor.servlet;

import co.bskim.confluence.attachjanitor.model.Badge;
import co.bskim.confluence.attachjanitor.model.Label;
import co.bskim.confluence.attachjanitor.rest.Json;
import co.bskim.confluence.attachjanitor.web.AccessGuard;
import co.bskim.confluence.attachjanitor.web.LocaleText;
import co.bskim.confluence.attachjanitor.web.LocaleTextFactory;
import co.bskim.confluence.attachjanitor.web.Screen;
import co.bskim.confluence.attachjanitor.web.StaticAssets;
import com.atlassian.confluence.security.websudo.WebSudoManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

/**
 * 이 앱의 모든 화면. 껍데기는 서버가 그리고 표는 브라우저가
 * {@code /rest/attachment-janitor/1.0/report/*} 에서 받아 그린다.
 *
 * <p>사용 설명서만 예외로 서버가 전부 그린다 — 설명서는 문서라서 JS 없이도 열려야 하고,
 * 링크로 공유하고 인쇄할 수 있어야 한다.
 *
 * <p>웹수도는 여기에서 건다. SAL 의 {@code @WebSudoRequired} 애노테이션은 Confluence
 * 플러그인 서블릿에 아무 효과가 없다 — 애노테이션만 붙인 상태에서 로그인만 한 세션으로
 * 화면이 그대로 열렸다(docs/00-환경실측.md 3번). REST 에는 걸지 않는다: 스캔이 웹수도
 * 제한 시간보다 오래 걸리면 폴링이 도중에 끊기고 관리자는 이유를 못 본다.
 */
@Named
public class JanitorServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    private final AccessGuard access;
    private final LocaleTextFactory localeTexts;
    private final ApplicationProperties applicationProperties;
    private final WebSudoManager webSudoManager;
    private final HelpPage helpPage;

    @Inject
    public JanitorServlet(AccessGuard access, LocaleTextFactory localeTexts,
                          @ComponentImport ApplicationProperties applicationProperties,
                          @ComponentImport WebSudoManager webSudoManager,
                          HelpPage helpPage)
    {
        this.access = access;
        this.localeTexts = localeTexts;
        this.applicationProperties = applicationProperties;
        this.webSudoManager = webSudoManager;
        this.helpPage = helpPage;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        if (!access.isLoggedIn())
        {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Login required");
            return;
        }
        if (!access.isAdmin())
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Confluence administrator required");
            return;
        }

        Screen screen = Screen.fromPath(request.getPathInfo());
        String base = applicationProperties.getBaseUrl(UrlMode.RELATIVE);
        if (!enterWebSudo(request, response, base, screen))
        {
            return;
        }

        LocaleText text = localeTexts.forRequest(request.getParameter("lang"));
        String spaceKey = request.getParameter("key");

        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        PrintWriter out = response.getWriter();

        head(out, text, screen);
        out.print("<body>");
        header(out, text, base, screen, request);
        out.print("<div class=\"aj-app\" data-base=\"");
        out.print(StaticAssets.escape(base));
        out.print("\" data-screen=\"");
        out.print(screen.name());
        out.print("\" data-lang=\"");
        out.print(text.getLang());
        out.print("\" data-space=\"");
        out.print(StaticAssets.escape(spaceKey == null ? "" : spaceKey));
        // 필터 목록은 열거형에서 나온다. 화면이 코드를 직접 적어 두면 열거형에 값을
        // 하나 더해도 필터에서만 조용히 빠진다 — 그건 테스트가 못 잡는다.
        out.print("\" data-labels=\"");
        out.print(StaticAssets.escape(labelCodes()));
        out.print("\" data-badges=\"");
        out.print(StaticAssets.escape(badgeCodes()));
        out.print("\">");

        if (screen == Screen.HELP)
        {
            helpPage.render(out, text, base);
        }
        else
        {
            body(out, text, screen, base);
        }

        out.print("</div>");
        out.print("<script id=\"aj-i18n\" type=\"application/json\">");
        // "</" 를 깨 둔다. 번역 문구가 script 요소를 먼저 닫아 버릴 수 없게 한다.
        out.print(strings(text).replace("</", "<\\/"));
        out.print("</script><script>");
        out.print(StaticAssets.read("/js/attachment-janitor.js"));
        out.print("</script></body></html>");
    }

    /** 화면 필터가 쓸 라벨 코드. 순서는 열거형 선언 순서 그대로다. */
    private static String labelCodes()
    {
        StringBuilder codes = new StringBuilder();
        for (Label label : Label.values())
        {
            if (codes.length() > 0)
            {
                codes.append(',');
            }
            codes.append(label.code());
        }
        return codes.toString();
    }

    /** 화면 필터가 쓸 배지 코드. */
    private static String badgeCodes()
    {
        StringBuilder codes = new StringBuilder();
        for (Badge badge : Badge.values())
        {
            if (codes.length() > 0)
            {
                codes.append(',');
            }
            codes.append(badge.code());
        }
        return codes.toString();
    }

    private void head(PrintWriter out, LocaleText text, Screen screen)
    {
        out.print("<html><head><title>");
        out.print(escaped(text, screen == Screen.HELP ? "aj.help.title" : "aj.admin.title"));
        out.print("</title>");
        // Confluence 의 sitemesh 가 이 응답을 관리 콘솔 껍데기로 감싼다. 장식이 실패해도
        // 같은 마크업이 평범한 페이지로 남으므로 화면이 깨지지는 않는다.
        out.print("<meta name=\"decorator\" content=\"atl.admin\">");
        out.print("<meta name=\"admin.active.section\" content=\"system.admin/configuration\">");
        out.print("<meta name=\"admin.active.tab\" content=\"attachment-janitor-admin-link\">");
        out.print("<style>");
        out.print(StaticAssets.read("/css/attachment-janitor.css"));
        out.print("</style></head>");
    }

    /**
     * 언어 토글 · 화면 탭. 모든 화면이 같은 머리를 쓴다.
     *
     * <p>제목은 <b>여기서 찍지 않는다.</b> sitemesh 의 {@code atl.admin} 장식이
     * {@code <title>} 을 관리 화면 제목으로 이미 그린다 — 같은 문구를 한 번 더 찍으면
     * 화면 맨 위에 제목이 두 번 나온다.
     */
    private void header(PrintWriter out, LocaleText text, String base, Screen screen,
                        HttpServletRequest request)
    {
        out.print("<div class=\"aj-head\">");

        // 언어는 서버측에서 정해진다. 링크로 공유하면 받는 사람도 같은 언어로 본다.
        out.print("<div class=\"aj-lang\">");
        for (String lang : new String[] {LocaleText.KO, LocaleText.EN})
        {
            out.print("<a href=\"");
            out.print(StaticAssets.escape(selfUrl(base, request, screen, lang)));
            out.print("\"");
            out.print(lang.equals(text.getLang()) ? " class=\"aj-lang-on\"" : "");
            out.print(">");
            out.print(LocaleText.KO.equals(lang) ? "한국어" : "English");
            out.print("</a>");
        }
        out.print("</div></div>");

        out.print("<nav class=\"aj-tabs\">");
        for (Screen tab : Screen.values())
        {
            if (!tab.isTab())
            {
                continue;
            }
            out.print("<a href=\"");
            out.print(StaticAssets.escape(tab.url(base, text)));
            out.print("\"");
            out.print(tab == screen ? " class=\"aj-tab-on\"" : "");
            out.print(">");
            out.print(escaped(text, tab.labelKey()));
            out.print("</a>");
        }
        out.print("</nav>");
    }

    /**
     * 지금 화면을 다른 언어로 다시 여는 주소.
     *
     * <p>스페이스 상세에서 언어를 바꾸면 랭킹으로 튕기지 않고 그 스페이스에 머물러야 한다.
     */
    private String selfUrl(String base, HttpServletRequest request, Screen screen, String lang)
    {
        StringBuilder url = new StringBuilder(base).append(Screen.BASE_PATH)
                .append(screen.suffix()).append("?lang=").append(lang);
        String key = request.getParameter("key");
        if (key != null && !key.isEmpty())
        {
            url.append("&key=").append(encode(key));
        }
        return url.toString();
    }

    private void body(PrintWriter out, LocaleText text, Screen screen, String base)
    {
        out.print("<p class=\"aj-lede\">");
        out.print(escaped(text, ledeKey(screen)));
        out.print("</p>");

        if (screen == Screen.SPACES || screen == Screen.SPACE_DETAIL)
        {
            out.print("<div class=\"aj-bar\">");
            if (screen == Screen.SPACES)
            {
                out.print("<button type=\"button\" class=\"aui-button aui-button-primary\""
                        + " id=\"aj-scan\">");
                out.print(escaped(text, "aj.action.scan"));
                out.print("</button>");
                out.print("<button type=\"button\" class=\"aui-button\" id=\"aj-cancel\" hidden>");
                out.print(escaped(text, "aj.action.cancel"));
                out.print("</button>");
            }
            out.print("<a class=\"aui-button aui-button-link\" id=\"aj-csv\" href=\"#\">");
            out.print(escaped(text, "aj.action.csv"));
            out.print("</a>");
            out.print("<span class=\"aj-asof\" id=\"aj-asof\"></span>");
            out.print("</div>");
        }

        out.print("<div id=\"aj-banner\"></div>");
        if (screen == Screen.SPACES || screen == Screen.SPACE_DETAIL)
        {
            out.print("<div id=\"aj-summary\" class=\"aj-summary\"></div>");
        }
        if (screen == Screen.SPACE_DETAIL || screen == Screen.DUPLICATES)
        {
            out.print("<div id=\"aj-filters\" class=\"aj-filters\"></div>");
        }
        out.print("<div id=\"aj-table\"><p class=\"aj-muted\">");
        out.print(escaped(text, "aj.state.loading"));
        out.print("</p></div>");

        if (screen != Screen.SETTINGS)
        {
            out.print("<p class=\"aj-foot\">");
            out.print(escaped(text, "aj.admin.limits"));
            out.print(" <a href=\"");
            // base 를 빼면 컨텍스트 경로가 있는 인스턴스에서 링크가 깨진다.
            out.print(StaticAssets.escape(Screen.HELP.url(base, text)));
            out.print("\">");
            out.print(escaped(text, "aj.admin.helplink"));
            out.print("</a></p>");
        }
    }

    private static String ledeKey(Screen screen)
    {
        switch (screen)
        {
            case DUPLICATES: return "aj.duplicates.lede";
            case SETTINGS: return "aj.settings.lede";
            case SPACE_DETAIL: return "aj.detail.lede";
            default: return "aj.admin.lede";
        }
    }

    /**
     * 브라우저가 그릴 모든 문구를 JSON 으로 넘긴다.
     *
     * <p>키를 손으로 나열하지 않고 접두사로 통째로 가져온다. 나열하면 문구를 추가할 때마다
     * 한쪽을 빼먹어 화면에 키가 그대로 나오는 사고가 난다(형제 앱의 교훈). 설명서
     * 문구({@code aj.help.})는 서버가 이미 그렸으므로 뺀다 — 페이로드만 커진다.
     */
    private String strings(LocaleText text)
    {
        Json json = Json.object();
        Map<String, String> all = text.translationsForPrefix("aj.");
        for (Map.Entry<String, String> entry : all.entrySet())
        {
            if (!entry.getKey().startsWith("aj.help."))
            {
                json.put(entry.getKey(), entry.getValue());
            }
        }
        return json.end();
    }

    /**
     * 관리자 보안 세션이 필요하면 Confluence 자신의 인증 화면으로 보낸다.
     *
     * @return 응답을 리다이렉트했으면 false — 더 쓰면 안 된다
     */
    private boolean enterWebSudo(HttpServletRequest request, HttpServletResponse response,
                                 String base, Screen screen) throws IOException
    {
        if (!webSudoManager.isEnabled()
                || webSudoManager.hasValidSession(request.getSession(false)))
        {
            // 요청을 표시해 두면 이 화면을 보는 동안 보안 세션이 연장된다.
            // Confluence 자기 관리 화면과 같은 동작이다.
            webSudoManager.markWebSudoRequest(request);
            return true;
        }
        String destination = Screen.BASE_PATH + screen.suffix();
        String query = request.getQueryString();
        if (query != null && !query.isEmpty())
        {
            destination = destination + "?" + query;
        }
        response.sendRedirect(base + "/authenticate.action?destination=" + encode(destination));
        return false;
    }

    private static String encode(String value)
    {
        try
        {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException error)
        {
            // UTF-8 은 모든 JVM 에 있다. 여기 오면 그게 더 큰 문제다.
            return value;
        }
    }

    private static String escaped(LocaleText text, String key)
    {
        return StaticAssets.escape(text.text(key));
    }
}
