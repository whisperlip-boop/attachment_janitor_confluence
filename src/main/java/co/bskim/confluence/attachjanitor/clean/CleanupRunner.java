package co.bskim.confluence.attachjanitor.clean;

import co.bskim.confluence.attachjanitor.lock.WorkLock;
import co.bskim.confluence.attachjanitor.model.CleanupProgress;
import co.bskim.confluence.attachjanitor.settings.SettingsStore;
import co.bskim.confluence.attachjanitor.store.ScanStore;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 구버전 정리를 <b>백그라운드에서</b> 돌린다. 무엇을 지울지는 {@link CleanupService} 가
 * 알고, 이 클래스는 언제 돌고 어디까지 갔는지만 안다.
 *
 * <p><b>왜 비동기인가.</b> 전에는 {@code POST /cleanup} 이 요청 스레드에서 끝까지 돌았다.
 * 파일당 약 105ms 라 1,020개가 107초였고 상한(5,000)이면 9분이다. 앞단이 AWS ALB 인
 * 환경에서 유휴 시간 초과는 60초인데, 정리가 도는 동안에는 연결에 아무것도 흐르지
 * 않으므로 그 60초를 그냥 넘긴다 — 브라우저는 통신 실패를 보고 <b>삭제는 서버에서
 * 계속된다.</b> 무결성은 무사했지만 응답이 사라져 화면이 표를 깎지 못했다. 상한을 낮추는
 * 것은 창을 좁힐 뿐 닫지 못하고, 타임아웃 값에 앱이 의존하게 된다(실측 37번).
 *
 * <p>구조는 {@code ScanService} 를 그대로 본떴다. 특히 두 가지를 똑같이 지킨다:
 * <ul>
 *   <li><b>잠금은 워커 스레드가 잡는다.</b> {@link WorkLock} 은 소유자를 추적하므로 잡은
 *       스레드가 놓아야 한다. 요청 스레드에서 잡으면 놓을 곳이 없다(실측 35번).</li>
 *   <li><b>잠금을 잡은 뒤에만 진행률을 RUNNING 으로 세운다.</b> 순서를 뒤집으면 시작하지도
 *       않은 작업을 화면이 영원히 폴링한다.</li>
 * </ul>
 *
 * <p><b>취소는 "여기서부터 더 지우지 마라"일 뿐이다.</b> 이미 지운 버전은 돌아오지
 * 않는다 — 버전 단위 휴지통이 없다(실측 30번). 그래서 멈춘 실행도 기록을 남기고,
 * 지운 만큼 스캔 결과에 옛것 표시를 붙인다.
 */
@Named
public class CleanupRunner
{
    private static final Logger log = LoggerFactory.getLogger(CleanupRunner.class);

    /** 워커가 "잠금을 잡았나"를 알려 줄 때까지 요청 스레드가 기다리는 한도. */
    private static final int LOCK_ANSWER_SECONDS = 30;

    private final CleanupService cleanupService;
    private final ScanStore store;
    private final SettingsStore settingsStore;
    private final TransactionTemplate transactionTemplate;
    private final WorkLock workLock;

    private volatile boolean cancelRequested;
    private volatile CleanupProgress progress = CleanupProgress.idle();

    @Inject
    public CleanupRunner(CleanupService cleanupService, ScanStore store,
                         SettingsStore settingsStore,
                         @ComponentImport TransactionTemplate transactionTemplate,
                         WorkLock workLock)
    {
        this.cleanupService = cleanupService;
        this.store = store;
        this.settingsStore = settingsStore;
        this.transactionTemplate = transactionTemplate;
        this.workLock = workLock;
    }

    public CleanupProgress progress()
    {
        return progress;
    }

    /**
     * 도는 실행을 멈춘다.
     *
     * @param batchId 멈추려는 실행. 지금 도는 것과 다르면 <b>아무 일도 하지 않는다</b> —
     *                옛 탭에 남아 있던 중지 단추가 그 사이 다른 관리자가 시작한 실행을
     *                첫 파일에서 세우는 일이 없어야 한다. {@code null} 이면 가리지 않는다
     * @return 실제로 취소를 걸었으면 true
     */
    public boolean cancel(String batchId)
    {
        CleanupProgress now = progress;
        if (batchId != null && !batchId.equals(now.batchId))
        {
            return false;
        }
        cancelRequested = true;
        return true;
    }

