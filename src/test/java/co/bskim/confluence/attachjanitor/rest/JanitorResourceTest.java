package co.bskim.confluence.attachjanitor.rest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JanitorResourceTest
{
    @Test
    public void quotesEveryCell()
    {
        assertEquals("\"TEST\"", JanitorResource.csvCell("TEST"));
    }

    @Test
    public void doublesEmbeddedQuotes()
    {
        assertEquals("\"say \"\"hi\"\"\"", JanitorResource.csvCell("say \"hi\""));
    }

    @Test
    public void nullBecomesAnEmptyCell()
    {
        assertEquals("\"\"", JanitorResource.csvCell(null));
    }

    @Test
    public void defusesSpreadsheetFormulas()
    {
        // A space named "=cmd|..." must not be evaluated when the CSV is opened in Excel.
        assertEquals("\"'=1+1\"", JanitorResource.csvCell("=1+1"));
        assertEquals("\"'@SUM(A1)\"", JanitorResource.csvCell("@SUM(A1)"));
        assertEquals("\"'-2\"", JanitorResource.csvCell("-2"));
    }

    @Test
    public void leavesOrdinaryNamesAlone()
    {
        assertEquals("\"Demonstration Space\"", JanitorResource.csvCell("Demonstration Space"));
    }
}
