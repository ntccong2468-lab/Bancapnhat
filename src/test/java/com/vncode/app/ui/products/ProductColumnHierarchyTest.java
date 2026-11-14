package com.vncode.app.ui.products;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static com.vncode.app.ui.products.ProductReferenceLayoutTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native layout with synthetic data; no API, production startup or seller writes. */
class ProductColumnHierarchyTest {
    @TempDir Path profile;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}

    @Test void identifiersAndVariantFactsHaveTheirOwnWideColumnsAndRemainVisibleWhenNarrow()throws Exception {
        String previousData=System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        try(var connection=Database.getConnection();var statement=connection.createStatement()){
            statement.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Synthetic shop','fixture','WILDBERRIES')");
        }
        var product=new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,"901","991"),
                "Костюм спортивный","DEMO-SUIT-2XL","901","Костюмы","Fixture brand","Коричневый","2XL",
                List.of("00000000000017"),"00000000000017","","Хлопок 80%, полиэстер 20%","6112110000",DocumentState.VALID,"Мужской",true,"52","2XL");
        var reads=new ShopReadCoordinator(2,16);var pane=fx(()->new ProductsPane(query->new ProductPage(List.of(product),1,null),reads));
        var stage=fx(Stage::new);var language=I18nService.getInstance().getCurrentLanguage();var theme=ThemeService.getCurrentTheme();
        try {
            fx(()->{
                I18nService.getInstance().setLanguage(AppLanguage.VI);pane.applyTranslations();
                stage.setScene(new Scene(pane,1331,808));ThemeService.applyTheme(stage.getScene());stage.show();
                pane.setShop(new Shop(1,"Synthetic shop","fixture"));return null;
            });
            await(()->((ListView<?>)pane.lookup("#productRows")).getItems().size()==1&&!((Button)pane.lookup("#productSync")).isDisabled());
            for(String paint:List.of("light","dark"))for(int width:List.of(1331,760)){
                fx(()->{ThemeService.switchTheme(paint);double frame=stage.getWidth()-stage.getScene().getWidth();stage.setMinWidth(width+frame);stage.setWidth(width+frame);return null;});
                await(()->Math.abs(stage.getScene().getWidth()-width)<1);
                fx(()->{
                    pane.applyCss();pane.layout();Node row=pane.lookup(".product-row");assertNotNull(row);
                    Label name=label(row,product.title()),market=label(row,"Article WB: 901");
                    if(width>1000)assertTrue(market.localToScene(market.getLayoutBounds()).getMinX()>name.localToScene(name.getLayoutBounds()).getMaxX(),
                            "Marketplace identifiers must read in a separate column beside product identity");
                    var facts=List.of(name,market,label(row,"Article người bán: DEMO-SUIT-2XL"),
                            label(row,"Коричневый · 2XL"),label(row,"Chất liệu: "+product.material()),
                            label(row,"Giấy tờ hợp lệ"),label(row,"Giới tính: Мужской"),label(row,"TN VED: 6112110000"));
                    var bounds=row.localToScene(row.getLayoutBounds());
                    for(var fact:facts){
                        var actual=fact.localToScene(fact.getLayoutBounds());
                        assertTrue(fact.isVisible()&&fact.isManaged(),"Every product fact remains part of the row: "+fact.getText());
                        assertTrue(actual.getMinX()>=bounds.getMinX()-1&&actual.getMaxX()<=bounds.getMaxX()+1
                                &&actual.getMinY()>=bounds.getMinY()-1&&actual.getMaxY()<=bounds.getMaxY()+1,
                                paint+" "+width+" product fact escapes the row: "+fact.getText()+" "+actual+" / "+bounds);
                    }
                    var seller=label(row,"Article người bán: DEMO-SUIT-2XL");
                    assertNotNull(seller.getTooltip());assertTrue(seller.getTooltip().getText().contains("00000000000017"),"Barcode/verified GTIN information is retained");
                    var viewport=pane.lookup("#productRows").localToScene(pane.lookup("#productRows").getLayoutBounds());
                    assertTrue(viewport.getHeight()>400,"Product rows retain the largest part of this work area");
                    return null;
                });
            }
        } finally {
            fx(()->{pane.dispose();stage.close();I18nService.getInstance().setLanguage(language);ThemeService.setCurrentTheme(theme);return null;});
            com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            if(previousData==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousData);
        }
    }
    private static Label label(Node root,String text){return root.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
            .filter(label->text.equals(label.getText())).findFirst().orElseThrow(()->new AssertionError("Product fact not visible: "+text));}
}
