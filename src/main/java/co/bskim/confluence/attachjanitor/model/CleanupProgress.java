package co.bskim.confluence.attachjanitor.model;

import co.bskim.confluence.attachjanitor.clean.CleanupService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 구버전 정리가 지금 어디까지 갔는지. 폴링하는 화면에 그대로 넘겨도 되는 스냅샷이다.
 *
 * <p>{@link ScanProgress} 와 같은 규칙을 따른다 — 취소·실패에 100% 를 찍지 않고, 총계를
 * 모르면 비율을 지어내지 않는다. 끝난 것과 완주한 것은 다르다.
 *
 * <p><b>파일별 내역을 끝난 스냅샷이 함께 들고 있다.</b> 진행률과 결과를 서로 다른 필드에
 * 따로 쓰면, 그 두 번의 쓰기 사이에 폴링이 도착한 화면은 "끝났는데 내역이 없다"를 보고
 * 표를 깎지 못한다. 그러면 정리한 파일에 체크박스가 남는 실측 34번이 그대로 돌아온다.
 * 스냅샷 하나를 통째로 바꾸는 것이 그 틈을 없애는 방법이다.
 */
public class CleanupProgress
{
    public enum State
    {
        /** 이 JVM 이 뜬 뒤로 정리한 적이 없다. */
        IDLE,
        RUNNING,
        DONE,
        FAILED,
        /** 도중에 멈췄다. <b>이미 지운 것은 돌아오지 않는다.</b> */
        CANCELLED
    }

    public final State state;
    /** 이 실행의 배치 식별자. 화면이 "내가 시작한 그 실행인가"를 가린다. */
    public final String batchId;
    public final int keep;
    /**
     * 처리할 파일 수.
     *
     * <p>시작할 때는 <b>요청받은 첨부 개수</b>다. 미리보기가 워커 안에서 도는데 그게 몇
     * 초 걸리므로, 분모를 미리보기 뒤에 세우면 그동안 화면이 0/0 을 본다. 미리보기가
     * 끝나면 실제로 지울 것이 있는 파일 수로 바뀐다 — 그래야 "480/500" 에 영원히
     * 앉아 있지 않는다.
     */
    public final int filesTotal;
    public final int filesDone;
    public final int filesSkipped;
    public final int filesFailed;
    public final int versionsRemoved;
    public final long bytesRemoved;
    public final Date startedAt;
    public final Date finishedAt;
    public final String message;
    /**
     * 파일별 내역. <b>끝난 스냅샷에만 채워진다.</b> 화면이 이걸로 표의 구버전 수치를
     * 즉시 깎는다 — 표는 저장된 스캔이라 우리가 방금 지운 것을 모른다.
     */
    public final List<CleanupService.Done> done;

    public CleanupProgress(State state, String batchId, int keep, int filesTotal, int filesDone,
                           int filesSkipped, int filesFailed, int versionsRemoved,
                           long bytesRemoved, Date startedAt, Date finishedAt, String message,
                           List<CleanupService.Done> done)
    {
        this.state = state;
        this.batchId = batchId;
        this.keep = keep;
        this.filesTotal = filesTotal;
        this.filesDone = filesDone;
        this.filesSkipped = filesSkipped;
        this.filesFailed = filesFailed;
        this.versionsRemoved = versionsRemoved;
        this.bytesRemoved = bytesRemoved;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.message = message;
        this.done = done == null
                ? new ArrayList<CleanupService.Done>()
                : new ArrayList<CleanupService.Done>(done);
    }

    public static CleanupProgress idle()
    {
        return new CleanupProgress(State.IDLE, null, 0, 0, 0, 0, 0, 0, 0L, null, null, null,
                null);
    }

    /** 잠금을 잡은 직후. 분모는 아직 요청받은 개수다. */
    public static CleanupProgress started(String batchId, int keep, int requested)
    {
        return new CleanupProgress(State.RUNNING, batchId, keep, requested, 0, 0, 0, 0, 0L,
                new Date(), null, null, null);
    }

    /** 미리보기가 끝나 진짜 분모를 알게 됐다. */
    public CleanupProgress totalling(int actualTotal)
    {
        return new CleanupProgress(State.RUNNING, batchId, keep, actualTotal, filesDone,
                filesSkipped, filesFailed, versionsRemoved, bytesRemoved, startedAt, null,
                null, null);
    }

    /** 파일 하나를 처리했다. */
    public CleanupProgress advanced(int newDone, int newSkipped, int newFailed,
                                    int newVersions, long newBytes)
    {
        return new CleanupProgress(State.RUNNING, batchId, keep, filesTotal, newDone,
                newSkipped, newFailed, newVersions, newBytes, startedAt, null, null, null);
    }

    /** 끝. 결과를 <b>같은 스냅샷에</b> 싣는다(위 클래스 설명). */
    public CleanupProgress ended(State newState, String newMessage,
                                 List<CleanupService.Done> details)
    {
        return new CleanupProgress(newState, batchId, keep, filesTotal, filesDone, filesSkipped,
                filesFailed, versionsRemoved, bytesRemoved, startedAt, new Date(), newMessage,
                details);
    }

    public boolean isRunning()
    {
        return state == State.RUNNING;
    }

    /** 0-100, 총계를 모르면 -1. */
    public int percent()
    {
        if (state != State.RUNNING)
        {
            // 취소·실패에 100% 를 찍으면 화면이 "다 됐다"고 말하게 된다.
            return state == State.DONE ? 100 : -1;
        }
        if (filesTotal <= 0)
        {
            return -1;
        }
        int processed = filesDone + filesSkipped + filesFailed;
        return (int) Math.min(99L, processed * 100L / filesTotal);
    }
}
