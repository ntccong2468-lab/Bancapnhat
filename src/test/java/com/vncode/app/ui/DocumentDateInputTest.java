package com.vncode.app.ui;

import com.vncode.app.shared.DocumentDateInput;
import javafx.application.Platform;
import javafx.scene.control.DatePicker;
import org.junit.jupiter.api.*;
import java.time.LocalDate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentDateInputTest {
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @Test void pastedEditorTextIsCommittedWithoutNeedingFocusLoss()throws Exception {
        var task=new FutureTask<Void>(()->{var picker=new DatePicker();DocumentDateInput.configure(picker);picker.getEditor().setText("14.10.2026");assertNull(picker.getValue());DocumentDateInput.commit(picker);assertEquals(LocalDate.of(2026,10,14),picker.getValue());picker.getEditor().setText("31.02.2026");assertThrows(IllegalArgumentException.class,()->DocumentDateInput.commit(picker));return null;});
        Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
    @Test void invalidCalendarDatesDoNotNormalizeSilently(){assertEquals(LocalDate.of(2028,2,29),DocumentDateInput.parse("29.02.2028"));assertThrows(RuntimeException.class,()->DocumentDateInput.parse("29.02.2026"));assertThrows(RuntimeException.class,()->DocumentDateInput.parse("31.04.2026"));}
}
