package co.bskim.confluence.attachjanitor.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.Table;

import java.util.Date;

/**
 * 구버전 정리를 <b>한 첨부에 대해</b> 한 번 실행한 기록.
 *
 * <p>이 앱에서 유일하게 무언가를 지우는 기능의 흔적이다. 되돌릴 수 없는 삭제라서
 * (실측 30번) "누가 언제 무엇을 지웠나"에 답하지 못하면 안 된다.
 *
 * <p><b>버전 하나에 한 행이 아니라 첨부 하나에 한 행이다.</b> 한 번 누르면 버전 340개가
 * 지워지는데 그걸 340행으로 남기면 로그가 아니라 잡음이 된다. 대신 지운 버전 번호를
 * 문자열로 함께 적는다 — 버전 번호는 재부여되지 않으므로(실측 32번) 나중에 봐도 뜻이
 * 통한다.
 *
 * <p>스캔 결과와 달리 <b>이 표는 보관 정책이 지우지 않는다.</b> 실행 기록이 스캔 세대
 * 정리에 휩쓸려 사라지면 감사 기록이 아니다.
 */
@Table("ACTION_LOG")
public interface AjActionLog extends Entity
{
    /** 한 번의 "삭제" 클릭. 같은 배치의 행들이 이 값을 공유한다. */
    @Indexed
    String getBatchId();

    void setBatchId(String batchId);

    Date getAt();

    void setAt(Date at);

    /** 실행한 관리자. 스캔의 {@code startedBy} 와 같은 규칙이다. */
    String getRequestedBy();

    void setRequestedBy(String requestedBy);

    String getSpaceKey();

    void setSpaceKey(String spaceKey);

    long getAttachmentId();

    void setAttachmentId(long attachmentId);

    String getFileName();

    void setFileName(String fileName);

    long getContainerId();

    void setContainerId(long containerId);

    /** 남기기로 한 총 버전 수(현재 포함). 화면의 "최근 N개 유지" 값이다. */
    int getKeepVersions();

    void setKeepVersions(int keepVersions);

    int getVersionsRemoved();

    void setVersionsRemoved(int versionsRemoved);

    long getBytesRemoved();

    void setBytesRemoved(long bytesRemoved);

    /** 지운 버전 번호. 예: {@code "1,2,3,4,5"}. 255자를 넘으면 잘린다. */
    String getVersionNumbers();

    void setVersionNumbers(String versionNumbers);

    /**
     * {@code DONE} · {@code SKIPPED} · {@code FAILED}.
     *
     * <p>건너뛴 것도 남긴다. 미리보기와 실행 사이에 누가 새 버전을 올려 수치가 달라지면
     * 그 첨부만 건드리지 않고 넘어가는데(다른 499개까지 막을 이유가 없다), 흔적이 없으면
     * 관리자는 "왜 하나가 안 지워졌지"에 답을 못 찾는다.
     */
    String getOutcome();

    void setOutcome(String outcome);

    /** 건너뛰거나 실패한 이유. 성공이면 비어 있다. */
    String getDetail();

    void setDetail(String detail);
}
