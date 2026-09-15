package co.bskim.confluence.attachjanitor.servlet;

import co.bskim.confluence.attachjanitor.model.Badge;
import co.bskim.confluence.attachjanitor.model.Label;
import co.bskim.confluence.attachjanitor.model.RefKind;
import co.bskim.confluence.attachjanitor.settings.Settings;
import co.bskim.confluence.attachjanitor.settings.SettingsStore;
import co.bskim.confluence.attachjanitor.web.LocaleText;
import co.bskim.confluence.attachjanitor.web.Screen;
import co.bskim.confluence.attachjanitor.web.StaticAssets;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.PrintWriter;

/**
 * 사용 설명서. 화면에 나오는 라벨과 배지가 각각 무슨 뜻이고 무엇을 하면 되는지 적는다.
 *
 * <p><b>두 가지 규칙이 있다.</b>
 * <ol>
 *   <li><b>라벨 표와 배지 표는 열거형을 순회해 만든다.</b> {@link Label} /
 *       {@link Badge} 에 값을 더하거나 빼면 설명서 행이 자동으로 따라오고 i18n 키만
 *       채우면 된다. 목차와 표를 손으로 적어 두면 동작을 고칠 때마다 어긋나는데,
 *       <b>설명서와 실제 동작이 다른 것은 설명서가 없는 것보다 나쁘다.</b></li>
 *   <li><b>임계값은 설정에서 읽어 그대로 적는다.</b> "20MB 이상" 이라고 글로 박아 두면
 *       관리자가 설정을 바꾼 순간 설명서가 거짓말이 된다.</li>
 * </ol>
 *
 * <p>본문 문구는 전부 i18n 번들({@code aj.help.*})에 있고 여기에는 구조만 있다.
 */
@Named
public class HelpPage
{
    private final SettingsStore settings;

    @Inject
    public HelpPage(SettingsStore settings)
    {
        this.settings = settings;
    }

