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
    /**
     * 본문 단계가 지금까지 읽은 본문 수. 다른 단계에서는 0 이다.
     *
     * <p>본문은 총계를 미리 알 수 없다 — 페이지 수도 이력 깊이도 스페이스마다 다르다.
     * 그래서 분모(스페이스 수)와 별개로 "몇 건 읽었는지"를 함께 낸다. 이게 없으면
     * 페이지가 많은 스페이스 하나를 도는 동안 화면이 멈춘 것처럼 보인다(실측 24번).
     */
    public final int bodies;
    public final Date startedAt;
    public final Date finishedAt;
    public final String message;

    public ScanProgress(State state, Phase phase, int processed, int expected,
                        Date startedAt, Date finishedAt, String message)
    {
        this(state, phase, processed, expected, 0, startedAt, finishedAt, message);
    }

    public ScanProgress(State state, Phase phase, int processed, int expected, int bodies,
                        Date startedAt, Date finishedAt, String message)
    {
        this.state = state;
        this.phase = phase == null ? Phase.NONE : phase;
        this.processed = processed;
        this.expected = expected;
        this.bodies = bodies;
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
        return at(newPhase, newProcessed, newExpected, 0);
    }

    public ScanProgress at(Phase newPhase, int newProcessed, int newExpected, int newBodies)
    {
        return new ScanProgress(State.RUNNING, newPhase, newProcessed, newExpected, newBodies,
                startedAt, null, null);
    }

    public ScanProgress ended(State newState, String newMessage)
    {
        return new ScanProgress(newState, Phase.NONE, processed, expected, bodies,
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
            // 끝난 것과 완주한 것은 다르다. 취소·실패에 100% 를 찍으면 화면이
            // "다 됐다"고 말하게 된다.
            return state == State.DONE ? 100 : -1;
        }
        if (expected <= 0)
        {
            return -1;
        }
        // 본문 단계는 분모가 스페이스 수라 비율이 실제 진척과 안 맞는다 — 큰 스페이스
        // 하나를 도는 몇 분 동안 0% 로 앉아 있게 된다. 지어내지 않고 "모름"으로 낸다.
        // 대신 읽은 본문 수(bodies)가 계속 올라가고 화면이 그걸 보여준다.
        if (phase == Phase.BODIES)
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
