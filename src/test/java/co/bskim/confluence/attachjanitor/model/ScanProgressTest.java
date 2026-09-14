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

    private static ScanProgress running(int processed, int expected)
    {
        return new ScanProgress(ScanProgress.State.RUNNING, ScanProgress.Phase.ATTACHMENTS,
                processed, expected, new Date(), null, null);
    }
}
