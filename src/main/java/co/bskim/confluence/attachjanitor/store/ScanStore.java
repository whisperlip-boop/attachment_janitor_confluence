package co.bskim.confluence.attachjanitor.store;

import co.bskim.confluence.attachjanitor.analyze.DuplicateFinder;
import co.bskim.confluence.attachjanitor.ao.AjActionLog;
import co.bskim.confluence.attachjanitor.ao.AjAttachment;
import co.bskim.confluence.attachjanitor.ao.AjDupGroup;
import co.bskim.confluence.attachjanitor.ao.AjRefHit;
import co.bskim.confluence.attachjanitor.ao.AjScanRun;
import co.bskim.confluence.attachjanitor.ao.AjSpaceStat;
import co.bskim.confluence.attachjanitor.model.AttachmentFacts;
import co.bskim.confluence.attachjanitor.model.Badge;
import co.bskim.confluence.attachjanitor.model.Label;
import co.bskim.confluence.attachjanitor.model.RefSource;
import co.bskim.confluence.attachjanitor.model.ScanOutcome;
import co.bskim.confluence.attachjanitor.model.SpaceTotals;
import co.bskim.confluence.attachjanitor.settings.Settings;
import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import net.java.ao.Query;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Active Objects 를 만지는 유일한 곳.
 *
 * <p><b>보관 정책이 두 겹이다.</b>
 * <ul>
 *   <li>{@link AjScanRun} · {@link AjSpaceStat} — 설정한 세대만큼 남긴다. 관리자가 실제로
 *       묻는 것은 "늘고 있나?" 인데 그건 비교 대상이 있어야 답할 수 있다.</li>
 *   <li>{@link AjAttachment} · {@link AjRefHit} · {@link AjDupGroup} — <b>마지막으로 완료된
 *       실행 한 벌만</b> 남긴다. 첨부 행은 인스턴스 첨부 수만큼 생기므로 세대를 쌓으면
 *       그것만으로 DB 가 커진다. 용량을 줄이자고 만든 앱이 용량을 먹으면 안 된다.</li>
 * </ul>
 * 상세를 지우는 시점은 <b>새 실행을 저장한 뒤</b>다. 먼저 지우면 스캔이 실패했을 때 볼
 * 것이 아무것도 남지 않는다. 이 순서를 바꾸지 말 것.
 */
@Named
public class ScanStore
{
    /**
     * {@code IN (...)} 한 번에 넣는 ID 개수. Postgres 는 인자 65535 개가 한도이고
     * Oracle 은 {@code IN} 목록이 1,000 개까지다 — 낮은 쪽에 맞춘다.
     */
    private static final int IN_CHUNK = 900;

    /** 정리 기록 화면이 한 번에 보는 행 수. */
    private static final int ACTION_LOG_PAGE = 200;

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private final ActiveObjects ao;

    @Inject
    public ScanStore(@ComponentImport ActiveObjects ao)
    {
        this.ao = ao;
    }

    /** 가장 최근 실행. 상태를 가리지 않는다 — 실패와 취소도 화면이 알아야 한다. */
    public AjScanRun latestRun()
    {
        AjScanRun[] runs = ao.find(AjScanRun.class, Query.select().order("ID DESC").limit(1));
        return runs.length == 0 ? null : runs[0];
    }

    /** 실제로 끝난 가장 최근 실행. 표가 보여주는 수치의 출처다. */
    public AjScanRun latestCompleteRun()
    {
        AjScanRun[] runs = ao.find(AjScanRun.class,
                Query.select().where("STATUS = ?", DONE).order("ID DESC").limit(1));
        return runs.length == 0 ? null : runs[0];
    }

    public List<AjSpaceStat> spaceStats(AjScanRun run)
    {
        if (run == null)
        {
            return new ArrayList<AjSpaceStat>();
        }
        return Arrays.asList(ao.find(AjSpaceStat.class,
                Query.select().where("RUN_ID = ?", run.getID()).order("TOTAL_BYTES DESC")));
    }

