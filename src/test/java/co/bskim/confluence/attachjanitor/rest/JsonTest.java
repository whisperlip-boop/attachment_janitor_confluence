package co.bskim.confluence.attachjanitor.rest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The escaping is the only reason this class exists instead of a library, so it is the thing
 * that gets tested.
 */
public class JsonTest
{
    @Test
    public void writesFlatObject()
    {
        assertEquals("{\"a\":1,\"b\":\"x\",\"c\":true}",
                Json.object().put("a", 1).put("b", "x").put("c", true).end());
    }

    @Test
    public void escapesQuotesAndBackslashes()
    {
        assertEquals("{\"k\":\"he said \\\"hi\\\" c:\\\\tmp\"}",
                Json.object().put("k", "he said \"hi\" c:\\tmp").end());
    }

    @Test
    public void escapesControlCharacters()
    {
        assertEquals("{\"k\":\"a\\nb\\u0000c\"}",
                Json.object().put("k", "a\nb\u0000c").end());
    }

    @Test
    public void escapesLineSeparatorsThatBreakScriptEvaluation()
    {
        assertEquals("{\"k\":\"a\\u2028b\\u2029c\"}",
                Json.object().put("k", "a\u2028b\u2029c").end());
    }

    @Test
    public void keepsNonAsciiAsIs()
    {
        // The response is UTF-8; escaping Korean would only make it bigger and harder to read.
        assertEquals("{\"k\":\"한글\"}", Json.object().put("k", "한글").end());
    }

    @Test
    public void writesNullAsNull()
    {
        assertEquals("{\"k\":null}", Json.object().put("k", (String) null).end());
    }

    @Test
    public void arrayOfRenderedObjects()
    {
        assertEquals("[{\"a\":1},{\"a\":2}]",
                Json.array().add(Json.object().put("a", 1).end())
                        .add(Json.object().put("a", 2).end()).end());
    }
}
