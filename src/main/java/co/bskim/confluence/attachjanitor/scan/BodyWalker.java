package co.bskim.confluence.attachjanitor.scan;

import co.bskim.confluence.attachjanitor.analyze.ReferenceIndex;
import co.bskim.confluence.attachjanitor.analyze.ReferenceScanner;
import co.bskim.confluence.attachjanitor.model.AttachmentRef;
import co.bskim.confluence.attachjanitor.model.RefKind;
import co.bskim.confluence.attachjanitor.model.RefSource;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.pages.AbstractPage;
import com.atlassian.confluence.pages.Comment;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.spaces.Space;
import com.atlassian.confluence.spaces.SpaceLogo;
import com.atlassian.confluence.spaces.SpaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;

/**
 * 인스턴스의 본문을 한 번 훑어 참조 인덱스를 채운다.
 *
 * <p>기획서 4.4 그대로다. 첨부마다 본문을 뒤지면 N×M 이 되므로 본문은 한 번만 읽는다.
 *
 * <p>본문 형식이 <b>둘</b>이라는 것이 실측 결과다(10번). 페이지·블로그·댓글은 XHTML
 * 저장 형식이고 스페이스 설명은 wiki 다. 하나의 파서에 넣으면 스페이스 설명은 전부
 * 분석 실패로 잡힌다.
 *
 * <p>과거 버전 본문은 기본으로 읽지 않는다. 읽으면 [이력 참조] 를 판정할 수 있지만
 * 버전 수만큼 조회가 늘어난다. 안 읽었을 때 화면은 [고아] 를 "고아(이력 미검사)" 로
 * 표기한다 — <b>검사하지 않은 것을 검사해서 없다고 말하지 않는다.</b>
 */
public final class BodyWalker
{
    private static final Logger log = LoggerFactory.getLogger(BodyWalker.class);

    /**
     * 페이지마다 바깥과 주고받는 통로.
     *
     * <p>스페이스 <b>사이</b>에서만 확인하면 페이지 500개짜리 스페이스 하나가 몇 분씩
     * 걸리는 동안 진행률이 멈춰 있고 취소도 안 먹는다. 실제로 그랬다(실측 24번).
     */
    public interface Watcher
    {
        /** true 면 이 스페이스 순회를 즉시 그만둔다. */
        boolean cancelled();

        /** 본문을 하나 읽을 때마다. 총계는 모른다 — 스페이스마다 페이지 수가 다르다. */
        void scanned(int bodies);
    }

    public static final Watcher SILENT = new Watcher()
    {
        @Override
        public boolean cancelled()
        {
            return false;
        }

        @Override
        public void scanned(int bodies)
        {
        }
    };

    /** 한 컨텐츠의 과거 버전을 이만큼까지만 거슬러 올라간다. */
    private static final int MAX_HISTORY = 200;

    private final PageManager pageManager;
    private final SpaceManager spaceManager;
    private final ReferenceIndex index;
    private Watcher watcher = SILENT;

    private int bodiesScanned;
    private int parseFailures;

    /**
     * @param pageManager 과거 버전 조회에도 이걸 쓴다. {@code PageManager} 가
     *                    {@code ContentEntityManager} 를 상속하므로 둘을 따로 주입하면
     *                    스프링이 같은 타입 빈이 둘이라며 앱을 기동시키지 않는다(실측 12번)
     */
    public BodyWalker(PageManager pageManager, SpaceManager spaceManager, ReferenceIndex index)
    {
        this.pageManager = pageManager;
        this.spaceManager = spaceManager;
        this.index = index;
    }

    public void watch(Watcher watcher)
    {
        this.watcher = watcher == null ? SILENT : watcher;
    }

    public int bodiesScanned()
    {
        return bodiesScanned;
    }

    /** 읽지 못한 본문 수. 0 이 아니면 라벨이 실제보다 [고아] 쪽으로 기운다. */
    public int parseFailures()
    {
        return parseFailures;
    }

    /**
     * 스페이스 하나의 본문을 전부 읽는다.
     *
     * @param scanHistory 과거 버전 본문까지 읽을지
     */
    public void walkSpace(Space space, boolean scanHistory)
    {
        String spaceKey = space.getKey();

        // 스페이스 설명은 wiki 다. 여기에도 첨부가 붙는다(스페이스 로고 등, 실측 V6).
        ContentEntityObject description = space.getDescription();
        if (description != null)
        {
            index.registerContent(spaceKey, description.getTitle(), description.getId());
            scanBody(ReferenceScanner.scanWiki(description.getBodyAsString()),
                    description.getId(), spaceKey,
                    new RefSource(RefKind.SPACE_DESCRIPTION, description.getId(),
                            space.getName(), spaceKey, "spacedescription"));
            registerLogo(space, description);
        }

        walkPages(pageManager.getPages(space, true), spaceKey, scanHistory);
        walkPages(pageManager.getBlogPosts(space, true), spaceKey, scanHistory);
    }


