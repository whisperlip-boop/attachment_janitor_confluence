package co.bskim.confluence.attachjanitor.analyze;

import co.bskim.confluence.attachjanitor.model.AttachmentFacts;
import co.bskim.confluence.attachjanitor.model.Badge;
import co.bskim.confluence.attachjanitor.model.Label;
import co.bskim.confluence.attachjanitor.model.RefKind;
import co.bskim.confluence.attachjanitor.model.RefSource;
import co.bskim.confluence.attachjanitor.settings.Settings;

import java.util.Date;

/**
 * 모은 재료로 라벨과 배지를 정한다. 판정 규칙이 있는 유일한 자리다.
 *
 * <p>규칙을 여기 한 곳에 두는 이유는 설명서 때문이다. 화면·CSV·설명서가 같은 열거형과
 * 같은 판정을 보게 해야 <b>설명서와 실제 동작이 어긋나지</b> 않는다.
 */
public final class Judge
{
    /** Confluence 가 초안 컨테이너에 쓰는 상태값. */
    private static final String DRAFT = "draft";

    private Judge()
    {
    }

    /**
     * 라벨. 축은 하나 — <b>지우면 뭐가 깨지는가.</b>
     *
     * <p>우선순위는 [위험] > [활성] > [이력 참조] > [고아] 다. 현재 본문 참조가 하나라도
     * 자기 컨테이너 밖에 있으면 다른 무엇보다 [위험] 이다 — 자기 페이지에서도 쓰이고
     * 있다는 사실은 지웠을 때 남의 페이지가 깨지는 것을 막아 주지 않는다.
     */
    public static Label label(AttachmentFacts facts)
    {
        if (DRAFT.equalsIgnoreCase(facts.containerStatus))
        {
            // 초안 본문은 저장되지 않았고 남에게 보이지 않아 참조로 세지 않는다.
            // 그렇다고 [고아] 로 찍으면 "지워도 된다"는 뜻이 되어 거짓말이 된다.
            return Label.DRAFT;
        }

        boolean own = false;
        boolean elsewhere = false;
        boolean history = false;
        for (RefSource source : facts.refs)
        {
            if (source.kind == RefKind.HISTORY)
            {
                history = true;
            }
            else if (source.sameContainer)
            {
                own = true;
            }
            else
            {
                elsewhere = true;
            }
        }

        if (elsewhere)
        {
            return Label.RISKY;
        }
        if (own)
        {
            return Label.ACTIVE;
        }
        if (history)
        {
            return Label.HISTORY;
        }
        return Label.ORPHAN;
    }

    /**
     * 배지. 축은 <b>얼마나 큰가 / 얼마나 낭비인가</b> 이고 라벨과 직교한다.
     *
     * <p>[중복] 은 여기서 정하지 않는다. 해시를 다 뜬 뒤에야 알 수 있어서
     * {@link DuplicateFinder} 가 나중에 비트를 얹는다.
     */
    public static int badges(AttachmentFacts facts, Settings settings, Date now)
    {
        int bits = 0;
        if (facts.versionCount >= settings.oldVersionCount
                || facts.oldVersionBytes >= settings.oldVersionBytes)
        {
            bits |= Badge.OLD_VERSIONS.bit();
        }
        if (facts.latestBytes >= settings.largeBytes)
        {
            bits |= Badge.LARGE.bit();
        }
        if (facts.lastModified != null && settings.staleDays > 0)
        {
            long age = now.getTime() - facts.lastModified.getTime();
            if (age >= settings.staleDays * 86400000L)
            {
                bits |= Badge.STALE.bit();
            }
        }
        return bits;
    }
}
