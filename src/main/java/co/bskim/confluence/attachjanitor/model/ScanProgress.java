package co.bskim.confluence.attachjanitor.model;

import java.util.Date;

/** 스캔 스레드가 지금 무엇을 하는지. 폴링하는 화면에 그대로 넘겨도 되는 스냅샷이다. */
public class ScanProgress
{
    public enum State
    {
        /** 이 JVM 이 뜬 뒤로 스캔한 적이 없다. 저장된 결과는 남아 있을 수 있다. */
        IDLE,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
    }

    /**
     * 스캔 단계.
     *
     * <p>단계를 안 보여주면 본문 읽기에 몇 분이 걸리는 동안 화면이 "2,044개 처리"에
     * 멈춰 있는 것처럼 보인다. 관리자는 그걸 멈춘 것과 구분하지 못한다.
     */
    public enum Phase
    {
        NONE,
        /** 첨부 목록과 버전·용량 */
        ATTACHMENTS,
        /** 본문을 읽어 참조 인덱스를 만든다 */
        BODIES,
        /** 인덱스와 첨부를 맞춰 라벨을 정한다 */
        MATCH,
        /** 후보를 해시해 중복을 가른다 */
        DUPLICATES,
        /** 결과 저장 */
        SAVE
    }

    public final State state;
    public final Phase phase;
    public final int processed;
    /**
     * 이 단계가 처리할 것으로 보는 수. 힌트일 뿐이다 — 스캔 중에 첨부가 늘 수 있어서
     * processed 가 이걸 넘길 수 있다.
     */
    public final int expected;
    public final Date startedAt;
    public final Date finishedAt;
    public final String message;

    public ScanProgress(State state, Phase phase, int processed, int expected,
                        Date startedAt, Date finishedAt, String message)
    {
        this.state = state;
        this.phase = phase == null ? Phase.NONE : phase;
        this.processed = processed;
        this.expected = expected;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.message = message;
    }

    public static ScanProgress idle()
    {
        return new ScanProgress(State.IDLE, Phase.NONE, 0, 0, null, null, null);
    }

    public ScanProgress at(Phase newPhase, int newProcessed, int newExpected)
    {
        return new ScanProgress(State.RUNNING, newPhase, newProcessed, newExpected,
                startedAt, null, null);
    }

    public ScanProgress ended(State newState, String newMessage)
    {
        return new ScanProgress(newState, Phase.NONE, processed, expected,
                startedAt, new Date(), newMessage);
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
            return state == State.IDLE ? -1 : 100;
        }
        if (expected <= 0)
        {
            return -1;
        }
        return (int) Math.min(99L, processed * 100L / expected);
    }

    /** 화면이 문구를 고르는 데 쓴다. */
    public String phaseKey()
    {
        return "aj.phase." + phase.name().toLowerCase();
    }
}
