package co.bskim.confluence.attachjanitor.analyze;

import co.bskim.confluence.attachjanitor.model.AttachmentRef;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 여기 있는 XML 은 전부 <b>docker 7.8.1 인스턴스에서 그대로 꺼낸 것</b>이다.
 * 손으로 지어낸 문법을 넣지 않는다 — 그러면 우리가 쓴 것을 우리가 읽는 시험이 된다
 * (기획서 0장 V5 의 원칙).
 */
public class ReferenceScannerTest
{
    private static List<String> names(ReferenceScanner.Result result)
    {
        List<String> out = new ArrayList<String>();
        for (AttachmentRef ref : result.refs)
        {
            out.add(ref.toString());
        }
        return out;
    }

    @Test
    public void imageAndLink()
    {
        String body = "<p><ac:image><ri:attachment ri:filename=\"a.png\" /></ac:image><br/>\n"
                + "<ac:link><ri:attachment ri:filename=\"a.pdf\" /></ac:link></p>";
        ReferenceScanner.Result result = ReferenceScanner.scanStorage(body);
        assertTrue(result.parsed);
        assertEquals("[a.png, a.pdf]", names(result).toString());
    }

    @Test
    public void macrosAreNotEnumerated()
    {
        // viewfile / multimedia / viewpdf 는 전부 같은 ri:attachment 를 쓴다(실측 V5).
        String body = "<ac:structured-macro ac:name=\"viewfile\" ac:schema-version=\"1\">"
                + "<ac:parameter ac:name=\"name\"><ri:attachment ri:filename=\"spec.xlsx\" />"
                + "</ac:parameter></ac:structured-macro>"
                + "<ac:structured-macro ac:name=\"multimedia\">"
                + "<ac:parameter ac:name=\"name\"><ri:attachment ri:filename=\"v.mp4\" />"
                + "</ac:parameter></ac:structured-macro>";
        assertEquals("[spec.xlsx, v.mp4]", names(ReferenceScanner.scanStorage(body)).toString());
    }

    @Test
    public void galleryParametersArePlainStrings()
    {
        String body = "<ac:structured-macro ac:name=\"gallery\" ac:schema-version=\"1\">"
                + "<ac:parameter ac:name=\"include\">a.png,b.png</ac:parameter>"
                + "<ac:parameter ac:name=\"exclude\">c.png</ac:parameter>"
                + "</ac:structured-macro>";
        assertEquals("[a.png, b.png, c.png]",
                names(ReferenceScanner.scanStorage(body)).toString());
    }

    @Test
    public void crossSpaceReferenceCarriesItsTarget()
    {
        String body = "<p><ac:image><ri:attachment ri:filename=\"shared.png\">"
                + "<ri:page ri:space-key=\"AJA\" ri:content-title=\"cross\" />"
                + "</ri:attachment></ac:image></p>";
        ReferenceScanner.Result result = ReferenceScanner.scanStorage(body);
        assertEquals("[AJA:cross^shared.png]", names(result).toString());
        assertTrue(result.refs.get(0).isCrossReference());
    }

    @Test
    public void htmlEntitiesDoNotKillTheParse()
    {
        // 실측: 이 인스턴스 본문 144개 중 18개에 &nbsp; 가 있었다. 이게 실패하면
        // "분석 실패"가 소수가 아니라 전부가 된다.
        String body = "<p>hard&nbsp;space and an em&mdash;dash and an &amp; ampersand.</p>"
                + "<ac:layout><ac:layout-section ac:type=\"two_equal\"><ac:layout-cell>"
                + "<ac:structured-macro ac:name=\"viewfile\" ac:schema-version=\"1\">"
                + "<ac:parameter ac:name=\"name\">"
                + "<ri:attachment ri:filename=\"nested.png\" /></ac:parameter>"
                + "</ac:structured-macro>"
                + "</ac:layout-cell><ac:layout-cell><p>&nbsp;</p></ac:layout-cell>"
                + "</ac:layout-section></ac:layout>";
        ReferenceScanner.Result result = ReferenceScanner.scanStorage(body);
        assertTrue("본문을 읽지 못했다", result.parsed);
        assertEquals("[nested.png]", names(result).toString());
    }

    @Test
    public void unknownEntityIsDroppedRatherThanFatal()
    {
        // 다른 앱이 넣은 엔티티는 우리 표에 없다. 그래도 파싱은 성공해야 한다.
        String body = "<p>&someplugin; text</p><ac:image>"
                + "<ri:attachment ri:filename=\"a.png\" /></ac:image>";
        ReferenceScanner.Result result = ReferenceScanner.scanStorage(body);
        assertTrue(result.parsed);
        assertEquals("[a.png]", names(result).toString());
    }

    @Test
    public void brokenBodyIsReportedNotSwallowed()
    {
        ReferenceScanner.Result result = ReferenceScanner.scanStorage("<p>unclosed");
        assertEquals(false, result.parsed);
        assertTrue(result.refs.isEmpty());
    }

    @Test
    public void koreanFileNameIsNormalisedToNfc()
    {
        String decomposed = "한글.png";   // 자모 분리(NFD)
        String composed = "한글.png";                             // 완성형(NFC)
        String body = "<ac:image><ri:attachment ri:filename=\"" + decomposed
                + "\" /></ac:image>";
        assertEquals(composed, ReferenceScanner.scanStorage(body).refs.get(0).fileName);
    }

    @Test
    public void spaceDescriptionsAreWikiNotXml()
    {
        // 실측 10번: SPACEDESCRIPTION 의 bodytypeid 는 0(WIKI) 이다.
        ReferenceScanner.Result result =
                ReferenceScanner.scanWiki("스페이스 설명에서 !shared.png! 를 참조한다.");
        assertTrue(result.parsed);
        assertEquals("[shared.png]", names(result).toString());
    }

    @Test
    public void wikiCrossSpaceAndAttachmentLink()
    {
        assertEquals("[AJA:cross^shared.png]",
                names(ReferenceScanner.scanWiki("!AJA:cross^shared.png!")).toString());
        assertEquals("[spec.xlsx]",
                names(ReferenceScanner.scanWiki("[^spec.xlsx]")).toString());
    }

    @Test
    public void wikiEmphasisIsNotAFileName()
    {
        // !강조! 는 파일이 아니다. 확장자가 없으면 무시한다.
        assertTrue(ReferenceScanner.scanWiki("!강조! 그리고 !another!").refs.isEmpty());
    }

    @Test
    public void entityTableIsOnTheClasspath()
    {
        assertTrue("html-entities.properties 가 리소스에 없다", HtmlEntities.size() > 200);
    }
}
