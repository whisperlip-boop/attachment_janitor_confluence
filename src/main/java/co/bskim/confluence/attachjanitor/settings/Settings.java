package co.bskim.confluence.attachjanitor.settings;

import co.bskim.confluence.attachjanitor.model.Badge;
import co.bskim.confluence.attachjanitor.web.LocaleText;

/**
 * 관리자가 바꿀 수 있는 값 전부. 불변 객체다 — 스캔 중에 값이 바뀌면 같은 스캔 안에서
 * 앞쪽 첨부와 뒤쪽 첨부가 다른 기준으로 판정된다.
 */
public final class Settings
{
    /** 해시를 어떻게 뜰지. 기본은 약식이다 — 정확 해시는 첨부를 전부 읽는다. */
    public enum DuplicateMode
    {
        /** 파일 앞뒤 블록 + 크기. 빠르지만 "중복 후보"라고만 말할 수 있다. */
        QUICK,
        /** 전체 SHA-1. 느리지만 같다고 단정할 수 있다. */
        FULL
    }

    public static final long MB = 1024L * 1024L;

    public final int oldVersionCount;
    public final long oldVersionBytes;
    public final long largeBytes;
    public final int staleDays;
    public final boolean scanHistory;
    public final DuplicateMode duplicateMode;
    /** 한 번의 스캔에서 해시를 위해 읽을 수 있는 총 바이트 상한. 0이면 무제한. */
    public final long duplicateByteBudget;
    public final int keepRuns;
    /**
     * 정리 기록을 며칠 보관하나.
     *
     * <p>스캔 세대({@link #keepRuns})와 별개다. 스캔은 다시 돌리면 되지만 정리 기록은
     * <b>되돌릴 수 없는 삭제의 유일한 흔적</b>이라 같은 기준으로 지울 수 없다. 기본이
     * 1년이고 하한이 30일인 것은 며칠짜리 창이 감사 기록이 아니라 최근 활동 목록이기
     * 때문이다.
     */
    public final int keepActionDays;

    public Settings(int oldVersionCount, long oldVersionBytes, long largeBytes, int staleDays,
                    boolean scanHistory, DuplicateMode duplicateMode, long duplicateByteBudget,
                    int keepRuns, int keepActionDays)
    {
        this.oldVersionCount = oldVersionCount;
        this.oldVersionBytes = oldVersionBytes;
        this.largeBytes = largeBytes;
        this.staleDays = staleDays;
        this.scanHistory = scanHistory;
        this.duplicateMode = duplicateMode;
        this.duplicateByteBudget = duplicateByteBudget;
        this.keepRuns = keepRuns;
        this.keepActionDays = keepActionDays;
    }

    /**
     * 기획서 3.2 의 값이 그대로 기본값이다.
     * 과거 버전 본문 검사는 기본 꺼짐 — 켜면 스캔 시간이 크게 늘어난다(기획서 4.4).
     */
    public static Settings defaults()
    {
        return new Settings(5, 10 * MB, 20 * MB, 3 * 365, false,
                DuplicateMode.QUICK, 2048 * MB, 5, 365);
    }

    /** 설명서가 임계값을 글로 박지 않고 지금 값을 그대로 적을 수 있게 한다. */
    public String describeThreshold(Badge badge, LocaleText text)
    {
        switch (badge)
        {
            case OLD_VERSIONS:
                return text.text("aj.help.threshold.oldversions",
                        oldVersionCount, megabytes(oldVersionBytes));
            case LARGE:
                return text.text("aj.help.threshold.large", megabytes(largeBytes));
            case STALE:
                return text.text("aj.help.threshold.stale", staleDays);
            case DUPLICATE:
                return text.text(duplicateMode == DuplicateMode.FULL
                        ? "aj.help.threshold.duplicate.full"
                        : "aj.help.threshold.duplicate.quick");
            default:
                return "";
        }
    }

    public static long megabytes(long bytes)
    {
        return bytes / MB;
    }
}