    /**
     * 한 스페이스의 첨부 행. 상세 화면이 읽는다.
     *
     * <p>정렬은 라벨의 정리 순위가 아니라 용량이다 — 필터로 라벨을 좁히고 나면 그 안에서
     * 궁금한 것은 "어느 게 크냐" 이기 때문이다.
     */
    public List<AjAttachment> attachments(AjScanRun run, String spaceKey)
    {
        if (run == null || !run.isHasDetail())
        {
            return new ArrayList<AjAttachment>();
        }
        Query query = spaceKey == null
                ? Query.select().where("RUN_ID = ?", run.getID())
                : Query.select().where("RUN_ID = ? AND SPACE_KEY = ?", run.getID(), spaceKey);
        return Arrays.asList(ao.find(AjAttachment.class,
                query.order("LATEST_BYTES + OLD_VERSION_BYTES DESC")));
    }

    /**
     * 여러 첨부의 근거를 <b>한 번에</b> 읽는다.
     *
     * <p>첨부마다 따로 읽으면 첨부 2,000 개짜리 스페이스 화면 한 번에 조회가 2,000 번
     * 나간다. 그래서 {@code IN} 으로 묶는다. 목록이 길면 SQL 인자 한도에 걸리므로
     * {@code IN_CHUNK} 씩 끊는다.
     *
     * @return 첨부 행 ID → 근거 목록. 근거가 없는 첨부는 키 자체가 없다
     */
    /**
     * 구버전 정리가 돈 뒤에 부른다. 저장된 최신 완료 실행에 "이제 옛것"이라고 표시한다.
     *
     * <p>지운 것을 반영해 수치를 고쳐 쓰지 않는다 — 그러면 화면이 스캔한 적 없는 상태를
     * 스캔 결과인 것처럼 보여주게 된다. 틀렸다고 말하고 다시 스캔하게 하는 편이 정직하다.
     */
    public void markStale()
    {
        AjScanRun run = latestCompleteRun();
        if (run == null || run.isSupersededByAction())
        {
            return;
        }
        run.setSupersededByAction(true);
        run.save();
    }

    /** 최근 정리 기록. 최신이 먼저다. */
    public List<AjActionLog> recentActions()
    {
        return Arrays.asList(ao.find(AjActionLog.class,
                Query.select().order("ID DESC").limit(ACTION_LOG_PAGE)));
    }

    public Map<Integer, List<AjRefHit>> refsOf(List<AjAttachment> rows)
    {
        Map<Integer, List<AjRefHit>> byRow = new HashMap<Integer, List<AjRefHit>>();

        List<Integer> ids = new ArrayList<Integer>();
        for (AjAttachment row : rows)
        {
            // 근거가 0 건인 첨부는 물어볼 필요가 없다. [고아] 가 많은 인스턴스에서
            // 이 한 줄이 조회 대상을 크게 줄인다.
            if (row.getRefCount() > 0)
            {
                ids.add(Integer.valueOf(row.getID()));
            }
        }

        for (int from = 0; from < ids.size(); from += IN_CHUNK)
        {
            List<Integer> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            for (AjRefHit hit : ao.find(AjRefHit.class,
                    Query.select().where("ROW_ID IN (" + placeholders(chunk.size()) + ")",
                            chunk.toArray())))
            {
                Integer key = Integer.valueOf(hit.getRow().getID());
                List<AjRefHit> hits = byRow.get(key);
                if (hits == null)
                {
                    hits = new ArrayList<AjRefHit>();
                    byRow.put(key, hits);
                }
                hits.add(hit);
            }
        }
        return byRow;
    }

    private static String placeholders(int count)
    {
        StringBuilder marks = new StringBuilder();
        for (int index = 0; index < count; index++)
        {
            marks.append(index == 0 ? "?" : ",?");
        }
        return marks.toString();
    }

