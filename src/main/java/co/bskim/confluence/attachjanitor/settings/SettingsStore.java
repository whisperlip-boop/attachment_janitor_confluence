package co.bskim.confluence.attachjanitor.settings;

import com.atlassian.bandana.BandanaManager;
import com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * 설정 저장. 전역 설정 한 벌뿐이라 AO 테이블을 따로 만들지 않고 Bandana 를 쓴다
 * (기획서 7장).
 *
 * <p>값을 객체 하나로 직렬화하지 않고 <b>키마다 문자열</b>로 넣는다. Bandana 는
 * XStream 으로 직렬화하는데, 그러면 나중에 필드를 추가·삭제할 때 예전에 저장된 객체를
 * 못 읽거나 조용히 기본값으로 돌아간다. 문자열이면 모르는 키는 무시되고 없는 키는
 * 기본값이 된다.
 */
@Named
public class SettingsStore
{
    private static final ConfluenceBandanaContext CONTEXT = ConfluenceBandanaContext.GLOBAL_CONTEXT;
    private static final String PREFIX = "co.bskim.attachjanitor.";

    private final BandanaManager bandana;

    @Inject
    public SettingsStore(@ComponentImport BandanaManager bandana)
    {
        this.bandana = bandana;
    }

    public Settings load()
    {
        Settings defaults = Settings.defaults();
        return new Settings(
                (int) number("oldVersionCount", defaults.oldVersionCount),
                number("oldVersionBytes", defaults.oldVersionBytes),
                number("largeBytes", defaults.largeBytes),
                (int) number("staleDays", defaults.staleDays),
                flag("scanHistory", defaults.scanHistory),
                Settings.DuplicateMode.FULL.name().equals(
                        string("duplicateMode", defaults.duplicateMode.name()))
                        ? Settings.DuplicateMode.FULL : Settings.DuplicateMode.QUICK,
                number("duplicateByteBudget", defaults.duplicateByteBudget),
                (int) number("keepRuns", defaults.keepRuns));
    }

    public void save(Settings settings)
    {
        put("oldVersionCount", String.valueOf(clampInt(settings.oldVersionCount, 2, 1000)));
        put("oldVersionBytes", String.valueOf(clampLong(settings.oldVersionBytes, 0, 1L << 42)));
        put("largeBytes", String.valueOf(clampLong(settings.largeBytes, 0, 1L << 42)));
        put("staleDays", String.valueOf(clampInt(settings.staleDays, 1, 36500)));
        put("scanHistory", String.valueOf(settings.scanHistory));
        put("duplicateMode", settings.duplicateMode.name());
        put("duplicateByteBudget",
                String.valueOf(clampLong(settings.duplicateByteBudget, 0, 1L << 45)));
        // 세대를 0으로 두면 방금 저장한 결과까지 지워진다. 1 이 하한이다.
        put("keepRuns", String.valueOf(clampInt(settings.keepRuns, 1, 50)));
    }

    private void put(String key, String value)
    {
        bandana.setValue(CONTEXT, PREFIX + key, value);
    }

    private String string(String key, String fallback)
    {
        Object value = bandana.getValue(CONTEXT, PREFIX + key);
        return value == null ? fallback : String.valueOf(value);
    }

    private long number(String key, long fallback)
    {
        try
        {
            return Long.parseLong(string(key, String.valueOf(fallback)).trim());
        }
        catch (NumberFormatException error)
        {
            // 손으로 고쳐 깨진 값이 스캔을 막지 않게 한다.
            return fallback;
        }
    }

    private boolean flag(String key, boolean fallback)
    {
        return Boolean.parseBoolean(string(key, String.valueOf(fallback)));
    }

    static int clampInt(int value, int min, int max)
    {
        return value < min ? min : (value > max ? max : value);
    }

    static long clampLong(long value, long min, long max)
    {
        return value < min ? min : (value > max ? max : value);
    }
}
