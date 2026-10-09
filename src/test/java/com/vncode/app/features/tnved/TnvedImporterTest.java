package com.vncode.app.features.tnved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class TnvedImporterTest {
 @TempDir Path dir;
 String manifest="\"version\":\"test-fixture\",\"source_url\":\"https://www.consultant.ru/document/cons_doc_LAW_397176/\",\"valid_from\":\"2026-10-09\"";
 @Test void parsesJsonAndPreservesLeadingZeros() throws Exception {
  Path p=dir.resolve("fixture.json");Files.writeString(p,"{"+manifest+",\"nodes\":[{\"code\":\"01\",\"parent_code\":\"I\",\"node_type\":\"CHAPTER\",\"section_code\":\"I\",\"name_ru\":\"Fixture\",\"name_vi\":\"Thử nghiệm\",\"is_leaf\":false,\"is_active\":true,\"valid_from\":\"2026-10-09\"}]}");
  var result=TnvedImporter.read(p);assertEquals("01",result.nodes().getFirst().code());assertEquals("test-fixture",result.version().label());
 }
 @Test void malformedTypesAndDatesAreRejected() throws Exception {
  for(String json:new String[]{"[]","{"+manifest.replace("2026-10-09","2026-99-99")+",\"nodes\":[]}","{"+manifest+",\"nodes\":[{\"code\":1}]}"}) {
   Path p=dir.resolve("invalid.json");Files.writeString(p,json);assertThrows(IllegalArgumentException.class,()->TnvedImporter.read(p));
  }
 }
 @Test void parsesQuotedCsvAndRequiresManifest() throws Exception {
  Path p=dir.resolve("fixture.csv");Files.writeString(p,"code,parent_code,node_type,section_code,name_ru,name_vi,is_leaf,is_active,valid_from\r\n01,I,CHAPTER,I,\"Fixture, name\",\"Tên \"\"thử\"\"\",false,true,2026-10-09\r\n");
  assertThrows(java.io.IOException.class,()->TnvedImporter.read(p));Files.writeString(Path.of(p+".manifest.json"),"{"+manifest+"}");
  var n=TnvedImporter.read(p).nodes().getFirst();assertEquals("01",n.code());assertEquals("Fixture, name",n.nameRu());assertEquals("Tên \"thử\"",n.nameVi());
 }
 @Test void missingRequiredCsvHeadersCannotActivateAnEmptyReplacement() throws Exception {
  Path p=dir.resolve("bad-header.csv");Files.writeString(p,"garbage\n");Files.writeString(Path.of(p+".manifest.json"),"{"+manifest+"}");
  assertThrows(IllegalArgumentException.class,()->TnvedImporter.read(p));
 }
}