    public List<AjDupGroup> duplicateGroups(AjScanRun run)
    {
        if (run == null || !run.isHasDetail())
        {
            return new ArrayList<AjDupGroup>();
        }
        return Arrays.asList(ao.find(AjDupGroup.class,
                Query.select().where("RUN_ID = ?", run.getID())
                        .order("RECLAIMABLE_BYTES DESC")));
    }

    /**
     * 여러 해시의 구성원을 <b>한 번에</b> 읽는다.
     *
     * <p>그룹마다 따로 물으면 중복 화면 한 번에 조회가 그룹 수만큼 나간다. 근거 조회와
     * 같은 이유로 {@code IN} 으로 묶는다({@link #refsOf}).
     *
     * @return 해시 → 구성원. 해시 하나에 대한 목록은 스페이스키 순이다
     */
    public Map<String, List<AjAttachment>> attachmentsByHash(AjScanRun run, List<String> hashes)
    {
        Map<String, List<AjAttachment>> byHash = new HashMap<String, List<AjAttachment>>();
        if (run == null || hashes.isEmpty())
        {
            return byHash;
        }
        for (int from = 0; from < hashes.size(); from += IN_CHUNK)
        {
            List<String> chunk = hashes.subList(from, Math.min(from + IN_CHUNK, hashes.size()));
            for (AjAttachment row : ao.find(AjAttachment.class,
                    Query.select().where("RUN_ID = ? AND CONTENT_HASH IN ("
                                    + placeholders(chunk.size()) + ")",
                            merge(Integer.valueOf(run.getID()), chunk))
                            .order("SPACE_KEY ASC")))
            {
                List<AjAttachment> members = byHash.get(row.getContentHash());
                if (members == null)
                {
                    members = new ArrayList<AjAttachment>();
                    byHash.put(row.getContentHash(), members);
                }
                members.add(row);
            }
        }
        return byHash;
    }

    /** {@code where} 인자는 실행 번호가 먼저고 그다음이 {@code IN} 목록이다. */
    private static Object[] merge(Object first, List<?> rest)
    {
        Object[] args = new Object[rest.size() + 1];
        args[0] = first;
        for (int index = 0; index < rest.size(); index++)
        {
            args[index + 1] = rest.get(index);
        }
        return args;
    }

    /**
     * 한 번의 스캔을 저장한다.
     *
     * @param outcome 실패·취소면 null. 그 경우 수치 없는 행만 남는다
     */
    public AjScanRun saveRun(String status, Date startedAt, Date finishedAt, String startedBy,
                             ScanOutcome outcome, Settings settings, String errorMessage)
    {
        AjScanRun run = ao.create(AjScanRun.class);
        run.setStatus(status);
        run.setStartedAt(startedAt);
        run.setFinishedAt(finishedAt);
        run.setStartedBy(startedBy);
        run.setErrorMessage(errorMessage);
        run.setHasDetail(false);

        if (outcome == null)
        {
            run.save();
            prune(settings);
            return run;
        }

        run.setSkippedCount(outcome.skipped);
        run.setBodiesScanned(outcome.bodiesScanned);
        run.setParseFailures(outcome.parseFailures);
        run.setRefCount(outcome.refCount);
        run.setUnresolvedRefs(outcome.unresolvedRefs);
        run.setHistoryScanned(outcome.historyScanned);
        run.save();

        Map<String, SpaceTotals> spaces = writeAttachments(run, outcome);
        writeSpaceStats(run, spaces);
        writeDuplicates(run, outcome);

        long latestBytes = 0;
        long oldVersionBytes = 0;
        int fileCount = 0;
        int oldVersionCount = 0;
        int orphanCount = 0;
        long orphanBytes = 0;
        for (SpaceTotals totals : spaces.values())
        {
            fileCount += totals.fileCount;
            latestBytes += totals.latestBytes;
            oldVersionBytes += totals.oldVersionBytes;
            oldVersionCount += totals.oldVersionCount;
            orphanCount += totals.orphanCount;
            orphanBytes += totals.orphanBytes;
        }
        run.setFileCount(fileCount);
        run.setLatestBytes(latestBytes);
        run.setOldVersionBytes(oldVersionBytes);
        run.setOldVersionCount(oldVersionCount);
        run.setTotalBytes(latestBytes + oldVersionBytes);
        run.setOrphanCount(orphanCount);
        run.setOrphanBytes(orphanBytes);
        if (outcome.duplicates != null)
        {
            run.setDuplicateGroups(outcome.duplicates.groups.size());
            run.setDuplicateReclaimable(outcome.duplicates.reclaimableBytes());
            run.setHashedBytes(outcome.duplicates.hashedBytes);
            run.setHashBudgetHit(outcome.duplicates.budgetHit);
        }
        run.setHasDetail(true);
        run.save();

        // 상세는 여기서 비로소 정리한다 — 새 결과가 커밋된 뒤다.
        dropDetailExcept(run);
        prune(settings);
        return run;
    }

