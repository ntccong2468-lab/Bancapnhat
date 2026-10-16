package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.KizImportReader;
import com.vncode.app.features.kiz.KizWarehouseRepository.*;
import com.vncode.app.shared.*;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.text.MessageFormat;
import java.util.Optional;

/** Shared shipment/warehouse review; only counts enter the UI, never paid codes. */
final class KizImportDialog {
    record Choice(boolean moveAvailable){}
    static Optional<Choice> confirm(Window owner,String title,KizImportReader.Preview preview,ImportReview review) {
        var dialog=new Dialog<Choice>();dialog.setTitle(tr("title"));dialog.setHeaderText(title);
        if(owner!=null)dialog.initOwner(owner);
        var counts=new Label(reviewText(review));counts.setWrapText(true);counts.setId("kiz-import-review");
        var formats=new Label(MessageFormat.format(tr("formats"),preview.marks().stream().filter(mark->!mark.fullDataMatrix()).count(),preview.marks().stream().filter(mark->mark.fullDataMatrix()).count()));formats.setWrapText(true);
        var move=new CheckBox(MessageFormat.format(tr("move"),review.otherCategory()));move.setId("kiz-import-move");move.setWrapText(true);
        move.setVisible(review.otherCategory()>0);move.setManaged(move.isVisible());
        var help=new Label(tr("help"));help.setWrapText(true);help.getStyleClass().add("text-muted");
        var content=new ScrollPane(new VBox(12,counts,formats,move,help));content.setFitToWidth(true);
        dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(590,480);
        var apply=new ButtonType(tr("apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,ButtonType.CANCEL);
        dialog.setResultConverter(button->button==apply?new Choice(move.isSelected()):null);
        DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        return dialog.showAndWait();
    }
    static String reviewText(ImportReview review) {
        return MessageFormat.format(tr("review"),review.fresh(),review.unassigned(),review.sameCategory(),review.otherCategory(),review.reserved(),review.consumed(),review.archived(),review.otherStock(),review.conflicts(),review.fileDuplicates());
    }
    static String resultText(Imported result) {
        var review=result.review();
        var lines=new java.util.ArrayList<String>();
        lines.add(MessageFormat.format(tr("result"),result.inserted(),result.classified(),result.moved()));
        String[] keys={"same","other","reserved","consumed","archived","foreign","conflicts","file_duplicates"};
        int[] counts={review.sameCategory(),review.otherCategory()-result.moved(),review.reserved(),review.consumed(),review.archived(),review.otherStock(),review.conflicts(),review.fileDuplicates()};
        for(int index=0;index<keys.length;index++)if(counts[index]>0)lines.add(MessageFormat.format(tr(keys[index]),counts[index]));
        return String.join("\n",lines);
    }
    private static String tr(String key){return I18nService.getInstance().tr("kiz.import."+key);}
}
