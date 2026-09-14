package co.bskim.confluence.attachjanitor.model;

import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScanProgressTest
{
    @Test
    public void idleHasNoPercentage()
    {
        assertEquals(-1, ScanProgress.idle().percent());
        assertFalse(ScanProgress.idle().isRunning());
    }

    @Test
    public void runningWithoutATotalHasNoPercentage()
    {
        assertEquals(-1, running(500, 0).percent());
    }

    @Test
    public void runningReportsProgress()
    {
        assertEquals(50, running(500, 1000).percent());
        assertTrue(running(500, 1000).isRunning());
    }

    @Test
    public void neverReportsAHundredWhileStillRunning()
    {
        // Attachments can be added while the scan runs, so processed can pass the estimate.
        // A bar that sits at 100% while work continues reads as a hung scan.
        assertEquals(99, running(1000, 1000).percent());
        assertEquals(99, running(4000, 1000).percent());
    }

    @Test
    public void finishedIsAlwaysComplete()
    {
        ScanProgress done = new ScanProgress(ScanProgress.State.DONE,
                ScanProgress.Phase.NONE, 7, 0, new Date(), new Date(), null);
        assertEquals(100, done.percent());
    }

    @Test
    public void cancelledAndFailedAreNotComplete()
    {
        // 끝난 것과 완주한 것은 다르다. 취소·실패에 100% 를 찍으면 진행 막대가
        // "다 됐다"고 말하게 되고, 그 위의 실패 배너와 정면으로 어긋난다.
        assertEquals(-1, running(3, 10).ended(ScanProgress.State.CANCELLED, null).percent());
        assertEquals(-1, running(3, 10).ended(ScanProgress.State.FAILED, "boom").percent());
        assertEquals(100, running(3, 10).ended(ScanProgress.State.DONE, null).percent());
    }

    @Test
    public void bodyCountSurvivesTheEndOfTheScan()
    {
        // 본문 수는 진행 중에만 쓰는 값이지만, 끝난 스냅샷에서 사라지면 취소된 스캔이
        // 어디까지 읽었는지 알 수 없다.
        ScanProgress mid = running(2, 4).at(ScanProgress.Phase.BODIES, 2, 4, 1300);
        assertEquals(1300, mid.bodies);
        assertEquals(1300, mid.ended(ScanProgress.State.CANCELLED, null).bodies);
    }

    @Test
    public void bodyCountIsZeroOutsideTheBodyPhase()
    {
        assertEquals(0, running(500, 1000).bodies);
        assertEquals(0, running(500, 1000).at(ScanProgress.Phase.MATCH, 1, 2).bodies);
    }

    @Test
    public void bodyPhaseReportsUnknownRatherThanAFakeRatio()
    {
        // 본문 단계의 분모는 스페이스 수다. 큰 스페이스 하나를 도는 몇 분 동안
        // 0% 로 앉아 있으면 "안 돌고 있다"로 읽힌다. 모르는 것은 모른다고 낸다.
        assertEquals(-1, running(0, 6).at(ScanProgress.Phase.BODIES, 0, 6, 1800).percent());
        // 다른 단계는 그대로 비율을 낸다.
        assertEquals(50, running(0, 6).at(ScanProgress.Phase.MATCH, 500, 1000).percent());
    }

    private static ScanProgress running(int processed, int expected)
    {
        return new ScanProgress(ScanProgress.State.RUNNING, ScanProgress.Phase.ATTACHMENTS,
                processed, expected, new Date(), null, null);
    }
}
