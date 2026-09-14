package co.bskim.confluence.attachjanitor.clean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "버전 몇 개를 남기고 어느 것을 지울지" 계산 — <b>Confluence 없이 도는 순수 함수다.</b>
 *
 * <p>일부러 떼어 놓았다. 실측 32번에서 확인한 것이 이렇다: Confluence 는
 * {@code removeAttachmentVersionFromServer} 에 <b>최신 버전을 넘겨도 막지 않는다.</b>
 * 지우면 첨부는 남지만 사람들이 내려받는 내용이 조용히 한 버전 뒤로 되돌아간다.
 * 경고도 없고 페이지도 안 깨진다 — 그래서 더 나쁘다.
 *
 * <p>즉 <b>이 산술의 버그가 곧 살아 있는 파일의 내용 변경</b>이다. 그래서 Confluence 를
 * 띄우지 않고 검사할 수 있는 자리에 두고 단위 시험으로 못 박는다.
 *
 * <p>규칙:
 * <ul>
 *   <li>버전 번호로 <b>직접 정렬</b>한다. {@code getPreviousVersions()} 의 목록 순서는
 *       문서화돼 있지 않고, 그 순서에 기대다 틀리면 위 사고가 난다.</li>
 *   <li><b>최신 버전 번호를 인자로 받아 직접 걸러낸다.</b> "호출자가 구버전만 넘긴다"는
 *       계약에 기대지 않는다 — 그 계약이 깨지는 날 파일 내용이 바뀐다.</li>
 *   <li>오래된 것부터 지운다. 도중에 죽어도 최근 이력이 남는다.</li>
 * </ul>
 */
public final class VersionPlan
{
    /** 남길 수 있는 최소 버전 수. 0 은 최신까지 지우라는 뜻이 되므로 허용하지 않는다. */
    public static final int MIN_KEEP = 1;

    private VersionPlan()
    {
    }

    /**
     * 지울 구버전 번호를 오래된 것부터 돌려준다.
     *
     * @param versions       후보 번호들. 최신 번호가 섞여 있어도 안전하다 — 걸러낸다.
     *                       순서는 상관없다, 여기서 정렬한다
     * @param currentVersion 최신 버전 번호. 이 값 <b>이상</b>은 절대 지우지 않는다
     * @param keep           살아남을 총 버전 수(최신 포함). {@link #MIN_KEEP} 미만이면
     *                       {@code MIN_KEEP} 로 올린다
     * @return 지울 번호, 오름차순(오래된 것부터). 지울 것이 없으면 빈 목록
     */
    public static List<Integer> toRemove(List<Integer> versions, int currentVersion, int keep)
    {
        List<Integer> sorted = new ArrayList<Integer>();
        if (versions != null)
        {
            for (Integer version : versions)
            {
                // 최신 이상은 여기서 떨어진다. 이 한 줄이 실측 32번의 사고를 막는다.
                if (version != null && version.intValue() < currentVersion)
                {
                    sorted.add(version);
                }
            }
        }
        Collections.sort(sorted);

        // keep 은 최신을 포함한 총 개수다. 최신은 후보에 없으므로 구버전 중 남길 수는
        // keep - 1 이다. keep = 1 이면 구버전을 전부 지운다.
        int keepOld = Math.max(MIN_KEEP, keep) - 1;
        int removeCount = sorted.size() - keepOld;
        if (removeCount <= 0)
        {
            return new ArrayList<Integer>();
        }
        return new ArrayList<Integer>(sorted.subList(0, removeCount));
    }
}
