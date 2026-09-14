package co.bskim.confluence.attachjanitor.model;

import co.bskim.confluence.attachjanitor.analyze.DuplicateFinder;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 번의 스캔이 만든 것 전부. 저장 직전의 모양이다.
 *
 * <p>합계를 여기서 미리 더해 두지 않는다 — 합계를 만드는 곳이 둘이 되면 화면의 총계와
 * 행의 합이 어긋나는 종류의 버그가 생긴다. 더하는 것은 저장하는 쪽 한 곳이다.
 */
public final class ScanOutcome
{
    public final List<AttachmentFacts> facts = new ArrayList<AttachmentFacts>();
    public DuplicateFinder.Result duplicates;

    /** 읽지 못한 첨부. 합계에 들어 있지 않다는 사실을 화면이 알린다. */
    public int skipped;
    public int bodiesScanned;
    /** 읽지 못한 본문. 0 이 아니면 라벨이 실제보다 [고아] 쪽으로 기운다. */
    public int parseFailures;
    public int refCount;
    /** 대상 페이지를 찾지 못한 참조. 이 값이 크면 [고아] 가 실제보다 많이 나온다. */
    public int unresolvedRefs;
    public boolean historyScanned;
}
