package co.bskim.confluence.attachjanitor.clean;

import co.bskim.confluence.attachjanitor.ao.AjActionLog;
import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 구버전 정리. <b>이 앱에서 무언가를 지우는 유일한 곳이다.</b>
 *
 * <p>지울 수 있는 것은 첨부의 <b>구버전</b>뿐이다. 최신 버전·첨부 자체·페이지는 절대
 * 건드리지 않는다. 구버전 삭제가 안전한 근거는 추측이 아니라 실측이다(31번):
 * {@code AttachmentResourceIdentifier} 에 버전 필드가 없어 본문이 특정 버전을 가리킬
 * 수 없다. 그래서 라벨([활성]/[위험]/[고아])은 이 작업의 안전과 무관하다.
 *
 * <p><b>되돌릴 수 없다.</b> 버전 단위 휴지통은 없고 DB 행과 디스크 파일이 즉시
 * 사라진다(실측 30·32번). 그래서 이 클래스의 규칙은 전부 "덜 지우는 쪽"으로 기운다:
 *
 * <ul>
 *   <li>미리보기와 실행이 <b>같은 계산</b>을 쓰고, 실행은 그 사이에 바뀐 것이 없는지
 *       <b>다시 읽어 확인</b>한다. 다르면 그 첨부만 건너뛰고 나머지는 진행한다 —
 *       한 사람이 파일 하나를 올렸다고 나머지 499개를 막을 이유가 없다.</li>
 *   <li>오래된 것부터 지운다. 도중에 죽어도 최근 이력이 남는다.</li>
 *   <li>첨부 <b>하나마다 트랜잭션 하나</b>. 340개를 한 트랜잭션에 묶지 않는다 —
 *       부분 성공이 정직한 결과이기도 하다.</li>
 *   <li>건너뛴 것·실패한 것도 {@link AjActionLog} 에 남긴다. 흔적 없는 누락이 가장 나쁘다.</li>
 * </ul>
 */
@Named
public class CleanupService
{
    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    /** AO 기본 문자열 열은 255자다. */
    private static final int CLIP = 255;

    private final AttachmentManager attachmentManager;
    private final ActiveObjects ao;
    private final TransactionTemplate transactionTemplate;

    /** 첨부 하나에 대한 계산 결과. 미리보기의 한 줄이자 실행의 한 단위다. */
    public static final class Item
    {
        public final long attachmentId;
        public final String fileName;
        public final String spaceKey;
        public final long containerId;
        public final String containerTitle;
        public final int currentVersion;
        public final List<Integer> removeVersions;
        public final long removeBytes;
        /** 계산할 수 없었던 이유. 채워져 있으면 이 첨부는 대상이 아니다. */
        public final String problem;

        Item(long attachmentId, String fileName, String spaceKey, long containerId,
             String containerTitle, int currentVersion, List<Integer> removeVersions,
             long removeBytes, String problem)
        {
            this.attachmentId = attachmentId;
            this.fileName = fileName;
            this.spaceKey = spaceKey;
            this.containerId = containerId;
            this.containerTitle = containerTitle;
            this.currentVersion = currentVersion;
            this.removeVersions = removeVersions;
            this.removeBytes = removeBytes;
            this.problem = problem;
        }

        public boolean isActionable()
        {
            return problem == null && !removeVersions.isEmpty();
        }
    }

    /** 미리보기 한 벌. 실행은 이걸 그대로 돌려받아 대조한다. */
    public static final class Preview
    {
        public final int keep;
        public final List<Item> items = new ArrayList<Item>();

        Preview(int keep)
        {
            this.keep = keep;
        }

        public int fileCount()
        {
            int count = 0;
            for (Item item : items)
            {
                if (item.isActionable())
                {
                    count++;
                }
            }
            return count;
        }

        public int versionCount()
        {
            int count = 0;
            for (Item item : items)
            {
                if (item.isActionable())
                {
                    count += item.removeVersions.size();
                }
            }
            return count;
        }

        public long bytes()
        {
            long total = 0;
            for (Item item : items)
            {
                if (item.isActionable())
                {
                    total += item.removeBytes;
                }
            }
            return total;
        }
    }

    /** 첨부 하나의 실행 결과. 화면이 표의 수치를 즉시 바로잡는 데 쓴다. */
    public static final class Done
    {
        public final long attachmentId;
        public final int versionsRemoved;
        public final long bytesRemoved;
        public final String outcome;

