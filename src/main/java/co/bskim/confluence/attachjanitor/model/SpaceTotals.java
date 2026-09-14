package co.bskim.confluence.attachjanitor.model;

/**
 * Running totals for one space while a scan is in flight, and the shape the ranking screen
 * reads afterwards. Mutable on purpose - the scan touches one of these per attachment.
 */
public class SpaceTotals
{
    /** Empty for the "no space" bucket (profile pictures). */
    public final String spaceKey;
    public String spaceName;

    public int fileCount;
    public long latestBytes;
    public long oldVersionBytes;
    public int oldVersionCount;
    public int orphanCount;
    public long orphanBytes;
    public int riskyCount;
    public int duplicateCount;

    public SpaceTotals(String spaceKey, String spaceName)
    {
        this.spaceKey = spaceKey == null ? "" : spaceKey;
        this.spaceName = spaceName == null ? "" : spaceName;
    }

    /** Everything this space's attachments occupy, current versions and superseded ones. */
    public long totalBytes()
    {
        return latestBytes + oldVersionBytes;
    }

    public boolean isNoSpace()
    {
        return spaceKey.isEmpty();
    }
}
