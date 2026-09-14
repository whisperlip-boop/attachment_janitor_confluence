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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** 한 컨텐츠의 과거 버전을 이만큼까지만 거슬러 올라간다. */
    private static final int MAX_HISTORY = 200;

    private final PageManager pageManager;
    private final ReferenceIndex index;

    private int bodiesScanned;
    private int parseFailures;

    /**
     * @param pageManager 과거 버전 조회에도 이걸 쓴다. {@code PageManager} 가
     *                    {@code ContentEntityManager} 를 상속하므로 둘을 따로 주입하면
     *                    스프링이 같은 타입 빈이 둘이라며 앱을 기동시키지 않는다(실측 12번)
     */
    public BodyWalker(PageManager pageManager, ReferenceIndex index)
    {
        this.pageManager = pageManager;
        this.index = index;
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
        }

        walkPages(pageManager.getPages(space, true), spaceKey, scanHistory);
        walkPages(pageManager.getBlogPosts(space, true), spaceKey, scanHistory);
    }

    private void walkPages(List<? extends AbstractPage> pages, String spaceKey,
                           boolean scanHistory)
    {
        for (AbstractPage page : pages)
        {
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
