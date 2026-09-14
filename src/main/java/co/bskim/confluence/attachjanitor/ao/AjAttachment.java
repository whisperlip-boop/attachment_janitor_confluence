package co.bskim.confluence.attachjanitor.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.Table;

import java.util.Date;

/**
 * 스캔이 본 첨부 한 개. 상세 화면과 CSV 가 읽는 행이다.
 *
 * <p><b>이 표는 마지막으로 완료된 스캔 한 벌만 보관한다.</b> {@link AjSpaceStat} 은
 * 추세를 보려고 여러 세대를 남기지만, 첨부 행은 인스턴스 첨부 수만큼 생기므로 세대를
 * 쌓으면 그것만으로 DB 가 커진다 — 용량을 줄이려고 만든 앱이 용량을 먹는 꼴이 된다.
 * 지우는 시점은 <b>새 스캔이 커밋된 뒤</b>다({@code ScanStore}). 먼저 지우면 스캔이
 * 실패했을 때 볼 것이 아무것도 남지 않는다.
 */
@Table("ATT_ROW")
public interface AjAttachment extends Entity
{
    @Indexed
    AjScanRun getRun();

    void setRun(AjScanRun run);

    /** Confluence 의 첨부 id. 화면에서 링크를 만들 때 쓴다. */
    long getAttachmentId();

    void setAttachmentId(long attachmentId);

    String getFileName();

    void setFileName(String fileName);

    /** 소문자 확장자. 중복 1단계 후보 묶기와 화면 필터에 쓴다. */
    String getExtension();

    void setExtension(String extension);

    @Indexed
    String getSpaceKey();

    void setSpaceKey(String spaceKey);

    long getContainerId();

    void setContainerId(long containerId);

    String getContainerTitle();

    void setContainerTitle(String containerTitle);

    /** PAGE / BLOGPOST / COMMENT / USERINFO … 열거하지 않고 Confluence 가 준 값을 그대로 둔다. */
    String getContainerType();

    void setContainerType(String containerType);

    String getContainerStatus();

    void setContainerStatus(String containerStatus);

    long getLatestBytes();

    void setLatestBytes(long latestBytes);

    int getVersionCount();

    void setVersionCount(int versionCount);

    long getOldVersionBytes();

    void setOldVersionBytes(long oldVersionBytes);

    int getOldVersionCount();

    void setOldVersionCount(int oldVersionCount);

    Date getLastModified();

    void setLastModified(Date lastModified);

    String getMediaType();

    void setMediaType(String mediaType);

    /** 중복 판정용 해시. 후보 그룹에 들지 않은 첨부는 비어 있다. */
    @Indexed
    String getContentHash();

    void setContentHash(String contentHash);

    /** {@code Label#code()}. 문자열로 담는 이유는 열거형 순서가 바뀌어도 안전하기 때문이다. */
    String getLabel();

    void setLabel(String label);

    /** {@code Badge#bit()} 의 합. */
    int getBadges();

    void setBadges(int badges);

    int getRefCount();

    void setRefCount(int refCount);
}
