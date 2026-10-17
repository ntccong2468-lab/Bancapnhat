package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.features.tnved.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.scene.control.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import java.util.*;

/** One review for the batch, before GS1 allocation; advice never edits a marketplace by itself. */
final class RegistrationBatchPreflight {
    record Finding(String field,ProductKey key,String article,String before,String suggestion){}
    record Review(List<Finding> findings){Review{findings=List.copyOf(findings);}List<ProductKey> keys(String field){return findings.stream().filter(f->f.field().equals(field)).map(Finding::key).distinct().toList();}}
    static Review read(Shop owner,List<ProductKey> selected){
        ListingIdentity.require(owner);var findings=new ArrayList<Finding>();var repo=new ProductRepository();var advisor=new ProductTnvedAdvisor();var cards=new HashSet<String>();
        for(var key:selected){ListingIdentity.require(owner,key);if(!cards.add(key.cardId()))continue;
            var snapshot=repo.localSnapshot(key).orElse(null);if(snapshot==null||!ListingIdentity.fingerprint(owner).equals(snapshot.metadata().get("owner")))continue;
            if(snapshot.brand()==null||snapshot.brand().isBlank())findings.add(new Finding("brand",key,snapshot.vendorArticle(),"",""));
            for(String field:List.of("gender","material"))if(snapshot.attributes().stream().filter(a->a.complexId().isBlank()||a.complexId().equals("0")).filter(a->ProductFieldNames.matches(field,a.name())).flatMap(a->a.values().stream()).allMatch(String::isBlank))findings.add(new Finding(field,key,snapshot.vendorArticle(),"",""));
            var proposal=advisor.propose(snapshot);
            if(proposal.current().isBlank()||proposal.unique()&&!ProductFieldNames.tnvedCode(proposal.current()).equals(proposal.candidates().getFirst().code()))
                findings.add(new Finding("tnved",key,snapshot.vendorArticle(),proposal.current(),proposal.unique()?proposal.candidates().getFirst().code():""));
        }
        return new Review(findings);
    }
    /** Empty string continues unchanged, a field opens the shared batch editor, null cancels. */
    static String show(javafx.stage.Window window,Review review){
        if(review.findings().isEmpty())return "";
        var dialog=new Dialog<String>();if(window!=null)dialog.initOwner(window);dialog.setTitle(tr("znack.registration.batch_preflight"));dialog.setHeaderText(tr("znack.registration.batch_preflight_hint"));
        var table=new TableView<Finding>();table.getItems().setAll(review.findings());table.setPrefHeight(360);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,"Article",Finding::article);column(table,tr("znack.registration.batch_preflight"),f->tr("products.edit_"+f.field()));
        column(table,tr("edit.before"),Finding::before);column(table,tr("tnved.proposal"),Finding::suggestion);
        dialog.getDialogPane().setContent(table);dialog.getDialogPane().setPrefWidth(800);
        var fields=new LinkedHashMap<ButtonType,String>();for(String field:List.of("tnved","brand","material","gender"))if(!review.keys(field).isEmpty()){
            var edit=new ButtonType(tr("products.edit_"+field));fields.put(edit,field);dialog.getDialogPane().getButtonTypes().add(edit);
        }
        var proceed=new ButtonType(tr("znack.registration.keep_register"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(proceed,ButtonType.CANCEL);
        dialog.setResultConverter(button->button==proceed?"":fields.get(button));DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);return dialog.showAndWait().orElse(null);
    }
    private static void column(TableView<Finding> table,String label,java.util.function.Function<Finding,String> value){
        var column=new TableColumn<Finding,String>(label);column.setCellValueFactory(row->new ReadOnlyStringWrapper(value.apply(row.getValue())));column.setPrefWidth(180);RegistrationTextCells.wrap(column);table.getColumns().add(column);
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
