package com.vncode.app.ui.znackregistration;

import javafx.application.Platform;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationV3PaneTest {
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException existing){}}
    <T> T fx(Supplier<T> work)throws Exception {var task=new FutureTask<T>(work::get);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    @Test void sevenVietnameseStepsAndWritesRequireASelectedJob()throws Exception {
        fx(()->{
            var pane=new RegistrationV3Pane(null);
            assertEquals("Chưa chọn cửa hàng",((Label)pane.lookup("#znackV3Account")).getText());
            var tabs=(TabPane)pane.lookup("#znackV3Steps");assertNotNull(tabs);assertEquals(7,tabs.getTabs().size());
            assertEquals("1. Sản phẩm",tabs.getTabs().getFirst().getText());assertEquals("7. Hoàn thành",tabs.getTabs().getLast().getText());
            assertTrue(((Button)pane.lookup("#znackV3Submit")).isDisabled());
            assertTrue(((Button)pane.lookup("#znackV3Sign")).isDisabled());pane.close();return null;
        });
    }
    @Test void conditionalAttributeEditorShowsChildrenAndPreservesEnteredValues()throws Exception {
        fx(()->{
            var child=new com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute(2,"Giấy tờ","text",false,false,false,false,java.util.List.of());
            var parent=new com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute(1,"Loại","text",false,false,false,false,java.util.List.of(),java.util.List.of(),java.util.Map.of("YES",java.util.List.of(child)));
            var editor=new RegistrationAttributeEditor();editor.setSchema(java.util.List.of(parent),java.util.Map.of(),java.util.Map.of());
            var root=(TextField)editor.lookup("#znackV3Attribute-1");root.setText("YES");
            var field=(TextField)editor.lookup("#znackV3Attribute-2");assertNotNull(field);field.setText("DOC-123");
            root.setText("NO");assertFalse(editor.values().containsKey(2L));root.setText("YES");assertEquals("DOC-123",editor.values().get(2L));return null;
        });
    }
    @Test void productSearchFindsSourceArticleAndGtinWithoutChangingTheJob(){
        var owner=new com.vncode.app.integration.znack.registration.v3.RegistrationJob.Owner(1,"owner","production","scope");
        var product=new com.vncode.app.integration.znack.registration.v3.RegistrationJob.Product(10,20,1,"ARTICLE-42","Áo đỏ","Brand","Đỏ","M","46",java.util.List.of(),"",java.util.List.of());
        var job=new com.vncode.app.integration.znack.registration.v3.RegistrationJob("job",owner,product,com.vncode.app.integration.znack.registration.v3.RegistrationState.PRODUCT_IMPORTED,0,com.vncode.app.integration.znack.registration.v3.RegistrationJob.Data.empty().withGtin("04601234567893"),"2026-10-21T00:00:00Z");
        assertTrue(RegistrationV3Pane.matches(job,"article-42"));assertTrue(RegistrationV3Pane.matches(job,"áo"));assertTrue(RegistrationV3Pane.matches(job,"567893"));assertFalse(RegistrationV3Pane.matches(job,"không tồn tại"));assertEquals(0,job.revision());
    }
    @Test void closingPaneInvalidatesOutstandingCallbacks()throws Exception {
        fx(()->{var pane=new RegistrationV3Pane(null);long epoch=pane.epoch();assertTrue(pane.accepts(epoch));pane.close();assertFalse(pane.accepts(epoch));return null;});
    }
}
