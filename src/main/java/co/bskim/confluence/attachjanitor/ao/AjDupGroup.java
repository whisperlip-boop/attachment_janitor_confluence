package co.bskim.confluence.attachjanitor.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.Table;

/**
 * 같은 내용으로 판정된 파일 묶음 하나.
 *
 * <p>스캔이 끝날 때 계산해 저장한다. 화면을 열 때마다 첨부 전체를 읽어 묶으면 첨부가
 * 십만 개인 인스턴스에서 화면이 열리지 않는다.
 */
@Table("DUP_GROUP")
public interface AjDupGroup extends Entity
{
    @Indexed
    AjScanRun getRun();

    void setRun(AjScanRun run);

    /** {@code AjAttachment#getContentHash()} 와 같은 값. 상세를 펼칠 때 이걸로 찾는다. */
    @Indexed
    String getContentHash();

    void setContentHash(String contentHash);

    int getFileCount();

    void setFileCount(int fileCount);

    /** 한 벌의 크기. 묶음 안의 파일은 크기가 같다. */
    long getUnitBytes();

    void setUnitBytes(long unitBytes);

    /** {@code unitBytes × (fileCount - 1)}. 관리자가 실제로 보고 싶은 숫자다. */
    long getReclaimableBytes();

    void setReclaimableBytes(long reclaimableBytes);

    /**
     * 전체 해시로 확인했으면 true, 약식 해시면 false.
     * 약식일 때 화면은 "중복"이 아니라 "중복 후보"라고 적는다 — 확인하지 않은 것을
     * 확인했다고 말하지 않는다.
     */
    boolean isExact();

    void setExact(boolean exact);
}
