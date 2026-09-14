package co.bskim.confluence.attachjanitor.rest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 첨부 id 목록 읽기. <b>지우는 요청의 입력</b>이라서 "일부만 알아듣고 진행"이 없어야 한다.
 */
public class JsonReaderTest
{
    @Test
    public void readsAPlainNumberArray()
    {
        assertEquals("[1, 22, 333]",
                JsonReader.numbers("{\"ids\":[1, 22,333],\"keep\":3}", "ids", 100).toString());
    }

    @Test
    public void emptyOrMissingGivesAnEmptyList()
    {
        assertEquals("[]", JsonReader.numbers("{\"ids\":[]}", "ids", 100).toString());
        assertEquals("[]", JsonReader.numbers("{\"keep\":3}", "ids", 100).toString());
        assertEquals("[]", JsonReader.numbers(null, "ids", 100).toString());
    }

    @Test
    public void anythingThatIsNotANumberRejectsTheWholeList()
    {
        // 절반만 알아듣고 지우기 시작하는 것이 아무것도 안 하는 것보다 나쁘다.
        assertEquals("[]", JsonReader.numbers("{\"ids\":[1,\"2\",3]}", "ids", 100).toString());
        assertEquals("[]", JsonReader.numbers("{\"ids\":[1,-2]}", "ids", 100).toString());
        assertEquals("[]", JsonReader.numbers("{\"ids\":[1,2.5]}", "ids", 100).toString());
        assertEquals("[]", JsonReader.numbers("{\"ids\":[1,null]}", "ids", 100).toString());
    }

    @Test
    public void tooManyIdsRejectsTheWholeList()
    {
        StringBuilder json = new StringBuilder("{\"ids\":[");
        for (int index = 0; index < 12; index++)
        {
            json.append(index == 0 ? "" : ",").append(index + 1);
        }
        json.append("]}");
        assertEquals("[]", JsonReader.numbers(json.toString(), "ids", 10).toString());
        assertEquals(12, JsonReader.numbers(json.toString(), "ids", 100).size());
    }

    @Test
    public void theOtherReadersStillWork()
    {
        assertEquals(3L, JsonReader.number("{\"keep\":3}", "keep", 1));
        assertEquals(1L, JsonReader.number("{\"other\":3}", "keep", 1));
        assertEquals(true, JsonReader.flag("{\"dry\":true}", "dry", false));
    }
}