        Done(long attachmentId, int versionsRemoved, long bytesRemoved, String outcome)
        {
            this.attachmentId = attachmentId;
            this.versionsRemoved = versionsRemoved;
            this.bytesRemoved = bytesRemoved;
            this.outcome = outcome;
        }
    }

    /** 실행 결과. */
    public static final class Result
    {
        public final String batchId;
        /**
         * 파일별 내역.
         *
         * <p>화면이 이걸로 표의 구버전 수치를 즉시 깎는다. 없으면 정리한 파일이 표에서
         * 여전히 "구버전 27개"로 보이고, 체크박스가 지울 것 없는 파일에 계속 남는다.
         */
        public final List<Done> done = new ArrayList<Done>();
        public int filesDone;
        public int filesSkipped;
        public int filesFailed;
        public int versionsRemoved;
        public long bytesRemoved;
        public final List<String> notes = new ArrayList<String>();
        /** 도중에 멈췄나. 멈춘 실행도 배치 요약 행을 남긴다 — 흔적 없는 삭제는 없다. */
        public boolean cancelled;
        /** 건드린 스페이스. 배치 요약 한 줄에 "어디를 지웠나"를 적기 위한 것이다. */
        public final java.util.LinkedHashSet<String> spaceKeys =
                new java.util.LinkedHashSet<String>();

        Result(String batchId)
        {
            this.batchId = batchId;
        }
    }

