package co.bskim.confluence.attachjanitor.rest;

import co.bskim.confluence.attachjanitor.ao.AjAttachment;
import co.bskim.confluence.attachjanitor.ao.AjActionLog;
import co.bskim.confluence.attachjanitor.clean.CleanupService;
import co.bskim.confluence.attachjanitor.clean.VersionPlan;
import co.bskim.confluence.attachjanitor.lock.WorkLock;
import com.atlassian.confluence.security.websudo.WebSudoManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import co.bskim.confluence.attachjanitor.ao.AjDupGroup;
import co.bskim.confluence.attachjanitor.ao.AjRefHit;
import co.bskim.confluence.attachjanitor.ao.AjScanRun;
import co.bskim.confluence.attachjanitor.ao.AjSpaceStat;
import co.bskim.confluence.attachjanitor.model.Badge;
import co.bskim.confluence.attachjanitor.model.Label;
import co.bskim.confluence.attachjanitor.model.ScanProgress;
import co.bskim.confluence.attachjanitor.scan.ScanService;
import co.bskim.confluence.attachjanitor.settings.Settings;
import co.bskim.confluence.attachjanitor.settings.SettingsStore;
import co.bskim.confluence.attachjanitor.store.ScanStore;
import co.bskim.confluence.attachjanitor.web.AccessGuard;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * 화면의 데이터와 스캔 제어.
 *
 * <p><b>사람이 읽는 문장을 내보내지 않는다.</b> 라벨과 배지는 코드({@code orphan},
 * {@code oldversions})로 내보내고 문구는 브라우저가 서블릿에서 받은 번들로 만든다.
 * 그래야 같은 결과 한 벌을 한국어 화면과 영어 화면이 함께 볼 수 있다.
 *
 * <p><b>조회 엔드포인트에는 웹수도를 걸지 않는다.</b> 스캔이 웹수도 제한 시간보다
 * 오래 걸리면 폴링 요청이 도중에 튕기고, 관리자는 "눌렀는데 버튼이 죽었다"만 본다.
 * 화면을 그리는 서블릿이 웹수도를 지고, 여기서는 매 호출 관리자 권한을 확인한다.
 *
 * <p><b>지우는 엔드포인트는 반대다.</b> {@code /cleanup*} 은 웹수도를 확인하고 없으면
 * 401 에 {@code websudo} 표시를 붙여 돌려준다 — 화면이 그걸 보고 재인증으로 보낸다.
 * 되돌릴 수 없는 작업 앞에서 몇 초를 아끼지 않는다.
 */
@Named
@Path("/report")
@Produces(MediaType.APPLICATION_JSON)
public class JanitorResource
{
    /** 한 번에 받을 수 있는 첨부 수. 넘으면 요청 전체를 거절한다. */
    private static final int MAX_IDS = 5000;

    private final ScanService scanService;
    private final ScanStore store;
    private final SettingsStore settingsStore;
    private final AccessGuard access;
    private final CleanupService cleanupService;
    private final WebSudoManager webSudoManager;
    private final WorkLock workLock;

    @Inject
    public JanitorResource(ScanService scanService, ScanStore store,
                           SettingsStore settingsStore, AccessGuard access,
                           CleanupService cleanupService,
                           @ComponentImport WebSudoManager webSudoManager,
                           WorkLock workLock)
    {
        this.scanService = scanService;
        this.store = store;
        this.settingsStore = settingsStore;
        this.access = access;
        this.cleanupService = cleanupService;
        this.webSudoManager = webSudoManager;
        this.workLock = workLock;
    }

    // ---------------------------------------------------------------- 랭킹