    private Map<String, SpaceTotals> writeAttachments(AjScanRun run, ScanOutcome outcome)
    {
        Map<String, SpaceTotals> spaces = new LinkedHashMap<String, SpaceTotals>();
        for (AttachmentFacts facts : outcome.facts)
        {
            SpaceTotals totals = spaces.get(facts.spaceKey);
            if (totals == null)
            {
                totals = new SpaceTotals(facts.spaceKey, facts.spaceName);
                spaces.put(facts.spaceKey, totals);
            }
            totals.fileCount++;
            totals.latestBytes += facts.latestBytes;
            totals.oldVersionBytes += facts.oldVersionBytes;
            totals.oldVersionCount += facts.oldVersionCount;
            if (facts.label == Label.ORPHAN)
            {
                totals.orphanCount++;
                totals.orphanBytes += facts.latestBytes;
            }
            if (facts.label == Label.RISKY)
            {
                totals.riskyCount++;
            }
            if (Badge.DUPLICATE.isIn(facts.badges))
            {
                totals.duplicateCount++;
            }

            AjAttachment row = ao.create(AjAttachment.class);
            row.setRun(run);
            row.setAttachmentId(facts.attachmentId);
            row.setFileName(clip(facts.fileName));
            row.setExtension(clip(facts.extension));
            row.setSpaceKey(clip(facts.spaceKey));
            row.setContainerId(facts.containerId);
            row.setContainerTitle(clip(facts.containerTitle));
            row.setContainerType(clip(facts.containerType));
            row.setContainerStatus(clip(facts.containerStatus));
            row.setLatestBytes(facts.latestBytes);
            row.setVersionCount(facts.versionCount);
            row.setOldVersionBytes(facts.oldVersionBytes);
            row.setOldVersionCount(facts.oldVersionCount);
            row.setLastModified(facts.lastModified);
            row.setMediaType(clip(facts.mediaType));
            row.setContentHash(facts.contentHash);
            row.setLabel(facts.label == null ? Label.ORPHAN.code() : facts.label.code());
            row.setBadges(facts.badges);
            row.setRefCount(facts.refs.size());
            row.save();

            // 근거는 상한까지만 저장한다. 표지 이미지 하나가 수천 페이지에서 참조되는
            // 경우가 실제로 있고, 전부 적어도 화면에서 읽히지 않으면서 행만 폭증한다.
            int written = 0;
            for (RefSource source : facts.refs)
            {
                if (written++ >= AjRefHit.MAX_PER_ATTACHMENT)
                {
                    break;
                }
                AjRefHit hit = ao.create(AjRefHit.class);
                hit.setRow(row);
                hit.setKind(source.kind.code());
                hit.setContentId(source.contentId);
                hit.setTitle(clip(source.title));
                hit.setSpaceKey(clip(source.spaceKey));
                hit.setContentType(clip(source.contentType));
                hit.save();
            }
        }
        return spaces;
    }

