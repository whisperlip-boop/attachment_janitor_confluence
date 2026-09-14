package co.bskim.confluence.attachjanitor.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.Table;

/**
 * One row of the ranking screen: what one space's attachments add up to.
 *
 * <p>{@link #getSpaceKey()} is empty for attachments that belong to no space - profile
 * pictures live under {@code attachments/ver003/nonspaced} and have a null space id
 * (docs/00-환경실측.md V6). They are kept as their own row rather than dropped, because
 * dropping them would make the space rows fail to add up to the instance total and nobody
 * would be able to tell why.</p>
 */
@Table("SPACE_STAT")
public interface AjSpaceStat extends Entity
{
    @Indexed
    AjScanRun getRun();

    void setRun(AjScanRun run);

    String getSpaceKey();

    void setSpaceKey(String spaceKey);

    String getSpaceName();

    void setSpaceName(String spaceName);

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

    /** 참조를 찾지 못한 첨부. 랭킹 화면에서 "여기부터 보라"는 칸이다. */
    int getOrphanCount();

    void setOrphanCount(int orphanCount);

    long getOrphanBytes();

    void setOrphanBytes(long orphanBytes);

    /** 자기 페이지 밖에서 참조되는 첨부. 지울 때 가장 조심해야 하는 쪽이다. */
    int getRiskyCount();

    void setRiskyCount(int riskyCount);

    /** 다른 곳에도 같은 내용이 있는 첨부. */
    int getDuplicateCount();

    void setDuplicateCount(int duplicateCount);
}