    public void render(PrintWriter out, LocaleText text, String base)
    {
        Settings current = settings.load();

        out.print("<div class=\"aj-help-layout\"><div class=\"aj-help-body\">");

        section(out, text, "aj.help.what");
        paragraph(out, text, "aj.help.what.body");
        paragraph(out, text, "aj.help.what.readonly");

        // 색은 범례 없이 두면 정보가 아니라 장식이다. 화면에도 범례를 두고 여기에도 적는다.
        section(out, text, "aj.help.bar");
        paragraph(out, text, "aj.help.bar.body");
        paragraph(out, text, "aj.help.bar.colours");
        out.print("<dl class=\"aj-help-dl\">");
        for (String[] pair : new String[][] {
                {"aj.bar.latest", "aj.help.bar.latest"},
                {"aj.bar.old", "aj.help.bar.old"},
                {"aj.bar.rest", "aj.help.bar.rest"}})
        {
            out.print("<dt>");
            out.print(escape(text, pair[0]));
            out.print("</dt><dd>");
            out.print(escape(text, pair[1]));
            out.print("</dd>");
        }
        out.print("</dl>");

        section(out, text, "aj.help.labels");
        paragraph(out, text, "aj.help.labels.intro");
        out.print("<table class=\"aui aj-help-table\"><thead><tr><th class=\"aj-w1\">");
        out.print(escape(text, "aj.help.col.label"));
        out.print("</th><th class=\"aj-w2\">");
        out.print(escape(text, "aj.help.col.condition"));
        out.print("</th><th>");
        out.print(escape(text, "aj.help.col.action"));
        out.print("</th></tr></thead><tbody>");
        for (Label label : Label.values())
        {
            out.print("<tr><td><span class=\"aj-label aj-label-");
            out.print(label.code());
            out.print("\">");
            out.print(escape(text, label.i18nKey("name")));
            out.print("</span></td><td>");
            out.print(escape(text, label.i18nKey("cond")));
            out.print("</td><td>");
            out.print(escape(text, label.i18nKey("act")));
            out.print("</td></tr>");
        }
        out.print("</tbody></table>");
        paragraph(out, text, "aj.help.labels.confusion");
        paragraph(out, text, "aj.help.labels.notrash");

        section(out, text, "aj.help.badges");
        paragraph(out, text, "aj.help.badges.intro");
        out.print("<table class=\"aui aj-help-table\"><thead><tr><th class=\"aj-w1\">");
        out.print(escape(text, "aj.help.col.badge"));
        out.print("</th><th class=\"aj-w2\">");
        out.print(escape(text, "aj.help.col.threshold"));
        out.print("</th><th>");
        out.print(escape(text, "aj.help.col.meaning"));
        out.print("</th></tr></thead><tbody>");
        for (Badge badge : Badge.values())
        {
            out.print("<tr><td><span class=\"aj-badge aj-badge-");
            out.print(badge.code());
            out.print("\">");
            out.print(escape(text, badge.i18nKey("name")));
            out.print("</span></td><td>");
            // 글로 박지 않고 지금 설정값을 그대로 적는다.
            out.print(StaticAssets.escape(current.describeThreshold(badge, text)));
            out.print("</td><td>");
            out.print(escape(text, badge.i18nKey("desc")));
            out.print("</td></tr>");
        }
        out.print("</tbody></table>");
        out.print("<p class=\"aj-muted\">");
        out.print(escape(text, "aj.help.badges.settings"));
        out.print(" <a href=\"");
        out.print(StaticAssets.escape(Screen.SETTINGS.url(base, text)));
        out.print("\">");
        out.print(escape(text, "aj.nav.settings"));
        out.print("</a></p>");

        section(out, text, "aj.help.refs");
        paragraph(out, text, "aj.help.refs.intro");
        out.print("<dl>");
        for (RefKind kind : RefKind.values())
        {
            out.print("<dt>");
            out.print(escape(text, kind.i18nKey()));
            out.print("</dt><dd>");
            out.print(escape(text, "aj.help.ref.desc." + kind.code()));
            out.print("</dd>");
        }
        out.print("</dl>");

        section(out, text, "aj.help.duplicates");
        paragraph(out, text, "aj.help.duplicates.body");
        paragraph(out, text, "aj.help.duplicates.mode");
        // 칸 이름만으로 뜻이 안 통하는 칸들. 화면에서는 머리의 ? 로도 뜨지만, 툴팁은
        // 인쇄하거나 링크로 공유하면 사라진다. 설명서에는 글로 남는다.
        paragraph(out, text, "aj.help.duplicates.columns");
        out.print("<dl class=\"aj-help-dl\">");
        for (String[] pair : new String[][] {
                {"aj.col.dupfiles", "aj.col.dupfiles.tip"},
                {"aj.col.unit", "aj.col.unit.tip"},
                {"aj.col.reclaim", "aj.col.reclaim.tip"},
                {"aj.col.certainty", "aj.col.certainty.tip"}})
        {
            out.print("<dt>");
            out.print(escape(text, pair[0]));
            out.print("</dt><dd>");
            out.print(escape(text, pair[1]));
            out.print("</dd>");
        }
        out.print("</dl>");

        // 이 앱에서 유일하게 되돌릴 수 없는 기능이다. 설명서에서 가장 길게 적는다 —
        // 짧게 적으면 관리자가 무엇을 잃는지 모르고 누른다.
        section(out, text, "aj.help.cleanup");
        paragraph(out, text, "aj.help.cleanup.why");
        paragraph(out, text, "aj.help.cleanup.safe");
        paragraph(out, text, "aj.help.cleanup.keep");
        paragraph(out, text, "aj.help.cleanup.flow");
        out.print("<ul>");
        for (String key : new String[] {"aj.help.cleanup.rule.oldonly",
                                        "aj.help.cleanup.rule.nolabel",
                                        "aj.help.cleanup.rule.preview",
                                        "aj.help.cleanup.rule.recheck",
                                        "aj.help.cleanup.rule.background",
                                        "aj.help.cleanup.rule.log",
                                        "aj.help.cleanup.rule.websudo"})
        {
            out.print("<li>");
            out.print(escape(text, key));
            out.print("</li>");
        }
        out.print("</ul>");
        paragraph(out, text, "aj.help.cleanup.caveat");

        section(out, text, "aj.help.limits");
        out.print("<ul>");
        for (String key : new String[] {"aj.help.limit.notdelete", "aj.help.limit.sizes",
                                        "aj.help.limit.deleted", "aj.help.limit.history",
                                        "aj.help.limit.templates", "aj.help.limit.external",
                                        "aj.help.limit.snapshot",
                                        "aj.help.limit.oneatatime"})
        {
            out.print("<li>");
            out.print(escape(text, key));
            out.print("</li>");
        }
        out.print("</ul>");

        out.print("</div><div class=\"aj-help-toc\"><h3>");
        out.print(escape(text, "aj.help.contents"));
        out.print("</h3><ul id=\"aj-toc\"></ul></div></div>");
    }

    /** 제목에 id 를 붙여 둔다. 우측 색인이 이걸 집어 목차를 만든다. */
    private static void section(PrintWriter out, LocaleText text, String key)
    {
        out.print("<h2 id=\"");
        out.print(key.replace('.', '-'));
        out.print("\">");
        out.print(escape(text, key));
        out.print("</h2>");
    }

    private static void paragraph(PrintWriter out, LocaleText text, String key)
    {
        out.print("<p>");
        out.print(escape(text, key));
        out.print("</p>");
    }

    private static String escape(LocaleText text, String key)
    {
        return StaticAssets.escape(text.text(key));
    }
}
