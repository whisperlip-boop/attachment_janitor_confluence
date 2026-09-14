package co.bskim.confluence.attachjanitor.web;

/**
 * 이 앱의 화면 목록. 상단 탭과 라우팅이 같은 목록을 본다.
 *
 * <p>탭을 손으로 적어 두면 화면을 추가할 때 한쪽만 고쳐서 링크가 없는 화면이 생긴다.
 */
public enum Screen
{
    SPACES("", "aj.nav.spaces"),
    DUPLICATES("/duplicates", "aj.nav.duplicates"),
    SETTINGS("/settings", "aj.nav.settings"),
    HELP("/help", "aj.nav.help"),
    /** 스페이스 상세. 탭에는 나오지 않고 랭킹 표에서 들어온다. */
    SPACE_DETAIL("/space", null);

    public static final String BASE_PATH = "/plugins/servlet/attachment-janitor";

    private final String suffix;
    private final String labelKey;

    Screen(String suffix, String labelKey)
    {
        this.suffix = suffix;
        this.labelKey = labelKey;
    }

    public String suffix()
    {
        return suffix;
    }

    public String labelKey()
    {
        return labelKey;
    }

    /** 탭에 보이는 화면인가. */
    public boolean isTab()
    {
        return labelKey != null;
    }

    public String url(String base, LocaleText text)
    {
        return base + BASE_PATH + suffix + text.queryParam(true);
    }

    public static Screen fromPath(String pathInfo)
    {
        String path = pathInfo == null ? "" : pathInfo;
        if (path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }
        for (Screen screen : values())
        {
            if (screen.suffix.equals(path))
            {
                return screen;
            }
        }
        return SPACES;
    }
}
