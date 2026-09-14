package co.bskim.confluence.attachjanitor.scan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 로고 파일명을 <b>추측하지 않는다</b>. Confluence 가 주는 내려받기 경로에서 떼어 쓴다.
 * 그 떼어내기가 틀리면 로고가 다시 [고아] 로 찍히므로 여기서 잡는다.
 */
public class BodyWalkerTest
{
    @Test
    public void takesTheFileNameFromTheDownloadPath()
    {
        // 실측한 실제 모양(실측 27번): 로고 첨부의 파일명이 스페이스 키다.
        assertEquals("AJA", BodyWalker.logoFileName(
                "/download/attachments/3670080/AJA?version=1&modificationDate=17893&api=v2"));
    }

    @Test
    public void handlesAContextPathAndNoQuery()
    {
        assertEquals("logo.png",
                BodyWalker.logoFileName("/confluence/download/attachments/12/logo.png"));
    }

    @Test
    public void decodesPercentEscapes()
    {
        // 한글 이름 로고. 인코딩된 채로 두면 첨부 이름과 안 맞는다.
        assertEquals("한글 로고.png", BodyWalker.logoFileName(
                "/download/attachments/12/%ED%95%9C%EA%B8%80%20%EB%A1%9C%EA%B3%A0.png?api=v2"));
    }

    @Test
    public void survivesNonsense()
    {
        assertNull(BodyWalker.logoFileName(null));
        assertNull(BodyWalker.logoFileName(""));
        assertNull(BodyWalker.logoFileName("/download/attachments/12/"));
        assertNull(BodyWalker.logoFileName("?only=query"));
    }
}
