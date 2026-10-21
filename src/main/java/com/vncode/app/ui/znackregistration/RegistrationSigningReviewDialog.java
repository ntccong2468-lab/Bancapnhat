package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.RegistrationSigningReview;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.DialogLayout;
import com.vncode.app.shared.I18nService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** Reviewing is read-only. The caller submits this same immutable document batch only on confirmation. */
public final class RegistrationSigningReviewDialog {
    private RegistrationSigningReviewDialog(){}
    public static boolean review(Window owner,RegistrationSigningReview review){
        if(review==null||review.cards().isEmpty())return false;
        var dialog=new Dialog<Boolean>();if(owner!=null)dialog.initOwner(owner);
        dialog.setTitle(tr("znack.registration.sign_approved"));dialog.setHeaderText(tr("znack.registration.signing_review"));
        var scope=review.scope();var signer=new Label(tr("znack.registration.signing_review_scope")+"\n"+review.owner()+"\n"+review.signer()+"\nINN: "+scope.inn()+"\n"+scope.environment()+"\n"+scope.fingerprint());signer.setId("registrationSigningReviewSigner");signer.setWrapText(true);
        var table=new TableView<RegistrationSigningReview.Card>();table.setId("registrationSigningReviewCards");table.getItems().setAll(review.cards());table.setPrefHeight(170);
        column(table,tr("znack.registration.review_product_id"),RegistrationSigningReview.Card::productId);column(table,tr("znack.registration.review_variant_id"),RegistrationSigningReview.Card::variantId);column(table,tr("znack.registration.review_revision"),RegistrationSigningReview.Card::revision);column(table,"GTIN",RegistrationSigningReview.Card::gtin);column(table,tr("znack.registration.review_catalog_id"),card->String.valueOf(card.goodId()));column(table,tr("znack.registration.catalog_observation"),RegistrationSigningReview.Card::catalogState);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        var hash=new Label();hash.setId("registrationSigningReviewHash");hash.setWrapText(true);var xml=new TextArea();xml.setId("registrationSigningReviewXml");xml.setEditable(false);xml.setWrapText(false);xml.setPrefRowCount(12);
        table.getSelectionModel().selectedItemProperty().addListener((observable,before,card)->{hash.setText(card==null?"":"SHA-256: "+card.document().sha256());xml.setText(card==null?"":new String(card.document().xml(),StandardCharsets.UTF_8));});table.getSelectionModel().selectFirst();
        var confirm=new ButtonType(tr("znack.registration.sign_approved"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(confirm,ButtonType.CANCEL);dialog.getDialogPane().setContent(new VBox(10,signer,table,hash,xml));dialog.getDialogPane().setPrefWidth(900);dialog.getDialogPane().setId("registrationSigningReviewDialog");
        dialog.setResultConverter(button->button==confirm);DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);return dialog.showAndWait().orElse(false);
    }
    private static void column(TableView<RegistrationSigningReview.Card> table,String label,Function<RegistrationSigningReview.Card,String> value){var column=new TableColumn<RegistrationSigningReview.Card,String>(label);column.setCellValueFactory(cell->new ReadOnlyStringWrapper(value.apply(cell.getValue())));table.getColumns().add(column);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
