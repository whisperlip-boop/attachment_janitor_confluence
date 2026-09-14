package co.bskim.confluence.attachjanitor.ao;

import net.java.ao.Entity;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

import java.util.Date;

/**
 * One scan of the whole instance.
 *
 * <p>A run is kept even when it failed. A screen that silently falls back to the previous
 * successful run shows an administrator a weeks-old snapshot as if it were current; the sibling
 * app learned that the hard way, so failure is a stored state here rather than an absence.</p>
 */
@Table("SCAN_RUN")
public interface AjScanRun extends Entity
{
    /** RUNNING / DONE / FAILED / CANCELLED. */
    String getStatus();

    void setStatus(String status);

    Date getStartedAt();

    void setStartedAt(Date startedAt);

    Date getFinishedAt();

    void setFinishedAt(Date finishedAt);

    /** Attachments counted, latest version only. */
    int getFileCount();

    void setFileCount(int fileCount);

    long getTotalBytes();

    void setTotalBytes(long totalBytes);

    long getLatestBytes();

    void setLatestBytes(long latestBytes);

    long getOldVersionBytes();

    void setOldVersionBytes(long oldVersionBytes);

    int getOldVersionCount();

    void setOldVersionCount(int oldVersionCount);

    /** Attachments the scan could not read. Reported, never silently dropped. */
    int getSkippedCount();

    void setSkippedCount(int skippedCount);

    @StringLength(StringLength.UNLIMITED)
    String getErrorMessage();

    void setErrorMessage(String errorMessage);

    /** 읽지 못한 본문 수. 0 이 아니면 라벨이 실제보다 "고아" 쪽으로 기운다. */
    int getParseFailures();

    void setParseFailures(int parseFailures);

    /** 참조를 찾으려고 읽은 본문 수(현재 본문 + 이력 스캔을 켰다면 과거 본문). */
    int getBodiesScanned();

    void setBodiesScanned(int bodiesScanned);

    /** 본문에서 찾은 첨부 참조 총 건수. */
    int getRefCount();

    void setRefCount(int refCount);

    /**
     * 대상 페이지를 찾지 못한 참조 수. 페이지 이름이 바뀌었거나 없는 페이지를 가리킨다.
     * 이 값이 크면 "고아"가 실제보다 많이 나온다 — 화면에 그대로 낸다.
     */
    int getUnresolvedRefs();

    void setUnresolvedRefs(int unresolvedRefs);

    int getOrphanCount();

    void setOrphanCount(int orphanCount);

    long getOrphanBytes();

    void setOrphanBytes(long orphanBytes);

    int getDuplicateGroups();

    void setDuplicateGroups(int duplicateGroups);

    long getDuplicateReclaimable();

    void setDuplicateReclaimable(long duplicateReclaimable);

    /** 중복 판정을 위해 실제로 읽은 바이트 수. 예산 관리의 근거다. */
    long getHashedBytes();

    void setHashedBytes(long hashedBytes);

    /** 해시 예산에 걸려 검사하지 못한 후보가 있는가. */
    boolean isHashBudgetHit();

    void setHashBudgetHit(boolean hashBudgetHit);

    /** 과거 버전 본문까지 검사했는가. 아니면 [고아] 는 "이력 미검사"로 표기된다. */
    boolean isHistoryScanned();

    void setHistoryScanned(boolean historyScanned);

    /** 이 실행의 첨부 상세 행이 아직 남아 있는가. 최신 완료 실행 하나만 true 다. */
    boolean isHasDetail();

    void setHasDetail(boolean hasDetail);

    String getStartedBy();

    void setStartedBy(String startedBy);
}
