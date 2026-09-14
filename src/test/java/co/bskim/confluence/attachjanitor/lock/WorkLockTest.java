package co.bskim.confluence.attachjanitor.lock;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.beehive.ClusterLockService;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 클러스터는 여기서 못 만든다. 그래서 <b>노드 대신 스레드</b>로 본다 — 이 잠금이 지키는
 * 성질(하나만 들어간다 · 잡은 쪽만 놓는다 · 못 잡으면 기다리지 않는다)은 스레드 두 개로도
 * 그대로 드러난다. <b>노드 두 대의 경합은 이 시험이 증명하지 못한다.</b>
 *
 * <p>가짜 잠금이 <b>둘</b>인 이유: 호스트 구현이 둘이고 <b>API 지원이 다르다</b>(실측 36번).
 * Server 쪽은 {@code ReentrantLock} 처럼 전부 답하지만, DC 쪽({@code HazelcastDualLock})은
 * {@code isHeldByCurrentThread()} 를 {@code UnsupportedOperationException} 으로 던지고
 * 남의 {@code unlock()} 에 {@code IllegalMonitorStateException} 을 던진다. 처음 짠 코드는
 * 앞 구현으로만 시험해서 통과했고, 뒤 구현에서는 <b>한 번 잡은 잠금을 영영 놓지 못했다.</b>
 * 그래서 모든 시험을 두 구현에 대해 돈다.
 */
public class WorkLockTest
{
    /** Server 쪽 — 전부 답하는 잠금. */
    private static class ServerLikeLock extends ReentrantLock implements ClusterLock
    {
    }

    /** DC 쪽 — Hazelcast IMap 잠금의 겉모습. 소유자 질의는 거절하고 남의 unlock 은 던진다. */
    private static class HazelcastLikeLock implements ClusterLock
    {
        private final ReentrantLock inner = new ReentrantLock();

        @Override
        public void lock()
        {
            inner.lock();
        }

        @Override
        public boolean tryLock()
        {
            return inner.tryLock();
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException
        {
            return inner.tryLock(time, unit);
        }

        @Override
        public void unlock()
        {
            // IMap.unlock: 잡지 않은 스레드가 부르면 IllegalMonitorStateException.
            inner.unlock();
        }

        @Override
        public void lockInterruptibly()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHeldByCurrentThread()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Condition newCondition()
        {
            throw new UnsupportedOperationException();
        }

        boolean isLocked()
        {
            return inner.isLocked();
        }
    }

    private static class FakeService implements ClusterLockService
    {
        final ClusterLock lock;
        int handedOut;
        RuntimeException failure;

        FakeService(ClusterLock lock)
        {
            this.lock = lock;
        }

        @Override
        public ClusterLock getLockForName(String name)
        {
            if (failure != null)
            {
                throw failure;
            }
            handedOut++;
            assertEquals("잠금 이름이 바뀌면 이전 버전과 서로를 못 막는다",
                    "co.bskim.confluence.attachment-janitor.work", name);
            return lock;
        }

        boolean isLocked()
        {
            return lock instanceof ReentrantLock
                    ? ((ReentrantLock) lock).isLocked()
                    : ((HazelcastLikeLock) lock).isLocked();
        }
    }

    private static FakeService[] bothHosts()
    {
        return new FakeService[] {
            new FakeService(new ServerLikeLock()),
            new FakeService(new HazelcastLikeLock()),
        };
    }

    /** 다른 스레드에서 돌린다 — "다른 노드" 자리다. */
    private static <T> T onAnotherThread(Callable<T> work) throws Exception
    {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try
        {
            Future<T> answer = pool.submit(work);
            return answer.get(10, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    @Test
    public void onlyOneGetsIn() throws Exception
    {
        for (FakeService service : bothHosts())
        {
            final WorkLock lock = new WorkLock(service);
            assertTrue("첫 번째는 잡는다", lock.tryAcquire("scan"));
            assertFalse("두 번째는 기다리지 않고 거절당한다",
                    onAnotherThread(new Callable<Boolean>()
                    {
                        @Override
                        public Boolean call()
                        {
                            return Boolean.valueOf(lock.tryAcquire("cleanup"));
                        }
                    }).booleanValue());
        }
    }

    @Test
    public void releasingLetsTheNextOneIn() throws Exception
    {
        for (FakeService service : bothHosts())
        {
            final WorkLock lock = new WorkLock(service);
            assertTrue(lock.tryAcquire("scan"));
            lock.release();
            assertFalse("놓았으면 잠금이 비어 있어야 한다 — DC 구현에서 깨졌던 바로 그 자리",
                    service.isLocked());
            assertTrue("다음 작업이 들어갈 수 있어야 한다",
                    onAnotherThread(new Callable<Boolean>()
                    {
                        @Override
                        public Boolean call()
                        {
                            return Boolean.valueOf(lock.tryAcquire("scan"));
                        }
                    }).booleanValue());
        }
    }

    @Test
    public void releasingWhatYouDoNotHoldIsSilent() throws Exception
    {
        for (FakeService service : bothHosts())
        {
            final WorkLock lock = new WorkLock(service);

            // 한 번도 안 잡았는데 놓는 경우 — finally 에 그냥 놓아도 되어야 한다.
            lock.release();

            // 잡은 스레드가 아닌 쪽이 놓는 경우. 여기서 예외가 나가면 스캔 스레드의
            // finally 가 진짜 실패를 가린다. 그리고 잠금은 원래 주인 것으로 남아야 한다.
            assertTrue(lock.tryAcquire("scan"));
            onAnotherThread(new Callable<Void>()
            {
                @Override
                public Void call()
                {
                    lock.release();
                    return null;
                }
            });
            assertTrue("남의 release() 로 잠금이 풀리면 안 된다", service.isLocked());
            lock.release();
            assertFalse(service.isLocked());
        }
    }

    @Test
    public void theSameThreadCannotTakeItTwice() throws Exception
    {
        for (FakeService service : bothHosts())
        {
            WorkLock lock = new WorkLock(service);
            assertTrue(lock.tryAcquire("scan"));
            // 재진입 잠금이라 호스트는 두 번째도 허락하며 보유 횟수를 2 로 올린다. 그러면
            // release() 한 번으로는 안 풀린다. 그래서 우리가 먼저 거절해야 한다.
            assertFalse("같은 스레드의 두 번째 잡기는 거절", lock.tryAcquire("cleanup"));
            lock.release();
            assertFalse("한 번의 release() 로 완전히 풀려야 한다", service.isLocked());
        }
    }

    @Test
    public void aBrokenLockServiceRefusesTheJob()
    {
        for (FakeService service : bothHosts())
        {
            service.failure = new IllegalStateException("no cluster lock service");
            WorkLock lock = new WorkLock(service);
            // 지우는 작업의 문지기다. 잠금을 못 쓰면 열어 두는 쪽이 아니라 막는 쪽으로 틀린다.
            assertFalse(lock.tryAcquire("cleanup"));
            lock.release();
        }
    }

    @Test
    public void theLockHandleIsFetchedOnce() throws Exception
    {
        for (FakeService service : bothHosts())
        {
            WorkLock lock = new WorkLock(service);
            for (int round = 0; round < 3; round++)
            {
                assertTrue(lock.tryAcquire("scan"));
                lock.release();
            }
            assertEquals(1, service.handedOut);
            assertFalse("모두 놓였어야 한다", service.isLocked());
        }
    }
}