    @GET
    @Path("/spaces")
    public Response spaces()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }

        // 실행이 둘인 것은 의도다. 배너는 마지막 시도를, 표는 마지막으로 끝난 실행을
        // 본다. 실패했다고 마지막으로 성공한 표를 치워 버리면 관리자는 아무것도 못 보고,
        // 날짜 없이 옛 표만 두면 방금 것으로 읽는다.
        AjScanRun latest = store.latestRun();
        AjScanRun complete = store.latestCompleteRun();

        Json rows = Json.array();
        for (AjSpaceStat stat : store.spaceStats(complete))
        {
            rows.add(Json.object()
                    .put("spaceKey", stat.getSpaceKey())
                    .put("spaceName", stat.getSpaceName())
                    .put("fileCount", stat.getFileCount())
                    .put("totalBytes", stat.getTotalBytes())
                    .put("latestBytes", stat.getLatestBytes())
                    .put("oldVersionBytes", stat.getOldVersionBytes())
                    .put("oldVersionCount", stat.getOldVersionCount())
                    .put("orphanCount", stat.getOrphanCount())
                    .put("orphanBytes", stat.getOrphanBytes())
                    .put("riskyCount", stat.getRiskyCount())
                    .put("duplicateCount", stat.getDuplicateCount())
                    .end());
        }
        return Response.ok(envelope(latest, complete).putRaw("spaces", rows.end()).end())
                .build();
    }

    // ---------------------------------------------------------------- 스페이스 상세

    @GET
    @Path("/space")
    public Response space(@QueryParam("key") String spaceKey)
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        AjScanRun latest = store.latestRun();
        AjScanRun complete = store.latestCompleteRun();

        List<AjAttachment> attachments =
                store.attachments(complete, spaceKey == null ? "" : spaceKey);
        // 근거는 한 번에 읽는다. 첨부마다 따로 물으면 큰 스페이스 화면 한 번에 조회가
        // 첨부 수만큼 나간다.
        Map<Integer, List<AjRefHit>> refs = store.refsOf(attachments);

        Json rows = Json.array();
        for (AjAttachment row : attachments)
        {
            rows.add(attachmentJson(row, refs.get(Integer.valueOf(row.getID()))).end());
        }
        return Response.ok(envelope(latest, complete)
                .put("spaceKey", spaceKey == null ? "" : spaceKey)
                .putRaw("attachments", rows.end()).end()).build();
    }

    // ---------------------------------------------------------------- 중복

    @GET
    @Path("/duplicates")
    public Response duplicates()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        AjScanRun latest = store.latestRun();
        AjScanRun complete = store.latestCompleteRun();

        List<AjDupGroup> dupGroups = store.duplicateGroups(complete);
        List<String> hashes = new ArrayList<String>();
        for (AjDupGroup group : dupGroups)
        {
            hashes.add(group.getContentHash());
        }
        // 구성원도 한 번에 읽는다. 그룹마다 따로 물으면 그룹 수만큼 조회가 나간다.
        Map<String, List<AjAttachment>> membersByHash = store.attachmentsByHash(complete, hashes);

        Json groups = Json.array();
        for (AjDupGroup group : dupGroups)
        {
            Json members = Json.array();
            List<AjAttachment> rows = membersByHash.get(group.getContentHash());
            for (AjAttachment row : rows == null ? new ArrayList<AjAttachment>() : rows)
            {
                members.add(attachmentJson(row).end());
            }
            groups.add(Json.object()
                    .put("hash", group.getContentHash())
                    .put("fileCount", group.getFileCount())
                    .put("unitBytes", group.getUnitBytes())
                    .put("reclaimableBytes", group.getReclaimableBytes())
                    // 약식 해시면 "중복"이 아니라 "중복 후보"다. 확인하지 않은 것을
                    // 확인했다고 말하지 않는다.
                    .put("exact", group.isExact())
                    .putRaw("members", members.end())
                    .end());
        }
        return Response.ok(envelope(latest, complete)
                .putRaw("groups", groups.end()).end()).build();
    }

    // ---------------------------------------------------------------- 스캔 제어

    @POST
    @Path("/scan")
    public Response startScan()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        if (!scanService.start(access.currentUserName()))
        {
            // 무엇이 막고 있는지 이름을 대지 않는다. 잠금을 쥔 쪽이 구버전 정리일 수도,
            // 클러스터의 다른 노드일 수도 있어서 여기서는 알 수 없다.
            return busy();
        }
        return Response.status(Response.Status.ACCEPTED)
                .entity(progressJson(scanService.progress())).build();
    }

    @GET
    @Path("/scan")
    public Response scanStatus()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        return Response.ok(progressJson(scanService.progress())).build();
    }

    @DELETE
    @Path("/scan")
    public Response cancelScan()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        scanService.cancel();
        return Response.ok(progressJson(scanService.progress())).build();
    }

    // ---------------------------------------------------------------- 설정

    @GET
    @Path("/settings")
    public Response settings()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        return Response.ok(settingsJson(settingsStore.load())).build();
    }

    @PUT
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveSettings(String body)
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        Settings current = settingsStore.load();
        Settings updated = new Settings(
                (int) JsonReader.number(body, "oldVersionCount", current.oldVersionCount),
                JsonReader.number(body, "oldVersionBytes", current.oldVersionBytes),
                JsonReader.number(body, "largeBytes", current.largeBytes),
                (int) JsonReader.number(body, "staleDays", current.staleDays),
                JsonReader.flag(body, "scanHistory", current.scanHistory),
                Settings.DuplicateMode.FULL.name().equalsIgnoreCase(
                        JsonReader.string(body, "duplicateMode", current.duplicateMode.name()))
                        ? Settings.DuplicateMode.FULL : Settings.DuplicateMode.QUICK,
                JsonReader.number(body, "duplicateByteBudget", current.duplicateByteBudget),
                (int) JsonReader.number(body, "keepRuns", current.keepRuns));
        settingsStore.save(updated);
        // 저장한 값을 그대로 돌려주지 않고 다시 읽는다 — 상·하한에 걸려 조정된 값을
        // 화면이 보아야 한다.
        return Response.ok(settingsJson(settingsStore.load())).build();
    }

    // ---------------------------------------------------------------- CSV

    @GET
    @Path("/spaces.csv")
    @Produces("text/csv;charset=UTF-8")
    public Response spacesCsv()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        csv.append("space_key,space_name,files,total_bytes,latest_bytes,old_version_bytes,"
                + "old_versions,orphans,orphan_bytes,risky,duplicates\n");
        for (AjSpaceStat stat : store.spaceStats(store.latestCompleteRun()))
        {
            csv.append(csvCell(stat.getSpaceKey())).append(',')
                    .append(csvCell(stat.getSpaceName())).append(',')
                    .append(stat.getFileCount()).append(',')
                    .append(stat.getTotalBytes()).append(',')
                    .append(stat.getLatestBytes()).append(',')
                    .append(stat.getOldVersionBytes()).append(',')
                    .append(stat.getOldVersionCount()).append(',')
                    .append(stat.getOrphanCount()).append(',')
                    .append(stat.getOrphanBytes()).append(',')
                    .append(stat.getRiskyCount()).append(',')
                    .append(stat.getDuplicateCount()).append('\n');
        }
        return csvResponse(csv, "attachment-janitor-spaces.csv");
    }

    @GET
    @Path("/space.csv")
    @Produces("text/csv;charset=UTF-8")
    public Response spaceCsv(@QueryParam("key") String spaceKey)
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        csv.append("space_key,file_name,container_title,container_type,label,badges,"
                + "latest_bytes,versions,old_version_bytes,old_versions,last_modified,"
                + "media_type,references\n");
        for (AjAttachment row : store.attachments(store.latestCompleteRun(),
                spaceKey == null ? "" : spaceKey))
        {
            csv.append(csvCell(row.getSpaceKey())).append(',')
                    .append(csvCell(row.getFileName())).append(',')
                    .append(csvCell(row.getContainerTitle())).append(',')
                    .append(csvCell(row.getContainerType())).append(',')
                    .append(csvCell(row.getLabel())).append(',')
                    .append(csvCell(badgeCodes(row.getBadges()))).append(',')
                    .append(row.getLatestBytes()).append(',')
                    .append(row.getVersionCount()).append(',')
                    .append(row.getOldVersionBytes()).append(',')
                    .append(row.getOldVersionCount()).append(',')
                    .append(csvCell(iso(row.getLastModified()))).append(',')
                    .append(csvCell(row.getMediaType())).append(',')
                    .append(row.getRefCount()).append('\n');
        }
        return csvResponse(csv, "attachment-janitor-" + safeName(spaceKey) + ".csv");
    }

    @GET
    @Path("/duplicates.csv")
    @Produces("text/csv;charset=UTF-8")
    public Response duplicatesCsv()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        AjScanRun complete = store.latestCompleteRun();
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        csv.append("hash,exact,files,unit_bytes,reclaimable_bytes,space_key,file_name,"
                + "container_title\n");
        List<AjDupGroup> dupGroups = store.duplicateGroups(complete);
        List<String> hashes = new ArrayList<String>();
        for (AjDupGroup group : dupGroups)
        {
            hashes.add(group.getContentHash());
        }
        Map<String, List<AjAttachment>> membersByHash = store.attachmentsByHash(complete, hashes);

        for (AjDupGroup group : dupGroups)
        {
            List<AjAttachment> rows = membersByHash.get(group.getContentHash());
            for (AjAttachment row : rows == null ? new ArrayList<AjAttachment>() : rows)
            {
                csv.append(csvCell(group.getContentHash())).append(',')
                        .append(group.isExact()).append(',')
                        .append(group.getFileCount()).append(',')
                        .append(group.getUnitBytes()).append(',')
                        .append(group.getReclaimableBytes()).append(',')
                        .append(csvCell(row.getSpaceKey())).append(',')
                        .append(csvCell(row.getFileName())).append(',')
                        .append(csvCell(row.getContainerTitle())).append('\n');
            }
        }
        return csvResponse(csv, "attachment-janitor-duplicates.csv");
    }


    // ---------------------------------------------------------------- 구버전 정리

    /**
     * 무엇이 지워질지 <b>지금 읽어</b> 돌려준다. 아무것도 지우지 않는다.
     *
     * <p>저장된 스캔 결과를 쓰지 않는 것이 요점이다 — 스캔은 스냅샷이고 며칠 전 것일
     * 수 있다. 화면은 이 응답을 보여주고 확인을 받은 뒤에야 실행을 부른다.
     */
    @POST
    @Path("/cleanup/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response cleanupPreview(@Context HttpServletRequest request, String body)
    {
        Response denial = mutationGuard(request);
        if (denial != null)
        {
            return denial;
        }
        List<Long> ids = JsonReader.numbers(body, "ids", MAX_IDS);
        if (ids.isEmpty())
        {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Json.object().put("error", "no-ids").end()).build();
        }
        int keep = (int) Math.max(VersionPlan.MIN_KEEP, JsonReader.number(body, "keep", 3));
        return Response.ok(previewJson(cleanupService.preview(ids, keep))).build();
    }

    /**
     * 지운다. <b>되돌릴 수 없다.</b>
     *
     * <p>클라이언트가 보낸 목록을 그대로 믿지 않는다 — 서비스가 실행 직전에 다시 읽어
     * 미리보기와 다른 첨부는 건너뛴다. 그래서 이 엔드포인트는 미리보기를 한 번 더
     * 계산하고 그 결과로 실행한다.
     */
    @POST
    @Path("/cleanup")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response cleanup(@Context HttpServletRequest request, String body)
    {
        Response denial = mutationGuard(request);
        if (denial != null)
        {
            return denial;
        }
        List<Long> ids = JsonReader.numbers(body, "ids", MAX_IDS);
        if (ids.isEmpty())
        {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Json.object().put("error", "no-ids").end()).build();
        }
        // 화면이 미리보기에서 본 것. "12:1,2,3" 꼴이다. 이게 없으면 실행하지 않는다 —
        // 사용자가 확인하지 않은 삭제를 하지 않겠다는 뜻이다.
        Map<Long, String> expected = new HashMap<Long, String>();
        for (String pair : JsonReader.plainStrings(body, "expect", MAX_IDS))
        {
            int colon = pair.indexOf(':');
            if (colon <= 0)
            {
                continue;
            }
            try
            {
                expected.put(Long.valueOf(pair.substring(0, colon)), pair.substring(colon + 1));
            }
            catch (NumberFormatException ignored)
            {
                // 못 읽은 항목은 기대값이 없는 것이 되고, 그 첨부는 건너뛴다.
            }
        }
        if (expected.isEmpty())
        {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Json.object().put("error", "no-expectation").end()).build();
        }

        int keep = (int) Math.max(VersionPlan.MIN_KEEP, JsonReader.number(body, "keep", 3));

        // 요청을 다 읽은 뒤에 잠금을 잡는다 — 형식이 틀린 요청이 잠금을 쥐지 않게.
        // 스캔이 도는 중에는 지우지 않는다: 스캔이 읽는 것과 우리가 지우는 것이 같은 행이라
        // 결과가 반쯤 옛것이 되고, 그 실행은 아직 완료 전이라 markStale() 이 표시하지도
        // 못한다. 노드가 둘 이상이면 이 배타는 잠금으로만 성립한다.
        if (!workLock.tryAcquire("cleanup"))
        {
            return busy();
        }
        CleanupService.Result result;
        try
        {
            CleanupService.Preview preview = cleanupService.preview(ids, keep);
            result = cleanupService.execute(preview, expected, access.currentUserName());

            // 저장된 스캔 결과의 구버전 수치는 이제 틀렸다. 다음 스캔까지 그렇다고 적는다.
            store.markStale();
        }
        finally
        {
            workLock.release();
        }

        Json done = Json.array();
        for (CleanupService.Done item : result.done)
        {
            done.add(Json.object()
                    .put("attachmentId", item.attachmentId)
                    .put("versionsRemoved", item.versionsRemoved)
                    .put("bytesRemoved", item.bytesRemoved)
                    .put("outcome", item.outcome)
                    .end());
        }
        return Response.ok(Json.object()
                .putRaw("done", done.end())
                .put("batchId", result.batchId)
                .put("filesDone", result.filesDone)
                .put("filesSkipped", result.filesSkipped)
                .put("filesFailed", result.filesFailed)
                .put("versionsRemoved", result.versionsRemoved)
                .put("bytesRemoved", result.bytesRemoved)
                .put("keep", keep)
                .end()).build();
    }

    /** 최근 실행 기록. 되돌릴 수 없는 작업이라 흔적을 볼 수 있어야 한다. */
    @GET
    @Path("/cleanup/log")
    public Response cleanupLog()
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        Json rows = Json.array();
        for (AjActionLog row : store.recentActions())
        {
            rows.add(Json.object()
                    .put("batchId", row.getBatchId())
                    .put("at", iso(row.getAt()))
                    .put("requestedBy", row.getRequestedBy())
                    .put("spaceKey", row.getSpaceKey())
                    .put("fileName", row.getFileName())
                    .put("containerId", row.getContainerId())
                    .put("keepVersions", row.getKeepVersions())
                    .put("versionsRemoved", row.getVersionsRemoved())
                    .put("bytesRemoved", row.getBytesRemoved())
                    .put("versionNumbers", row.getVersionNumbers())
                    .put("outcome", row.getOutcome())
                    .put("detail", row.getDetail())
                    .end());
        }
        return Response.ok(Json.object().putRaw("actions", rows.end()).end()).build();
    }

    private static String previewJson(CleanupService.Preview preview)
    {
        Json items = Json.array();
        for (CleanupService.Item item : preview.items)
        {
            StringBuilder numbers = new StringBuilder();
            for (Integer version : item.removeVersions)
            {
                numbers.append(numbers.length() == 0 ? "" : ",").append(version);
            }
            items.add(Json.object()
                    .put("attachmentId", item.attachmentId)
                    .put("fileName", item.fileName)
                    .put("spaceKey", item.spaceKey)
                    .put("containerId", item.containerId)
                    .put("containerTitle", item.containerTitle)
                    .put("currentVersion", item.currentVersion)
                    .put("removeCount", item.removeVersions.size())
                    .put("removeVersions", numbers.toString())
                    .put("removeBytes", item.removeBytes)
                    .put("problem", item.problem)
                    .end());
        }
        return Json.object()
                .put("keep", preview.keep)
                .put("fileCount", preview.fileCount())
                .put("versionCount", preview.versionCount())
                .put("bytes", preview.bytes())
                .putRaw("items", items.end())
                .end();
    }

    /**
     * 지우는 요청의 문지기. 관리자 확인 + <b>웹수도</b>.
     *
     * <p>웹수도가 없으면 401 에 {@code websudo} 표시를 붙인다. 화면이 그걸 보고
     * "다시 로그인하라"를 내고 재인증 화면으로 보낸다 — 조용히 실패하지 않는다.
     */
    private Response mutationGuard(HttpServletRequest request)
    {
        Response denial = guard();
        if (denial != null)
        {
            return denial;
        }
        if (webSudoManager.isEnabled()
                && !webSudoManager.hasValidSession(request.getSession(false)))
        {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Json.object().put("error", "websudo-required")
                            .put("websudo", true).end()).build();
        }
        return null;
    }

    // ---------------------------------------------------------------- 도우미

    private static Response csvResponse(StringBuilder csv, String fileName)
    {
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    private static String safeName(String value)
    {
        String text = value == null || value.isEmpty() ? "nospace" : value;
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static String badgeCodes(int bits)
    {
        StringBuilder out = new StringBuilder();
        for (Badge badge : Badge.values())
        {
            if (badge.isIn(bits))
            {
                if (out.length() > 0)
                {
                    out.append(' ');
                }
                out.append(badge.code());
            }
        }
        return out.toString();
    }

    static String csvCell(String value)
    {
        String text = value == null ? "" : value;
        // 앞글자가 =, +, -, @ 면 표계산기가 수식으로 읽는다. 파일명은 사용자가 정하므로
        // 스페이스 이름보다 이쪽이 더 현실적인 경로다.
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0)
        {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    /** 근거 없이 첨부 한 건. 중복 화면은 근거를 쓰지 않는다. */
    private Json attachmentJson(AjAttachment row)
    {
        return attachmentRow(row);
    }

    /**
     * @param hits 이 첨부의 근거. {@code null} 이면 근거가 0 건이라는 뜻이고 빈 배열이
     *             실린다 — 화면이 "근거 없음"과 "안 실림"을 구분할 필요가 없게 한다
     */
    private Json attachmentJson(AjAttachment row, List<AjRefHit> hits)
    {
        Json json = attachmentRow(row);
        Json refs = Json.array();
        if (hits != null)
        {
            for (AjRefHit hit : hits)
            {
                refs.add(Json.object()
                        .put("kind", hit.getKind())
                        .put("contentId", hit.getContentId())
                        .put("title", hit.getTitle())
                        .put("spaceKey", hit.getSpaceKey())
                        .put("contentType", hit.getContentType())
                        .end());
            }
        }
        json.putRaw("refs", refs.end());
        return json;
    }

    private Json attachmentRow(AjAttachment row)
    {
        Json json = Json.object()
                .put("attachmentId", row.getAttachmentId())
                .put("fileName", row.getFileName())
                .put("extension", row.getExtension())
                .put("spaceKey", row.getSpaceKey())
                .put("containerId", row.getContainerId())
                .put("containerTitle", row.getContainerTitle())
                .put("containerType", row.getContainerType())
                .put("latestBytes", row.getLatestBytes())
                .put("versionCount", row.getVersionCount())
                .put("oldVersionBytes", row.getOldVersionBytes())
                .put("oldVersionCount", row.getOldVersionCount())
                .put("lastModified", iso(row.getLastModified()))
                .put("mediaType", row.getMediaType())
                .put("label", row.getLabel())
                .put("badges", row.getBadges())
                .put("badgeCodes", badgeCodes(row.getBadges()))
                .put("refCount", row.getRefCount())
                // 정렬 기본값을 서버가 정해 보낸다. 라벨의 정리 순위는 열거형에만 있고
                // 화면이 그 순서를 다시 적으면 둘이 어긋난다.
                .put("cleanupRank", rankOf(row.getLabel()));
        return json;
    }

    private static int rankOf(String labelCode)
    {
        Label label = Label.byCode(labelCode);
        return label == null ? 0 : label.cleanupRank();
    }

    /** 모든 응답이 공유하는 머리. 화면이 어느 실행을 보고 있는지 늘 알 수 있어야 한다. */
    private Json envelope(AjScanRun latest, AjScanRun complete)
    {
        return Json.object()
                .putRaw("run", runJson(latest))
                .putRaw("shown", runJson(complete))
                // 표가 최신이 아닌 경우는 둘이다: 그 뒤에 다른 실행이 있었거나,
                // 구버전 정리가 돌아 수치가 실제와 어긋났거나.
                .put("stale", (complete != null && latest != null
                        && complete.getID() != latest.getID())
                        || (complete != null && complete.isSupersededByAction()))
                .putRaw("progress", progressJson(scanService.progress()));
    }

    /**
     * 잠금을 못 잡았을 때의 답. <b>무엇이 막고 있는지 적지 않는다</b> — 스캔일 수도 구버전
     * 정리일 수도 있고, 클러스터라면 이 노드가 아닐 수도 있어 여기서는 알 수 없다.
     * 없는 정보를 지어내느니 "다른 작업이 돌고 있다"만 말한다.
     */
    private static Response busy()
    {
        return Response.status(Response.Status.CONFLICT)
                .entity(Json.object().put("error", "busy").end()).build();
    }

    private Response guard()
    {
        if (!access.isLoggedIn())
        {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Json.object().put("error", "login-required").end()).build();
        }
        if (!access.isAdmin())
        {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Json.object().put("error", "admin-required").end()).build();
        }
        return null;
    }

    private static String settingsJson(Settings settings)
    {
        return Json.object()
                .put("oldVersionCount", settings.oldVersionCount)
                .put("oldVersionBytes", settings.oldVersionBytes)
                .put("largeBytes", settings.largeBytes)
                .put("staleDays", settings.staleDays)
                .put("scanHistory", settings.scanHistory)
                .put("duplicateMode", settings.duplicateMode.name())
                .put("duplicateByteBudget", settings.duplicateByteBudget)
                .put("keepRuns", settings.keepRuns)
                .end();
    }

    private static String runJson(AjScanRun run)
    {
        if (run == null)
        {
            return "null";
        }
        return Json.object()
                .put("status", run.getStatus())
                .put("startedAt", iso(run.getStartedAt()))
                .put("finishedAt", iso(run.getFinishedAt()))
                .put("startedBy", run.getStartedBy())
                .put("fileCount", run.getFileCount())
                .put("totalBytes", run.getTotalBytes())
                .put("latestBytes", run.getLatestBytes())
                .put("oldVersionBytes", run.getOldVersionBytes())
                .put("oldVersionCount", run.getOldVersionCount())
                .put("orphanCount", run.getOrphanCount())
                .put("orphanBytes", run.getOrphanBytes())
                .put("duplicateGroups", run.getDuplicateGroups())
                .put("duplicateReclaimable", run.getDuplicateReclaimable())
                .put("skippedCount", run.getSkippedCount())
                .put("bodiesScanned", run.getBodiesScanned())
                .put("parseFailures", run.getParseFailures())
                .put("refCount", run.getRefCount())
                .put("unresolvedRefs", run.getUnresolvedRefs())
                .put("hashedBytes", run.getHashedBytes())
                .put("hashBudgetHit", run.isHashBudgetHit())
                .put("historyScanned", run.isHistoryScanned())
                .put("hasDetail", run.isHasDetail())
                .put("errorMessage", run.getErrorMessage())
                .end();
    }

    private static String progressJson(ScanProgress progress)
    {
        return Json.object()
                .put("state", progress.state.name())
                .put("phase", progress.phase.name())
                .put("phaseKey", progress.phaseKey())
                .put("running", progress.isRunning())
                .put("processed", progress.processed)
                .put("expected", progress.expected)
                .put("bodies", progress.bodies)
                .put("percent", progress.percent())
                .put("startedAt", iso(progress.startedAt))
                .put("finishedAt", iso(progress.finishedAt))
                .put("message", progress.message)
                .end();
    }

    private static String iso(Date date)
    {
        if (date == null)
        {
            return null;
        }
        // 보는 사람을 위한 서식은 브라우저가 만든다. 전선에는 UTC 를 보내 둘이 어느 날인지를
        // 두고 다투지 않게 한다.
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }
}
