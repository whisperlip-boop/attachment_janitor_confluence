package co.bskim.confluence.attachjanitor.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.Table;

/**
 * "이 첨부가 저기서 쓰인다" 한 건.
 *
 * <p>라벨의 근거다. 근거를 펼쳐 볼 수 없는 라벨은 신뢰받지 못한다 — [위험] 이라고만
 * 적혀 있으면 관리자는 어느 페이지가 깨질지 몰라 아무 조치도 못 한다.
 *
 * <p>한 첨부가 받는 근거는 {@code MAX_PER_ATTACHMENT} 건까지만 저장한다. 표지 이미지
 * 하나가 수천 페이지에서 참조되는 경우가 실제로 있고, 그걸 전부 적어 봐야 화면에서
 * 읽히지도 않으면서 행만 폭증한다. 잘린 사실은 첨부 행의 {@code refCount} 로 드러난다.
 */
@Table("REF_HIT")
public interface AjRefHit extends Entity
{
    int MAX_PER_ATTACHMENT = 50;

    @Indexed
    AjAttachment getRow();

    void setRow(AjAttachment row);

    /** {@code RefKind#code()}. */
    String getKind();

    void setKind(String kind);

    long getContentId();

    void setContentId(long contentId);

    String getTitle();

    void setTitle(String title);

    String getSpaceKey();

    void setSpaceKey(String spaceKey);

    /** 링크를 만들 때 필요하다. PAGE 와 BLOGPOST 는 주소 모양이 다르다. */
    String getContentType();

    void setContentType(String contentType);
}