    @Inject
    public CleanupService(@ComponentImport AttachmentManager attachmentManager,
                          @ComponentImport ActiveObjects ao,
                          @ComponentImport TransactionTemplate transactionTemplate)
    {
        this.attachmentManager = attachmentManager;
        this.ao = ao;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 오래 도는 쪽이 밖에 알리고 밖의 뜻을 듣는 통로.
     *
     * <p>이 앱의 다른 긴 루프({@code BodyWalker} · {@code DuplicateFinder})와 같은 꼴이다.
     * 루프 <b>안에서</b> 취소를 확인하지 않으면 파일이 많은 정리 하나가 취소를 몇 분
     * 지연시킨다.
     */
    public interface Watcher
    {
        boolean cancelled();

        /** 파일 하나를 처리할 때마다. 인자는 지금까지의 누계다. */
        void advanced(Result running);
    }

    /** 아무것도 보지 않고 아무것도 멈추지 않는 감시자. 동기 경로가 쓴다. */
    public static final Watcher IGNORING = new Watcher()
    {
        @Override
        public boolean cancelled()
        {
            return false;
        }

        @Override
        public void advanced(Result running)
        {
        }
    };

    /**
     * 무엇을 지울지 <b>지금 이 순간의 DB 를 읽어</b> 계산한다.
     *
     * <p>저장된 스캔 결과를 쓰지 않는다. 스캔은 스냅샷이고 며칠 전 것일 수 있다.
     */
    public Preview preview(final List<Long> attachmentIds, final int keep)
    {
        return preview(attachmentIds, keep, IGNORING);
    }

    /**
     * 위와 같되 도중에 멈출 수 있다.
     *
     * <p>미리보기도 첨부 하나마다 {@code getPreviousVersions()} 를 부르므로 약 14ms 씩
     * 든다(실측 4번). 첨부 수천 개면 이것만으로 몇 분이다 — 취소가 여기에도 닿아야 한다.
     * 멈추면 그때까지 읽은 것만 담긴 미리보기가 나오므로, 호출자는 취소 여부를 따로
     * 확인하고 <b>그 미리보기로 실행하지 않는다.</b>
     */
    public Preview preview(final List<Long> attachmentIds, final int keep, final Watcher watcher)
    {
        final Preview preview = new Preview(Math.max(VersionPlan.MIN_KEEP, keep));
        transactionTemplate.execute(new TransactionCallback<Void>()
        {
            @Override
            public Void doInTransaction()
            {
                for (Long id : attachmentIds)
                {
                    if (watcher.cancelled())
                    {
                        return null;
                    }
                    preview.items.add(inspect(id.longValue(), preview.keep));
                }
                return null;
            }
        });
        return preview;
    }

    /** 첨부 하나를 읽어 지울 버전을 정한다. 실패는 예외가 아니라 {@code problem} 이다. */
    private Item inspect(long attachmentId, int keep)
    {
        try
        {
            Attachment head = attachmentManager.getAttachment(attachmentId);
            if (head == null)
            {
                return problem(attachmentId, "gone");
            }
            // 머리가 아닌 행을 넘겨받았다면 계산이 통째로 틀어진다. 여기서 잘라낸다.
            if (!head.isLatestVersion())
            {
                return problem(attachmentId, "notlatest");
            }

            List<Attachment> previous = attachmentManager.getPreviousVersions(head);
            List<Integer> numbers = new ArrayList<Integer>();
            for (Attachment version : previous)
            {
                numbers.add(Integer.valueOf(version.getVersion()));
            }
            List<Integer> remove = VersionPlan.toRemove(numbers, head.getVersion(), keep);

            long bytes = 0;
            for (Attachment version : previous)
            {
                if (remove.contains(Integer.valueOf(version.getVersion())))
                {
                    bytes += version.getFileSize();
                }
            }

            String spaceKey = head.getSpace() == null ? null : head.getSpace().getKey();
            long containerId = head.getContainer() == null ? 0L : head.getContainer().getId();
            String containerTitle = head.getContainer() == null
                    ? null : head.getContainer().getTitle();
            return new Item(attachmentId, head.getFileName(), spaceKey, containerId,
                    containerTitle, head.getVersion(), remove, bytes, null);
        }
        catch (Throwable error)
        {
            log.warn("Attachment Janitor: could not inspect attachment {}", attachmentId, error);
            return problem(attachmentId, "unreadable");
        }
    }

    private static Item problem(long attachmentId, String reason)
    {
        return new Item(attachmentId, null, null, 0L, null, 0, new ArrayList<Integer>(),
                0L, reason);
    }

    /**
     * 지운다. <b>실행 직전에 다시 읽어 화면이 본 것과 대조한다.</b>
     *
     * @param preview     서버가 지금 계산한 것
     * @param expected    <b>화면이 미리보기에서 본 것</b>. 첨부 id → {@code "1,2,3"}.
     *                    여기 없거나 다른 첨부는 건드리지 않는다 — 사용자가 확인하지 않은
     *                    일을 하지 않는다는 뜻이고, 이 인자가 없으면 "다시 확인"이 서버가
     *                    방금 계산한 것끼리 비교하는 빈 절차가 된다
     * @param requestedBy 실행한 관리자
     */
    /**
     * <p>배치 식별자는 밖에서 받는다. 비동기 실행이 시작하자마자 202 로 이 값을 돌려줘야
     * 하는데, 여기서 만들면 그때는 아직 없다.
     *
     * <p><b>취소해도 이미 지운 것은 돌아오지 않는다.</b> 버전 단위 휴지통이 없다(실측
     * 30번). 취소가 뜻하는 것은 "여기서부터는 더 지우지 마라" 하나뿐이고, 결과에는
     * 그때까지 지운 것이 정직하게 담긴다.
     */
    public Result execute(Preview preview, Map<Long, String> expected, String requestedBy,
                          String batchId, Watcher watcher)
    {
        Result result = new Result(batchId);
        Date at = new Date();

        for (Item planned : preview.items)
        {
            if (!planned.isActionable())
            {
                continue;
            }
            if (watcher.cancelled())
            {
                result.cancelled = true;
                return result;
            }
            applyOne(planned, expected.get(Long.valueOf(planned.attachmentId)),
                    preview.keep, requestedBy, at, result);
            watcher.advanced(result);
        }
        return result;
    }

    /** 첨부 하나. 트랜잭션도 하나다. */
    private void applyOne(final Item planned, final String expected, final int keep,
                          final String requestedBy, final Date at, final Result result)
    {
        try
        {
            transactionTemplate.execute(new TransactionCallback<Void>()
            {
                @Override
                public Void doInTransaction()
                {
                    // 미리보기 이후에 바뀌었는지 지금 다시 읽는다.
                    Item now = inspect(planned.attachmentId, keep);
                    if (now.problem != null)
                    {
                        record(planned, keep, requestedBy, at, result, "SKIPPED", now.problem,
                                0, 0L, "");
                        result.filesSkipped++;
                        result.done.add(new Done(planned.attachmentId, 0, 0L, "SKIPPED"));
                        return null;
                    }
                    // 화면이 본 것과 지금 계산한 것을 견준다. 누가 새 버전을 올렸거나
                    // 지웠으면 여기서 갈린다. 이 첨부만 건너뛰고 나머지는 진행한다.
                    if (expected == null || !expected.equals(join(now.removeVersions)))
                    {
                        record(planned, keep, requestedBy, at, result, "SKIPPED", "changed",
                                0, 0L, "");
                        result.filesSkipped++;
                        result.done.add(new Done(planned.attachmentId, 0, 0L, "SKIPPED"));
                        return null;
                    }

                    Attachment head = attachmentManager.getAttachment(planned.attachmentId);
                    List<Attachment> previous = attachmentManager.getPreviousVersions(head);
                    int removed = 0;
                    long bytes = 0;
                    StringBuilder numbers = new StringBuilder();

                    // 오래된 것부터. now.removeVersions 는 이미 오름차순이다.
                    for (Integer wanted : now.removeVersions)
                    {
                        Attachment victim = find(previous, wanted.intValue());
                        if (victim == null)
                        {
                            continue;
                        }
                        // 두 번째 안전장치. 산술이 틀려도 최신은 넘어가지 않는다(실측 32번).
                        if (victim.getVersion() >= head.getVersion())
                        {
                            log.error("Attachment Janitor: refusing to remove version {} of {}"
                                    + " - it is not older than the current version {}",
                                    new Object[] {Integer.valueOf(victim.getVersion()),
                                        Long.valueOf(head.getId()),
                                        Integer.valueOf(head.getVersion())});
                            continue;
                        }
                        long size = victim.getFileSize();
                        attachmentManager.removeAttachmentVersionFromServer(victim);
                        removed++;
                        bytes += size;
                        numbers.append(numbers.length() == 0 ? "" : ",").append(wanted);
                    }

                    record(planned, keep, requestedBy, at, result, "DONE", null,
                            removed, bytes, numbers.toString());
                    result.filesDone++;
                    result.versionsRemoved += removed;
                    result.bytesRemoved += bytes;
                    result.done.add(new Done(planned.attachmentId, removed, bytes, "DONE"));
                    return null;
                }
            });
        }
        catch (Throwable error)
        {
            // 첨부 하나가 나머지 전부를 잃게 하지 않는다.
            log.warn("Attachment Janitor: cleanup failed for attachment {}",
                    planned.attachmentId, error);
            result.filesFailed++;
            result.notes.add(planned.fileName == null
                    ? String.valueOf(planned.attachmentId) : planned.fileName);
            try
            {
                recordOutside(planned, keep, requestedBy, at, result, "FAILED",
                        String.valueOf(error));
            }
            catch (Throwable ignored)
            {
                // 로그조차 못 남기는 상황이면 위의 warn 이 마지막 흔적이다.
                log.warn("Attachment Janitor: could not write the action log either", ignored);
            }
        }
    }

    /** {@code [1, 2, 3]} → {@code "1,2,3"}. 화면·로그·대조가 같은 표기를 쓴다. */
    public static String join(List<Integer> versions)
    {
        StringBuilder out = new StringBuilder();
        for (Integer version : versions)
        {
            out.append(out.length() == 0 ? "" : ",").append(version);
        }
        return out.toString();
    }

    private static Attachment find(List<Attachment> versions, int number)
    {
        for (Attachment version : versions)
        {
            if (version.getVersion() == number)
            {
                return version;
            }
        }
        return null;
    }

    private void record(Item item, int keep, String requestedBy, Date at, Result result,
                        String outcome, String detail, int removed, long bytes, String numbers)
    {
        if (item.spaceKey != null)
        {
            result.spaceKeys.add(item.spaceKey);
        }
        AjActionLog row = ao.create(AjActionLog.class);
        row.setBatchId(result.batchId);
        row.setAt(at);
        row.setRequestedBy(clip(requestedBy));
        row.setSpaceKey(clip(item.spaceKey));
        row.setAttachmentId(item.attachmentId);
        row.setFileName(clip(item.fileName));
        row.setContainerId(item.containerId);
        row.setKeepVersions(keep);
        row.setVersionsRemoved(removed);
        row.setBytesRemoved(bytes);
        row.setVersionNumbers(clip(numbers));
        row.setOutcome(outcome);
        row.setDetail(clip(detail));
        row.save();
    }

    /** 트랜잭션이 깨진 뒤에 남기는 기록. 새 트랜잭션이 필요하다. */
    private void recordOutside(final Item item, final int keep, final String requestedBy,
                               final Date at, final Result result, final String outcome,
                               final String detail)
    {
        transactionTemplate.execute(new TransactionCallback<Void>()
        {
            @Override
            public Void doInTransaction()
            {
                record(item, keep, requestedBy, at, result, outcome, detail, 0, 0L, "");
                return null;
            }
        });
    }

    private static String clip(String value)
    {
        if (value == null)
        {
            return null;
        }
        return value.length() <= CLIP ? value : value.substring(0, CLIP);
    }
}