    private void writeSpaceStats(AjScanRun run, Map<String, SpaceTotals> spaces)
    {
        for (SpaceTotals totals : spaces.values())
        {
            AjSpaceStat stat = ao.create(AjSpaceStat.class);
            stat.setRun(run);
            stat.setSpaceKey(totals.spaceKey);
            stat.setSpaceName(clip(totals.spaceName));
            stat.setFileCount(totals.fileCount);
            stat.setLatestBytes(totals.latestBytes);
            stat.setOldVersionBytes(totals.oldVersionBytes);
            stat.setOldVersionCount(totals.oldVersionCount);
            stat.setTotalBytes(totals.totalBytes());
            stat.setOrphanCount(totals.orphanCount);
            stat.setOrphanBytes(totals.orphanBytes);
            stat.setRiskyCount(totals.riskyCount);
            stat.setDuplicateCount(totals.duplicateCount);
            stat.save();
        }
    }

    private void writeDuplicates(AjScanRun run, ScanOutcome outcome)
    {
        if (outcome.duplicates == null)
        {
            return;
        }
        for (DuplicateFinder.Group group : outcome.duplicates.groups)
        {
            AjDupGroup row = ao.create(AjDupGroup.class);
            row.setRun(run);
            row.setContentHash(group.hash);
            row.setFileCount(group.members.size());
            row.setUnitBytes(group.unitBytes);
            row.setReclaimableBytes(group.reclaimableBytes());
            row.setExact(group.exact);
            row.save();
        }
    }

    /** 이 실행 말고 다른 실행의 상세 행을 지운다. */
    private void dropDetailExcept(AjScanRun keep)
    {
        for (AjScanRun run : ao.find(AjScanRun.class, Query.select().where("HAS_DETAIL = ?", true)))
        {
            if (run.getID() == keep.getID())
            {
                continue;
            }
            deleteDetail(run);
            run.setHasDetail(false);
            run.save();
        }
    }

    /**
     * 한 실행의 상세 행을 지운다.
     *
     * <p>{@code ao.delete} 를 행마다 부르면 첨부 10 만 건에 삭제문이 20 만 개 나간다.
     * {@code deleteWithSQL} 은 한 문장으로 지운다. 근거 행에는 실행 번호가 없으므로
     * 첨부 행 ID 를 {@code IN} 으로 끊어 넘긴다.
     */
    private void deleteDetail(AjScanRun run)
    {
        List<Integer> ids = new ArrayList<Integer>();
        for (AjAttachment row : ao.find(AjAttachment.class,
                Query.select("ID").where("RUN_ID = ?", run.getID())))
        {
            ids.add(Integer.valueOf(row.getID()));
        }
        for (int from = 0; from < ids.size(); from += IN_CHUNK)
        {
            List<Integer> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            ao.deleteWithSQL(AjRefHit.class,
                    "ROW_ID IN (" + placeholders(chunk.size()) + ")", chunk.toArray());
        }
        ao.deleteWithSQL(AjAttachment.class, "RUN_ID = ?", Integer.valueOf(run.getID()));
        ao.deleteWithSQL(AjDupGroup.class, "RUN_ID = ?", Integer.valueOf(run.getID()));
    }

    private void prune(Settings settings)
    {
        AjScanRun[] runs = ao.find(AjScanRun.class, Query.select().order("ID DESC"));
        for (int index = settings.keepRuns; index < runs.length; index++)
        {
            deleteDetail(runs[index]);
            ao.deleteWithSQL(AjSpaceStat.class, "RUN_ID = ?",
                    Integer.valueOf(runs[index].getID()));
            ao.delete(runs[index]);
        }
    }

    /** AO 의 기본 문자열 열은 255자다. 잘라 넣지 않으면 저장이 통째로 실패한다. */
    private static String clip(String value)
    {
        if (value == null)
        {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