    /**
     * 스페이스 로고를 참조 하나로 만들어 넣는다.
     *
     * <p>로고는 SPACEDESCRIPTION 컨테이너에 붙은 첨부인데 <b>어느 본문도 그것을 가리키지
     * 않는다</b>(실측 27번). 그대로 두면 앱이 스페이스 로고를 [고아] 로 찍어 "아무도 안
     * 쓴다"고 말하게 된다 — 지우면 스페이스 머리글이 깨지는데도.
     *
     * <p>파일명을 추측하지 않는다. {@code SpaceLogo#getDownloadPath()} 가 Confluence
     * 자신의 답이고 거기서 이름을 떼어 쓴다. 같은 컨테이너에 붙었지만 로고가 아닌 파일은
     * 그대로 [고아] 로 둔다 — 그건 정말로 안 쓰이는 파일이다.
     *
     * <p><b>{@code ownerContentId} 는 반드시 스페이스 설명의 id 여야 한다.</b> 로고 첨부가
     * 붙어 있는 컨테이너가 바로 그것이고, 그래야 {@link ReferenceIndex#resolve()} 가 이
     * 참조를 own-container 로 풀어 {@code Judge} 가 [활성] 을 준다. 다른 id 를 넣으면
     * "바깥에서 참조됨"이 되어 조용히 [위험] 으로 바뀐다.
     */
    private void registerLogo(Space space, ContentEntityObject description)
    {
        SpaceLogo logo = spaceManager.getLogoForSpace(space.getKey());
        if (logo == null || !logo.isCustomLogo())
        {
            return;
        }
        String fileName = logoFileName(logo.getDownloadPath());
        if (fileName == null)
        {
            return;
        }
        index.add(new AttachmentRef(fileName, null, null), description.getId(),
                space.getKey(),
                new RefSource(RefKind.SPACE_LOGO, description.getId(), space.getName(),
                        space.getKey(), "spacedescription"));
    }

    /** {@code /download/attachments/<id>/<name>?version=...} 에서 이름만 뗀다. */
    static String logoFileName(String downloadPath)
    {
        if (downloadPath == null)
        {
            return null;
        }
        String path = downloadPath;
        int query = path.indexOf('?');
        if (query >= 0)
        {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0)
        {
            path = path.substring(slash + 1);
        }
        if (path.isEmpty())
        {
            return null;
        }
        try
        {
            path = URLDecoder.decode(path, "UTF-8");
        }
        catch (UnsupportedEncodingException impossible)
        {
            return null;
        }
        catch (IllegalArgumentException malformed)
        {
            // 잘못된 % 이스케이프. 디코드 못 한 원본으로도 맞을 수 있으니 그대로 쓴다.
            path = path.substring(path.lastIndexOf('/') + 1);
        }
        return AttachmentRef.normalise(path);
    }

    private void walkPages(List<? extends AbstractPage> pages, String spaceKey,
                           boolean scanHistory)
    {
        for (AbstractPage page : pages)
        {
            if (watcher.cancelled())
            {
                return;
            }
            try
            {
                // 초안 페이지는 저장되지 않은 본문이고 남에게 보이지 않는다. 참조로 세면
                // 아무도 못 보는 글이 첨부를 [활성] 으로 만든다.
                if (!page.isCurrent())
                {
                    continue;
                }
                index.registerContent(spaceKey, page.getTitle(), page.getId());

                scanBody(ReferenceScanner.scanStorage(page.getBodyAsString()),
                        page.getId(), spaceKey,
                        new RefSource(RefKind.OWN_BODY, page.getId(), page.getTitle(),
                                spaceKey, page.getType()));

                for (Comment comment : page.getComments())
                {
                    // 댓글에 적힌 참조가 힌트 없이 가리키는 것은 댓글이 아니라 그 댓글이
                    // 달린 페이지의 첨부다 — 첨부는 페이지에 붙는다.
                    scanBody(ReferenceScanner.scanStorage(comment.getBodyAsString()),
                            page.getId(), spaceKey,
                            new RefSource(RefKind.COMMENT, comment.getId(), page.getTitle(),
                                    spaceKey, page.getType()));
                }

                if (scanHistory)
                {
                    walkHistory(page, spaceKey);
                }
            }
            catch (Throwable error)
            {
                // 페이지 하나가 나머지 전부를 잃게 하지 않는다.
                parseFailures++;
                log.warn("Attachment Janitor: could not read body of content {}",
                        page == null ? "?" : page.getIdAsString(), error);
            }
        }
    }

    private void walkHistory(AbstractPage page, String spaceKey)
    {
        ContentEntityObject version = page;
        for (int step = 0; step < MAX_HISTORY; step++)
        {
            if (watcher.cancelled())
            {
                return;
            }
            ContentEntityObject previous = pageManager.getPreviousVersion(version);
            if (previous == null)
            {
                return;
            }
            scanBody(ReferenceScanner.scanStorage(previous.getBodyAsString()),
                    page.getId(), spaceKey,
                    new RefSource(RefKind.HISTORY, page.getId(), page.getTitle(),
                            spaceKey, page.getType()));
            version = previous;
        }
    }

    private void scanBody(ReferenceScanner.Result result, long ownerContentId,
                          String ownerSpaceKey, RefSource source)
    {
        bodiesScanned++;
        watcher.scanned(bodiesScanned);
        if (!result.parsed)
        {
            parseFailures++;
            return;
        }
        for (AttachmentRef ref : result.refs)
        {
            index.add(ref, ownerContentId, ownerSpaceKey, source);
        }
    }
}
