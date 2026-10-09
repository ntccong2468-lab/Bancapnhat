package com.vncode.app.features.tnved;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.features.tnved.TnvedModels.*;

class TnvedCatalogTest {
    @TempDir Path dir;
    final LocalDate date = LocalDate.parse("2026-10-09");
    Node node(String code,String parent,boolean leaf,LocalDate end) {
        return new Node(code,parent,leaf?"LEAF":"CHAPTER","I","Орёл тест", "Sản phẩm thử nghiệm", "", "", leaf,true,date,end,
                "https://www.consultant.ru/document/cons_doc_LAW_397176/");
    }
    TnvedCatalog catalog() throws Exception {var c=new TnvedCatalog(dir.resolve("tnved.sqlite"));c.initialize();return c;}
    Version version(String name) {return new Version(name,"https://www.consultant.ru/document/cons_doc_LAW_397176/",date);}
    @Test void seedsOnly21VerifiedStructuralSections() throws Exception {
        var c=catalog();assertEquals(21,c.children(null).size());assertEquals("I",c.children(null).getFirst().code());
        assertTrue(c.children("I").isEmpty());assertTrue(c.search("77",0,100,date).isEmpty());
        assertFalse(c.isAssignable("I",date));
    }
    @Test void preservesZeroPrefixesAndSearchesVietnameseAndRussian() throws Exception {
        var c=catalog();c.importVersion(version("test-fixture-v1"),List.of(node("01","I",false,null),node("0101210000","01",true,null)),true);
        assertEquals("0101210000",c.children("01").getFirst().code());
        assertEquals(2,c.search("san pham thu nghiem",0,100,date).size());
        assertEquals(2,c.search("ОРЕЛ",0,100,date).size());assertTrue(c.isAssignable("0101210000",date));
    }
    @Test void invalidImportsDoNotReplaceTheCurrentVersion() throws Exception {
        var c=catalog();c.importVersion(version("good-fixture"),List.of(node("01","I",false,null)),true);
        for(var bad:List.of(List.of(node("77","XV",false,null)),
                List.of(node("01","missing",false,null)),List.of(node("01","I",false,null),node("01","I",false,null)),
                List.of(node("01","02",false,null),node("02","01",false,null)))) {
            assertThrows(IllegalArgumentException.class,()->c.importVersion(version("bad-fixture"),bad,true));
            assertEquals("good-fixture",c.activeVersion());assertEquals(1,c.children("I").size());
        }
        assertThrows(IllegalArgumentException.class,()->c.importVersion(version("unconfirmed"),List.of(),false));
    }
    @Test void expiredLeavesAndUnknownCodesCannotBeAssigned() throws Exception {
        var c=catalog();c.importVersion(version("expired-fixture"),List.of(node("01","I",false,null),
                new Node("0101210000","01","LEAF","I","Fixture","Fixture","","",true,true,date.minusDays(2),date.minusDays(1),version("x").sourceUrl())),true);
        assertFalse(c.isAssignable("0101210000",date));assertFalse(c.isAssignable("9999999999",date));
        assertFalse(c.isAssignable("01",date));
    }
    @Test void snapshotContainsCurrentCatalogAndFutureVersionIsNotAssignable() throws Exception {
        var c=catalog();c.importVersion(new Version("future-fixture",version("x").sourceUrl(),date.plusDays(2)),
                List.of(node("01","I",false,null),node("0101210000","01",true,null)),true);
        assertFalse(c.isAssignable("0101210000",date));
        Path backup=c.snapshot();assertTrue(java.nio.file.Files.size(backup)>0);
        var restored=new TnvedCatalog(backup);assertEquals("future-fixture",restored.activeVersion());
    }
}
