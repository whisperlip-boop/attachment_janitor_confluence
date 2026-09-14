package co.bskim.confluence.attachjanitor.lock;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * 이 앱의 무거운 작업 <b>하나만</b> 돌게 만드는 잠금. 스캔과 구버전 정리가 같은 이름을
 * 쓴다 — 셋 중 어느 조합도 겹치면 안 되기 때문이다.
 *
 * <p><b>왜 자바 변수로는 안 되나.</b> Data Center 는 같은 DB 와 같은 공유 홈을 보는 JVM 이
 * 여러 대다. {@code AtomicBoolean} 은 노드 하나 안에서만 참이라, 노드 둘이 동시에 스캔을
 * 시작하면 둘 다 202 를 받는다. 더 나쁜 것은 정리 × 정리다 — "실행 시점에 버전 수를 다시
 * 읽어 미리보기와 다르면 멈춘다"는 검사가 노드 사이에서는 원자적이지 않아서, 두 노드가
 * 같은 첨부를 서로의 삭제 사이에 다시 읽으면 <b>유지 개수 아래로 내려갈 수 있다</b>.
 * (현재 버전은 {@code CleanupService} 의 머리 방어가 각 노드에서 그대로 걸리므로 이
 * 경합으로도 지워지지 않는다. 잃는 것은 되돌릴 여지 쪽이다.)
 *
 * <p><b>왜 이름이 하나인가.</b> 스캔 × 정리도 막아야 한다. 스캔이 정리 중간 상태를 읽어
 * 저장하면 {@code markStale()} 이 그것을 상한 것으로 표시하지 못한다 — 그 함수는 완료된
 * 실행에 표시를 다는데 그 실행은 아직 완료 전이다. 방금 것으로 보이는 중간 상태가 남는다.
 * 이름을 둘로 나누면 이 조합을 따로 막아야 하는데, 지금 "스캔 중"이라는 사실은 DB 어디에도
 * 없다({@code ScanRun} 행은 끝날 때만 쓴다). 이름 하나면 그 문제가 사라진다.
 *
 * <p><b>절대 {@link ClusterLock#lock()} 을 쓰지 않는다.</b> 요청 스레드가 막혀 기다리면
 * 스캔이 끝날 때까지 몇 분 동안 버튼이 죽은 것처럼 보인다. 못 잡으면 즉시 409 다.
 *
 * <p><b>소유자는 우리가 직접 기억한다 — {@link ClusterLock#isHeldByCurrentThread()} 를
 * 부르지 않는다.</b> Confluence DC 의 구현({@code HazelcastDualLock})은 그 메서드를
 * {@code UnsupportedOperationException} 으로 던진다(실측 36번). Server 의 구현은 잘
 * 답하기 때문에 단일 노드 시험은 전부 통과했고, 그래서 이 결함은 코드를 읽어야만 보였다.
 * 두 호스트 구현이 공통으로 지원하는 것은 {@code tryLock()} 과 {@code unlock()} 둘뿐이고,
 * 둘 다 <b>잡은 스레드가 놓아야 한다</b>(다른 스레드의 {@code unlock()} 은 예외). 그래서
 * 잡은 스레드를 필드에 적어 두고, 그 스레드에서만 놓는다.
 */
@Named
public class WorkLock
{
    private static final Logger log = LoggerFactory.getLogger(WorkLock.class);

    /**
     * 잠금 이름은 <b>인스턴스 전체에서</b> 유일해야 한다 — 다른 앱과 겹치면 서로를 막는다.
     * 그래서 plugin key 를 그대로 앞에 붙인다.
     */
    static final String NAME = "co.bskim.confluence.attachment-janitor.work";

    private final ClusterLockService clusterLockService;

    /**
     * 이름으로 얻은 잠금 손잡이. 한 번 얻어 두는 것은 비용 때문이지 동일성 때문이 아니다 —
     * DC 구현은 부를 때마다 새 객체를 주고, 잠금의 실체는 객체가 아니라 이름(맵 키)이다.
     */
    private volatile ClusterLock cached;

    /** 지금 잠금을 쥔 스레드. 없으면 null. 이게 이 클래스의 소유자 판정 전부다. */
    private volatile Thread owner;

    @Inject
    public WorkLock(@ComponentImport ClusterLockService clusterLockService)
    {
        this.clusterLockService = clusterLockService;
    }

    /**
     * @param what 로그에 남길 작업 이름
     * @return 잡았으면 true. 못 잡으면 false — 호출자는 기다리지 말고 409 로 답한다.
     */
    public boolean tryAcquire(String what)
    {
        // 두 호스트 구현이 모두 재진입 잠금이다. 같은 스레드가 두 번 잡으면 보유 횟수가
        // 2 가 되고 release() 한 번으로는 안 풀린다. 그런 호출은 지금 없지만, 생기는 날
        // 조용히 영구 잠금이 되지 않도록 여기서 거절한다.
        if (owner == Thread.currentThread())
        {
            log.error("Attachment Janitor: {} tried to take the work lock it already holds", what);
            return false;
        }
        try
        {
            boolean mine = lock().tryLock();
            if (mine)
            {
                owner = Thread.currentThread();
            }
            else
            {
                log.info("Attachment Janitor: {} not started, another job holds the lock", what);
            }
            return mine;
        }
        catch (Throwable error)
        {
            // 7.8.1 로 컴파일해 7.12.3 에서 도니 현실적인 실패는 Error 쪽이다.
            // 지우는 작업의 문지기라서 <b>못 잡은 것으로</b> 친다 — 열어 두지 않는다.
            log.error("Attachment Janitor: cluster lock unavailable, refusing to start " + what,
                    error);
            return false;
        }
    }

    /**
     * 잡은 스레드가 부르면 놓는다. 그 밖의 스레드가 부르면 아무 일도 하지 않는다 — 그래서
     * {@code finally} 에 그냥 놓아도 된다. 판정은 우리 필드로만 한다(위 클래스 설명).
     */
    public void release()
    {
        if (owner != Thread.currentThread())
        {
            return;
        }
        try
        {
            ClusterLock lock = cached;
            if (lock != null)
            {
                lock.unlock();
            }
        }
        catch (Throwable error)
        {
            // 여기서 던지면 진짜 실패를 가린다. 잠금이 남는 것은 다음 시도에서 드러난다.
            log.error("Attachment Janitor: could not release the cluster lock", error);
        }
        finally
        {
            owner = null;
        }
    }

    private ClusterLock lock()
    {
        ClusterLock local = cached;
        if (local == null)
        {
            synchronized (this)
            {
                local = cached;
                if (local == null)
                {
                    local = clusterLockService.getLockForName(NAME);
                    cached = local;
                }
            }
        }
        return local;
    }
}
