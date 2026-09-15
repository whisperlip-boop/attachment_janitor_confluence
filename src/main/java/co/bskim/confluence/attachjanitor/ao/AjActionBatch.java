package co.bskim.confluence.attachjanitor.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.Table;

import java.util.Date;

/**
 * 구버전 정리를 <b>한 번 실행한</b> 요약. {@link AjActionLog} 한 묶음에 이 행 하나다.
 *
 * <p>왜 요약 행을 따로 두나. 파일별 행만 있으면 250개짜리 실행 한 번이 250행이라
 * 기록을 읽을 때 그 배치 하나가 화면을 통째로 차지하고 이전 실행들이 뒤로 밀린다
 * (실측 35번 부수 관찰). 목록은 이 표로 보고, 궁금한 배치만 펼쳐 파일별 행을 본다.
 *
 * <p><b>파일별 행을 이 요약으로 대체하지 않는다.</b> "어느 파일의 몇 번 버전이
 * 사라졌나"에 답하는 것이 그 표의 존재 이유이고, 요약만 남기면 감사 기록이 아니라
 * 통계가 된다.
 *
 * <p>끝맺음이 {@code DONE} 이 아닌 실행도 남긴다. 취소한 실행에도 이미 지운 파일이
 * 있고, 흔적이 없으면 그 삭제는 아무도 한 적 없는 일이 된다.
 *
 * <p><b>보관 기간이 있다.</b> 설정의 "정리 기록 보관 일수"(기본 365)를 넘긴 행은 다음
 * 정리 때 이 표와 파일별 표에서 함께 지워진다. 감사 기록이므로 기본값을 길게 두고
 * 하한도 낮게 두지 않는다 — 며칠짜리 창은 감사 기록이 아니라 최근 활동 목록이다.
 */
@Table("ACTION_BATCH")
public interface AjActionBatch extends Entity
{
    /** {@link AjActionLog#getBatchId()} 와 같은 값. 파일별 행을 이걸로 찾는다. */
    @Indexed
    String getBatchId();

    void setBatchId(String batchId);

    /** 보관 정책이 이 칸으로 지운다. 그래서 인덱스가 있다. */
    @Indexed
    Date getAt();

    void setAt(Date at);

    Date getFinishedAt();

    void setFinishedAt(Date finishedAt);

    String getRequestedBy();

    void setRequestedBy(String requestedBy);

    /** 남기기로 한 총 버전 수(현재 포함). 화면의 "최근 N개 유지" 값이다. */
    int getKeepVersions();

    void setKeepVersions(int keepVersions);

    int getFilesDone();

    void setFilesDone(int filesDone);

    int getFilesSkipped();

    void setFilesSkipped(int filesSkipped);

    int getFilesFailed();

    void setFilesFailed(int filesFailed);

    int getVersionsRemoved();

    void setVersionsRemoved(int versionsRemoved);

    long getBytesRemoved();

    void setBytesRemoved(long bytesRemoved);

    /** 건드린 스페이스 키. 예: {@code "AJA,AJB"}. 255자를 넘으면 잘린다. */
    String getSpaceKeys();

    void setSpaceKeys(String spaceKeys);

    /** {@code DONE} · {@code CANCELLED} · {@code FAILED}. */
    String getOutcome();

    void setOutcome(String outcome);

    /** 실패했거나 멈춘 이유. 정상이면 비어 있다. */
    String getDetail();

    void setDetail(String detail);
}
