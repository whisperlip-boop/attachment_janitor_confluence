package co.bskim.confluence.attachjanitor.rest;

import co.bskim.confluence.attachjanitor.ao.AjAttachment;
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
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 화면의 데이터와 스캔 제어.
 *
 * <p><b>사람이 읽는 문장을 내보내지 않는다.</b> 라벨과 배지는 코드({@code orphan},
 * {@code oldversions})로 내보내고 문구는 브라우저가 서블릿에서 받은 번들로 만든다.
 * 그래야 같은 결과 한 벌을 한국어 화면과 영어 화면이 함께 볼 수 있다.
 *
 * <p>여기에는 {@code @WebSudoRequired} 를 붙이지 않는다. 스캔이 웹수도 제한 시간보다
 * 오래 걸리면 폴링 요청이 도중에 튕기고, 관리자는 "눌렀는데 버튼이 죽었다"만 본다.
 * 화면을 그리는 서블릿이 웹수도를 지고, 여기서는 매 호출 관리자 권한을 확인한다.
 */
@Named
@Path("/report")
@Produces(MediaType.APPLICATION_JSON)
public class JanitorResource
{
    private final ScanService scanService;
    private final ScanStore store;
    private final SettingsStore settingsStore;
    private final AccessGuard access;

    @Inject
    public JanitorResource(ScanService scanService, ScanStore store,
                           SettingsStore settingsStore, AccessGuard access)
    {
        this.scanService = scanService;
        this.store = store;
        this.settingsStore = settingsStore;
        this.access = access;
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

        Json groups = Json.array();
        for (AjDupGroup group : store.duplicateGroups(complete))
        {
            Json members = Json.array();
            for (AjAttachment row : store.attachmentsWithHash(complete, group.getContentHash()))
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
            return Response.status(Response.Status.CONFLICT)
                    .entity(Json.object().put("error", "scan-already-running").end())
                    .build();
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
        for (AjDupGroup group : store.duplicateGroups(complete))
        {
            for (AjAttachment row : store.attachmentsWithHash(complete, group.getContentHash()))
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
                .put("stale", complete != null && latest != null
                        && complete.getID() != latest.getID())
                .putRaw("progress", progressJson(scanService.progress()));
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
