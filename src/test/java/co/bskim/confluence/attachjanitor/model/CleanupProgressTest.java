package co.bskim.confluence.attachjanitor.model;

import co.bskim.confluence.attachjanitor.clean.CleanupService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 정리 진행률의 규칙. {@code ScanProgressTest} 와 같은 것을 지킨다 — <b>끝난 것과
 * 완주한 것은 다르다.</b> 취소·실패에 100% 를 찍으면 화면이 "다 됐다"고 말하게 된다.
 */
public class CleanupProgressTest
{
    @Test
    public void idleKnowsNothing()
    {
        CleanupProgress idle = CleanupProgress.idle();
        assertEquals(CleanupProgress.State.IDLE, idle.state);
        assertFalse(idle.isRunning());
        assertEquals(-1, idle.percent());
        assertTrue("끝나지 않은 진행률에 내역이 있으면 안 된다", idle.done.isEmpty());
    }

    @Test
    public void startedUsesTheRequestedCountAsTheDenominator()
    {
        // 미리보기가 워커 안에서 도는 동안에도 분모가 있어야 화면이 0/0 을 보지 않는다.
        CleanupProgress started = CleanupProgress.started("b1", 3, 500);
        assertTrue(started.isRunning());
        assertEquals(500, started.filesTotal);
        assertEquals(0, started.percent());
    }

    @Test
    public void previewNarrowsTheDenominatorToWhatWillActuallyBeTouched()
    {
        // 고른 500개 중 실제로 지울 것이 있는 파일이 12개면 분모는 12다.
        // 500 으로 두면 12개를 다 지우고도 화면이 2% 에 앉아 있다.
        CleanupProgress narrowed = CleanupProgress.started("b1", 3, 500).totalling(12);
        assertEquals(12, narrowed.filesTotal);
        assertEquals(0, narrowed.percent());
    }

    @Test
    public void percentCountsSkippedAndFailedAsProcessed()
    {
        // 건너뛴 것도 지나간 것이다. 안 세면 진행률이 끝까지 100 에 닿지 못한다.
        CleanupProgress at = CleanupProgress.started("b1", 3, 10).totalling(10)
                .advanced(4, 3, 1, 40, 4096L);
        assertEquals(80, at.percent());
    }

    @Test
    public void runningNeverReachesHundred()
    {
        CleanupProgress almost = CleanupProgress.started("b1", 3, 10).totalling(10)
                .advanced(10, 0, 0, 100, 1024L);
        assertTrue(almost.isRunning());
        assertEquals("아직 도는 중이면 99 가 상한이다", 99, almost.percent());
    }

    @Test
    public void onlyDoneReportsHundred()
    {
        CleanupProgress base = CleanupProgress.started("b1", 3, 10).totalling(10)
                .advanced(10, 0, 0, 100, 1024L);
        assertEquals(100, base.ended(CleanupProgress.State.DONE, null, null).percent());
        assertEquals(-1, base.ended(CleanupProgress.State.CANCELLED, null, null).percent());
        assertEquals(-1, base.ended(CleanupProgress.State.FAILED, "boom", null).percent());
    }

    @Test
    public void cancelledKeepsWhatWasAlreadyRemoved()
    {
        // 취소는 "여기서부터 더 지우지 마라"일 뿐이다. 이미 지운 것은 돌아오지 않으므로
        // 결과에 그대로 남아야 한다 — 0 으로 비우면 화면이 표를 깎지 못한다.
        List<CleanupService.Done> details = new ArrayList<CleanupService.Done>();
        CleanupProgress stopped = CleanupProgress.started("b1", 3, 100).totalling(100)
                .advanced(7, 0, 0, 21, 2048L)
                .ended(CleanupProgress.State.CANCELLED, null, details);
        assertEquals(7, stopped.filesDone);
        assertEquals(21, stopped.versionsRemoved);
        assertEquals(2048L, stopped.bytesRemoved);
        assertFalse(stopped.isRunning());
    }

    @Test
    public void terminalSnapshotCarriesTheDetails()
    {
        // 진행률과 내역을 따로 쓰면 그 틈에 도착한 폴링이 "끝났는데 내역이 없다"를 본다.
        // 그러면 표가 자기 수치를 깎지 못하고 정리한 파일에 체크박스가 남는다(실측 34번).
        List<CleanupService.Done> details = new ArrayList<CleanupService.Done>();
        CleanupProgress end = CleanupProgress.started("b1", 3, 1).totalling(1)
                .ended(CleanupProgress.State.DONE, null, details);
        assertEquals(CleanupProgress.State.DONE, end.state);
        assertEquals(details.size(), end.done.size());
    }

    @Test
    public void detailsAreCopiedNotShared()
    {
        // 워커가 계속 채우는 목록을 그대로 들고 있으면 화면이 반쯤 찬 목록을 읽는다.
        List<CleanupService.Done> details = new ArrayList<CleanupService.Done>();
        CleanupProgress end = CleanupProgress.started("b1", 3, 1)
                .ended(CleanupProgress.State.DONE, null, details);
        details.add(null);
        assertEquals(0, end.done.size());
    }
}
