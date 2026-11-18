package com.vncode.app.ui.controls;

import javafx.application.Platform;
import javafx.scene.control.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CategoryFilterMenuLimitTest {
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @Test void reachingTheLimitDisablesOnlyUnselectedOptionsAndUncheckingRestoresThem()throws Exception{
        fx(()->{
            var button=new MenuButton();var changes=new AtomicInteger();var menu=new CategoryFilterMenu(button,changes::incrementAndGet,2);menu.setTexts("Categories","Uncategorized","Clear");menu.rebuild(List.of("A","B","C"));
            check(button,"A").fire();check(button,"B").fire();assertEquals(Set.of("A","B"),menu.selectedCategories());assertEquals(2,changes.get());
            assertTrue(check(button,"C").isDisabled());assertFalse(check(button,"A").isDisabled());assertFalse(check(button,"B").isDisabled());
            check(button,"C").setSelected(true);assertFalse(check(button,"C").isSelected());assertEquals(Set.of("A","B"),menu.selectedCategories());assertEquals(2,changes.get(),"A rejected programmatic selection must not refresh the product query");
            check(button,"A").fire();assertFalse(check(button,"C").isDisabled());check(button,"C").fire();assertEquals(Set.of("B","C"),menu.selectedCategories());
            assertEquals("Categories (2)",button.getText());button.getItems().getFirst().fire();assertTrue(menu.selectedCategories().isEmpty());assertFalse(check(button,"A").isDisabled());assertEquals("Categories",button.getText());return null;
        });
    }
    @Test void theDefaultConstructorKeepsUnlimitedSelectionAndHistoricalNormalization()throws Exception{
        fx(()->{
            var button=new MenuButton();var menu=new CategoryFilterMenu(button,()->{});menu.setTexts("Categories","Uncategorized","Clear");menu.rebuild(List.of(" A ","a","B","C",""));
            assertEquals(6,button.getItems().size(),"Historical case-insensitive normalization and one blank option stay unchanged");
            for(var item:button.getItems())if(item instanceof CustomMenuItem custom&&custom.getContent() instanceof CheckBox check)check.fire();
            assertEquals(Set.of("A","B","C",""),menu.selectedCategories());assertTrue(menu.matches(" A "));assertTrue(menu.matches(null));return null;
        });
    }
    @Test void aHundredSelectionsIncludingAnUncategorizedValueRemainWithinTheProductQueryLimit()throws Exception{
        fx(()->{
            var button=new MenuButton();var menu=new CategoryFilterMenu(button,()->{},100);menu.setTexts("Categories","Uncategorized","Clear");var values=new ArrayList<String>();values.add("");for(int index=0;index<101;index++)values.add("Category "+index);menu.rebuildExact(values);
            check(button,"Uncategorized").fire();for(int index=0;index<99;index++)check(button,"Category "+index).fire();
            assertEquals(100,menu.selectedCategories().size());assertTrue(menu.selectedCategories().contains(""));assertTrue(check(button,"Category 100").isDisabled());assertFalse(check(button,"Uncategorized").isDisabled());
            check(button,"Category 0").fire();assertFalse(check(button,"Category 100").isDisabled());check(button,"Category 100").fire();assertEquals(100,menu.selectedCategories().size());return null;
        });
    }
    @Test void exactModeRetainsQueryValuesAndLanguageChangesWithoutCollapsingCaseOrWhitespace()throws Exception{
        fx(()->{
            var button=new MenuButton();var changes=new AtomicInteger();var menu=new CategoryFilterMenu(button,changes::incrementAndGet,100);menu.setTexts("Categories","Uncategorized","Clear");menu.rebuildExact(List.of("Suits","SUITS"," Suits ","Suits"));
            assertEquals(5,button.getItems().size());check(button," Suits ").fire();assertEquals(Set.of(" Suits "),menu.selectedCategories());assertTrue(menu.matches(" Suits "));assertFalse(menu.matches("Suits"));
            check(button,"SUITS").fire();menu.setTexts("Danh mục","Chưa phân loại","Xóa");assertEquals(Set.of(" Suits ","SUITS"),menu.selectedCategories());assertEquals(2,changes.get());assertEquals("Danh mục (2)",button.getText());return null;
        });
    }
    @Test void invalidLimitsAreRejectedBeforeCreatingAnUnusableMenu()throws Exception{fx(()->{assertThrows(IllegalArgumentException.class,()->new CategoryFilterMenu(new MenuButton(),()->{},0));return null;});}
    private static CheckBox check(MenuButton button,String name){return button.getItems().stream().filter(CustomMenuItem.class::isInstance).map(CustomMenuItem.class::cast).map(CustomMenuItem::getContent).filter(CheckBox.class::isInstance).map(CheckBox.class::cast).filter(check->check.getText().equals(name)).findFirst().orElseThrow();}
    private static <T>T fx(Callable<T> work)throws Exception{var future=new FutureTask<>(work);Platform.runLater(future);try{return future.get(15,TimeUnit.SECONDS);}catch(ExecutionException failed){if(failed.getCause() instanceof Error cause)throw cause;if(failed.getCause() instanceof Exception cause)throw cause;throw failed;}}
}