    /**
     * 앱이 꺼지거나 새 버전으로 바뀔 때. {@code ScanService} 와 같은 등급이다 —
     * 이게 없어도 잠금은 결국 풀리고, 이 애노테이션이 더하는 것은 덜 지우고 멈추는
     * 것뿐이다. <b>이 경로가 실제로 도는 것은 관찰하지 못했다.</b>
     */
    @PreDestroy
    public void shutdown()
    {
        cancelRequested = true;
    }

    /**
     * 정리를 시작한다.
     *
     * @return 이 실행의 배치 식별자. 다른 작업(스캔이든 정리든, 다른 노드일 수도 있다)이
     *         도는 중이면 {@code null} — 호출자는 409 로 답한다
     */
    public String start(final List<Long> ids, final int keep, final Map<Long, String> expected,
                        final String requestedBy)
    {
        // 식별자는 요청 스레드에서 만든다. 202 응답이 이미 이 값을 들고 나가야 화면이
        // "내가 시작한 그 실행인가"를 폴링에서 가릴 수 있다.
        final String batchId = UUID.randomUUID().toString();
        final int requested = ids.size();
        final CountDownLatch answered = new CountDownLatch(1);
        final AtomicBoolean acquired = new AtomicBoolean(false);

        Thread thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                // 잡은 순간부터 놓을 때까지가 한 try 다. 그 사이 무엇이 던져도
                // release() 에 닿아야 한다 — 그 틈이 곧 영구 잠금이다.
                boolean mine = false;
                try
                {
                    try
                    {
                        mine = workLock.tryAcquire("cleanup");
                        if (mine)
                        {
                            // 지난 실행의 취소 요청이 남아 있으면 새 실행이 첫 파일에서
                            // 죽는다. 잠금을 잡은 뒤 여기서 지운다.
                            cancelRequested = false;
                            progress = CleanupProgress.started(batchId, keep, requested);
                        }
                        acquired.set(mine);
                    }
                    finally
                    {
                        answered.countDown();
                    }
                    if (mine)
                    {
                        perform(batchId, ids, keep, expected, requestedBy);
                    }
                }
                finally
                {
                    if (mine)
                    {
                        workLock.release();
                    }
                }
            }
        }, "attachment-janitor-cleanup");
        thread.setDaemon(true);
        thread.start();

        try
        {
            if (!answered.await(LOCK_ANSWER_SECONDS, TimeUnit.SECONDS))
            {
                log.error("Attachment Janitor: the cleanup thread did not report back in {}s",
                        Integer.valueOf(LOCK_ANSWER_SECONDS));
                return null;
            }
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        return acquired.get() ? batchId : null;
    }

    private void perform(String batchId, List<Long> ids, int keep,
                         Map<Long, String> expected, String requestedBy)
    {
        final Date startedAt = progress.startedAt;
        CleanupService.Result result = null;
        CleanupProgress.State end;
        String message = null;

        CleanupService.Watcher watcher = new CleanupService.Watcher()
        {
            @Override
            public boolean cancelled()
            {
                return cancelRequested;
            }

            @Override
            public void advanced(CleanupService.Result running)
            {
                progress = progress.advanced(running.filesDone, running.filesSkipped,
                        running.filesFailed, running.versionsRemoved, running.bytesRemoved);
            }
        };

        try
        {
            // 미리보기도 첨부 하나마다 약 14ms 다(실측 4번). 수천 개면 이것만으로 몇
            // 분이라 취소가 여기에도 닿아야 한다.
            CleanupService.Preview preview = cleanupService.preview(ids, keep, watcher);
            if (cancelRequested)
            {
                // 잘린 미리보기로 실행하지 않는다. 고른 것의 일부만 지우게 된다.
                end = CleanupProgress.State.CANCELLED;
            }
            else
            {
                progress = progress.totalling(preview.fileCount());
                result = cleanupService.execute(preview, expected, requestedBy, batchId, watcher);
                end = result.cancelled
                        ? CleanupProgress.State.CANCELLED : CleanupProgress.State.DONE;
            }
        }
        catch (Throwable error)
        {
            // Throwable 이다. 7.8.1 로 컴파일해 7.12.3 에서 돌리므로 현실적인 실패는
            // NoSuchMethodError / NoClassDefFoundError — 전부 Error 다. 안 잡으면
            // 진행률이 RUNNING 에 박혀 화면이 영구 폴링한다.
            log.warn("Attachment Janitor: cleanup failed", error);
            end = CleanupProgress.State.FAILED;
            message = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
        }

        finish(batchId, startedAt, requestedBy, keep, result, end, message);
    }

    /**
     * 뒷정리. <b>끝맺음을 가리지 않는다</b> — 취소도 실패도 여기를 지난다.
     *
     * <p>취소한 실행에도 이미 지운 파일이 있다. 그 경우에도 스캔 결과에 옛것 표시를
     * 붙이고 기록을 남긴다. 안 그러면 표는 지워진 버전을 여전히 세고 있고, 그 삭제는
     * 아무도 한 적 없는 일이 된다.
     *
     * <p><b>진행률 스냅샷을 가장 마지막에, 결과와 함께 한 번에 쓴다.</b> 진행률과 결과를
     * 따로 쓰면 그 사이에 폴링이 도착한 화면이 "끝났는데 내역이 없다"를 보고 표를 깎지
     * 못한다 — 실측 34번이 그대로 돌아온다.
     */
    private void finish(String batchId, Date startedAt, String requestedBy, int keep,
                        final CleanupService.Result result, CleanupProgress.State end,
                        String message)
    {
        final int filesDone = result == null ? 0 : result.filesDone;
        final int filesSkipped = result == null ? 0 : result.filesSkipped;
        final int filesFailed = result == null ? 0 : result.filesFailed;
        final int versions = result == null ? 0 : result.versionsRemoved;
        final long bytes = result == null ? 0L : result.bytesRemoved;
        final String spaces = result == null ? "" : join(result.spaceKeys);
        List<CleanupService.Done> details = result == null
                ? new ArrayList<CleanupService.Done>() : result.done;

        if (filesDone > 0)
        {
            try
            {
                transactionTemplate.execute(new TransactionCallback<Void>()
                {
                    @Override
                    public Void doInTransaction()
                    {
                        // 저장된 스캔의 구버전 수치는 이제 틀렸다. 다음 스캔까지 그렇다고
                        // 적는다. 수치를 고쳐 쓰지는 않는다 — 스캔한 적 없는 상태를
                        // 스캔 결과처럼 보여주게 된다.
                        store.markStale();
                        return null;
                    }
                });
            }
            catch (Throwable error)
            {
                log.warn("Attachment Janitor: could not mark the scan stale", error);
            }
        }

        final Date finishedAt = new Date();
        final String outcome = end.name();
        final String detail = message;
        final Date started = startedAt;
        final String who = requestedBy;
        final String id = batchId;
        final int keepVersions = keep;
        try
        {
            transactionTemplate.execute(new TransactionCallback<Void>()
            {
                @Override
                public Void doInTransaction()
                {
                    store.saveBatch(id, started, finishedAt, who, keepVersions, filesDone,
                            filesSkipped, filesFailed, versions, bytes, spaces, outcome, detail);
                    return null;
                }
            });
        }
        catch (Throwable error)
        {
            // 요약을 못 남겨도 파일별 행은 이미 있다. 그게 마지막 흔적이다.
            log.warn("Attachment Janitor: could not write the batch summary", error);
        }

        try
        {
            // 설정 읽기도 try 안이다. 이 한 줄이 밖에 있던 채로 리뷰에 잡혔다 — 여기서
            // 던지면 아래 끝 스냅샷을 못 쓰고 진행률이 RUNNING 에 박혀 모든 화면이
            // 영구 폴링한다. 되돌릴 수 없는 삭제를 다 해 놓고서다.
            final int keepDays = settingsStore.load().keepActionDays;
            transactionTemplate.execute(new TransactionCallback<Void>()
            {
                @Override
                public Void doInTransaction()
                {
                    store.pruneActions(keepDays);
                    return null;
                }
            });
        }
        catch (Throwable error)
        {
            log.warn("Attachment Janitor: could not prune old cleanup records", error);
        }
        finally
        {
            // 뒷정리에서 무엇이 어긋나도 끝 스냅샷은 쓴다. 이게 없으면 화면은 끝난 실행을
            // 영원히 "도는 중"으로 본다(스캔 경로의 catch (Throwable) 규칙과 같은 이유).
            progress = progress.ended(end, message, details);
        }
    }

    private static String join(Iterable<String> keys)
    {
        StringBuilder out = new StringBuilder();
        for (String key : keys)
        {
            out.append(out.length() == 0 ? "" : ",").append(key);
        }
        return out.toString();
    }
}
