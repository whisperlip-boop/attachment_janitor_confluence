package co.bskim.confluence.attachjanitor.scan;

import co.bskim.confluence.attachjanitor.analyze.DuplicateFinder;
import co.bskim.confluence.attachjanitor.analyze.Judge;
import co.bskim.confluence.attachjanitor.analyze.ReferenceIndex;
import co.bskim.confluence.attachjanitor.model.AttachmentFacts;
import co.bskim.confluence.attachjanitor.model.AttachmentRef;
import co.bskim.confluence.attachjanitor.model.RefSource;
import co.bskim.confluence.attachjanitor.model.ScanOutcome;
import co.bskim.confluence.attachjanitor.model.ScanProgress;
import co.bskim.confluence.attachjanitor.settings.Settings;
import co.bskim.confluence.attachjanitor.settings.SettingsStore;
import co.bskim.confluence.attachjanitor.store.ScanStore;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.pages.AttachmentStatisticsDTO;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.spaces.Space;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 스캔 한 번. 다섯 단계로 나뉜다 — 첨부 · 본문 · 대조 · 중복 · 저장.
 *
 * <p>스캔은 인스턴스 전체가 단위다. 기획서 4장은 스페이스를 단위로 삼았지만 7.8.1 에는
 * 스페이스 단위 첨부 조회가 없다(docs/00-환경실측.md V3) — 스페이스 버튼을 만들면 같은
 * 전체 스트림을 돌고 대부분을 버리게 되어 비용은 같고 결과만 적어진다. 참조 인덱스도
 * 인스턴스 전체여야 한다: 다른 스페이스의 페이지가 이 스페이스의 첨부를 참조할 수 있어서
 * 스페이스별로 끊으면 그 참조를 놓치고 [고아] 로 잘못 찍는다.
 *
 * <p>읽기와 쓰기는 <b>다른 트랜잭션</b>이다. Confluence 의 Active Objects 는 호스트
 * Hibernate 세션 위에서 돌기 때문에 백그라운드 스레드의 AO 쓰기도 감싸야 하는데,
 * 하나로 묶으면 대형 인스턴스에서 스캔이 끝날 때까지 트랜잭션이 열려 있다(실측 1번).
 */
@Named
public class ScanService
{
    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    /** 진행률을 이 간격으로 발행한다. 사이에는 폴링하는 화면이 낡은 수를 본다. */
    private static final int PROGRESS_EVERY = 200;

    private final AttachmentManager attachmentManager;
    private final SpaceManager spaceManager;
    private final PageManager pageManager;
    private final ScanStore store;
    private final SettingsStore settingsStore;
    private final TransactionTemplate transactionTemplate;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelRequested;
    private volatile ScanProgress progress = ScanProgress.idle();

    @Inject
    public ScanService(@ComponentImport AttachmentManager attachmentManager,
                       @ComponentImport SpaceManager spaceManager,
                       @ComponentImport PageManager pageManager,
                       ScanStore store, SettingsStore settingsStore,
                       @ComponentImport TransactionTemplate transactionTemplate)
    {
        this.attachmentManager = attachmentManager;
        this.spaceManager = spaceManager;
        this.pageManager = pageManager;
        this.store = store;
        this.settingsStore = settingsStore;
        this.transactionTemplate = transactionTemplate;
    }

    public ScanProgress progress()
    {
        return progress;
    }

