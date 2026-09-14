package co.bskim.confluence.attachjanitor.model;

import java.text.Normalizer;

/**
 * 본문 하나가 가리키는 첨부 하나.
 *
 * <p>{@link #targetTitle} 이 비어 있으면 <b>본문 자신의 컨테이너</b>에 붙은 첨부다.
 * 값이 있으면 다른 페이지의 첨부를 가리키는 교차 참조다. {@link #targetSpaceKey} 가
 * 비어 있으면 참조한 본문과 같은 스페이스로 해석한다(실측 V5).
 */
public final class AttachmentRef
{
    public final String fileName;
    public final String targetSpaceKey;
    public final String targetTitle;

    public AttachmentRef(String fileName, String targetSpaceKey, String targetTitle)
    {
        this.fileName = normalise(fileName);
        this.targetSpaceKey = trimToNull(targetSpaceKey);
        this.targetTitle = trimToNull(targetTitle);
    }

    public boolean isCrossReference()
    {
        return targetTitle != null;
    }

    /**
     * 파일명 비교의 기준.
     *
     * <p>유니코드 정규화를 NFC 로 맞춘다 — 한글 파일명은 자모 분리(NFD)로 올라오는 경로가
     * 있어서 바이트로 비교하면 눈에 같아 보이는 이름이 안 맞는다.
     * <b>대소문자는 구분한다.</b> Confluence 는 같은 페이지에 {@code A.png} 와
     * {@code a.png} 를 동시에 둘 수 있으므로, 소문자로 낮춰 비교하면 엉뚱한 첨부를
     * [활성] 으로 찍는다.
     */
    public static String normalise(String name)
    {
        if (name == null)
        {
            return "";
        }
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFC);
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString()
    {
        return (targetSpaceKey == null ? "" : targetSpaceKey + ":")
                + (targetTitle == null ? "" : targetTitle + "^") + fileName;
    }
}