    /** @return 이미 스캔 중이면 false. 호출자는 "시작했다"가 아니라 409 로 답한다 */
    public boolean start(final String startedBy)
    {
        if (!running.compareAndSet(false, true))
        {
            return false;
        }
        cancelRequested = false;
        progress = new ScanProgress(ScanProgress.State.RUNNING,
                ScanProgress.Phase.ATTACHMENTS, 0, expectedAttachments(),
                new Date(), null, null);

        Thread thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    scan(startedBy);
                }
                finally
                {
                    running.set(false);
                }
            }
        }, "attachment-janitor-scan");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public void cancel()
    {
        cancelRequested = true;
    }

    private int expectedAttachments()
    {
        try
        {
            Optional<AttachmentStatisticsDTO> statistics =
                    attachmentManager.getAttachmentStatistics();
            return statistics.isPresent() ? statistics.get().getCurrentAttachmentsCount() : 0;
        }
        catch (Throwable error)
        {
            // 진행률의 분모 하나 때문에 스캔을 막지 않는다.
            log.warn("Attachment Janitor: attachment statistics unavailable", error);
            return 0;
        }
    }

    private void scan(String startedBy)
    {
        final Date startedAt = progress.startedAt;
        final Settings settings = settingsStore.load();
        try
        {
            final ScanOutcome outcome = transactionTemplate.execute(
                    new TransactionCallback<ScanOutcome>()
                    {
                        @Override
                        public ScanOutcome doInTransaction()
                        {
                            return collect(settings);
                        }
                    });

            if (cancelRequested)
            {
                // 취소도 행으로 남긴다. 흔적이 없으면 화면을 다시 그리는 순간 취소가
                // 없었던 일이 되고 이전 스캔의 표만 남는다.
                save(ScanStore.CANCELLED, startedAt, startedBy, null, settings, null);
                progress = progress.ended(ScanProgress.State.CANCELLED, null);
                return;
            }

            progress = progress.at(ScanProgress.Phase.SAVE, 0, outcome.facts.size());
            save(ScanStore.DONE, startedAt, startedBy, outcome, settings, null);
            progress = progress.ended(ScanProgress.State.DONE, null);
        }
        catch (Throwable error)
        {
            // Throwable 이다. 7.8.1 로 컴파일해 7.12.3 에서 돌리므로 현실적인 실패는
            // NoSuchMethodError / NoClassDefFoundError — 전부 Error 다. 안 잡으면 진행률이
            // RUNNING 에 박혀 화면이 영구 폴링한다.
            log.warn("Attachment Janitor: scan failed", error);
            String message = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            try
            {
                save(ScanStore.FAILED, startedAt, startedBy, null, settings, message);
            }
            catch (Throwable storeError)
            {
                log.warn("Attachment Janitor: could not store the failed run either",
                        storeError);
            }
            progress = progress.ended(ScanProgress.State.FAILED, message);
        }
    }

    private ScanOutcome collect(Settings settings)
    {
        ScanOutcome outcome = new ScanOutcome();
        outcome.historyScanned = settings.scanHistory;

        collectAttachments(outcome);
        if (cancelRequested)
        {
            return outcome;
        }

        ReferenceIndex index = collectBodies(settings, outcome);
        if (cancelRequested)
        {
            return outcome;
        }

        match(outcome, index, settings);
        if (cancelRequested)
        {
            return outcome;
        }

        progress = progress.at(ScanProgress.Phase.DUPLICATES, 0, outcome.facts.size());
        outcome.duplicates = DuplicateFinder.find(outcome.facts, settings,
                new DuplicateFinder.DataSource()
                {
                    @Override
                    public InputStream open(long attachmentId)
                    {
                        // 파일시스템을 읽는 유일한 자리다. V2 에서 "디스크를 건드리지
                        // 않는다"고 정했고, 중복 판정만 그 예외다 — 내용이 같은지는
                        // 메타데이터로 알 수 없기 때문이다.
                        return attachmentManager.getAttachmentData(
                                attachmentManager.getAttachment(attachmentId));
                    }
                });
        return outcome;
    }

    private void collectAttachments(ScanOutcome outcome)
    {
        Iterator<Attachment> attachments =
                attachmentManager.getAttachmentDao().findLatestVersionsIterator();
        int processed = 0;
        while (attachments.hasNext())
        {
            if (cancelRequested)
            {
                return;
            }
            Attachment attachment = attachments.next();
            try
            {
                outcome.facts.add(factsOf(attachment));
            }
            catch (Throwable error)
            {
                // 첨부 하나가 나머지 십만 개를 잃게 하지 않는다. 센 수는 화면에 나오므로
                // 합계가 완전한 척하지 않는다.
                outcome.skipped++;
                if (outcome.skipped <= 5)
                {
                    log.warn("Attachment Janitor: skipping attachment {}",
                            attachment == null ? "?" : attachment.getId(), error);
                }
            }
            processed++;
            if (processed % PROGRESS_EVERY == 0)
            {
                progress = progress.at(ScanProgress.Phase.ATTACHMENTS, processed,
                        progress.expected);
            }
        }
    }

    private AttachmentFacts factsOf(Attachment attachment)
    {
        AttachmentFacts facts = new AttachmentFacts();
        facts.attachmentId = attachment.getId();
        facts.fileName = AttachmentRef.normalise(attachment.getFileName());
        facts.extension = AttachmentFacts.extensionOf(facts.fileName);
        facts.latestBytes = attachment.getFileSize();
        facts.versionCount = attachment.getVersion();
        facts.lastModified = attachment.getLastModificationDate();
        facts.mediaType = attachment.getMediaType() == null ? "" : attachment.getMediaType();

        Space space = attachment.getSpace();
        if (space != null)
        {
            facts.spaceKey = space.getKey();
            facts.spaceName = space.getName();
        }

        ContentEntityObject container = attachment.getContainer();
        if (container != null)
        {
            facts.containerId = container.getId();
            facts.containerTitle = container.getTitle() == null ? "" : container.getTitle();
            facts.containerType = container.getType() == null ? "" : container.getType();
            facts.containerStatus = container.getContentStatus() == null
                    ? "" : container.getContentStatus();
        }

        // 구버전 조회는 버전이 둘 이상일 때만 한다. 호출 1회가 약 14ms 라
        // 전 첨부에 부르면 스캔이 십 수 배 느려진다(실측 4번).
        if (facts.versionCount > 1)
        {
            for (Attachment older : attachmentManager.getPreviousVersions(attachment))
            {
                facts.oldVersionCount++;
                facts.oldVersionBytes += older.getFileSize();
            }
        }
        return facts;
    }

    private ReferenceIndex collectBodies(Settings settings, ScanOutcome outcome)
    {
        ReferenceIndex index = new ReferenceIndex();
        // PageManager 가 ContentEntityManager 를 상속한다. 둘을 따로 주입하면 스프링이
        // "같은 타입 빈이 둘"이라며 앱을 기동시키지 않는다(실측 12번).
        BodyWalker walker = new BodyWalker(pageManager, index);

        List<Space> spaces = spaceManager.getAllSpaces();
        int done = 0;
        for (Space space : spaces)
        {
            if (cancelRequested)
            {
                break;
            }
            try
            {
                walker.walkSpace(space, settings.scanHistory);
            }
            catch (Throwable error)
            {
                outcome.parseFailures++;
                log.warn("Attachment Janitor: could not walk space {}", space.getKey(), error);
            }
            done++;
            progress = progress.at(ScanProgress.Phase.BODIES, done, spaces.size());
        }

        index.resolve();
        outcome.bodiesScanned = walker.bodiesScanned();
        outcome.parseFailures += walker.parseFailures();
        outcome.refCount = index.total();
        outcome.unresolvedRefs = index.unresolved();
        return index;
    }

    private void match(ScanOutcome outcome, ReferenceIndex index, Settings settings)
    {
        Date now = new Date();
        int done = 0;
        for (AttachmentFacts facts : outcome.facts)
        {
            List<RefSource> hits = index.hitsFor(facts.containerId, facts.fileName);
            facts.refs.addAll(hits);
            facts.label = Judge.label(facts);
            facts.badges = Judge.badges(facts, settings, now);
            done++;
            if (done % PROGRESS_EVERY == 0)
            {
                progress = progress.at(ScanProgress.Phase.MATCH, done, outcome.facts.size());
            }
        }
    }

    private void save(final String status, final Date startedAt, final String startedBy,
                      final ScanOutcome outcome, final Settings settings, final String errorMessage)
    {
        transactionTemplate.execute(new TransactionCallback<Void>()
        {
            @Override
            public Void doInTransaction()
            {
                store.saveRun(status, startedAt, new Date(), startedBy, outcome, settings,
                        errorMessage);
                return null;
            }
        });
    }
}
