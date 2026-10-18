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
    /** Choosing a file already authorizes a local import; this dialog only reports its result. */
    static void report(Window owner,String title,KizImportReader.Preview preview,Imported result){
        var dialog=new Dialog<Void>();dialog.setTitle(tr("title"));dialog.setHeaderText(title);if(owner!=null)dialog.initOwner(owner);
        var summary=new Label(fileText(preview));summary.setId("kiz-import-file-report");summary.setWrapText(true);
        var details=new Label(preview.issues().stream().map(i->i.row()+": "+com.vncode.app.features.kiz.KizErrorMessages.message(i.code())).collect(java.util.stream.Collectors.joining("\n")));details.setId("kiz-import-damaged");details.setWrapText(true);
        var imported=new Label(result==null?"":resultText(result));imported.setWrapText(true);
        var content=new ScrollPane(new VBox(12,summary,imported,details));content.setFitToWidth(true);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(590,480);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setId("kiz-import-report-close");
        DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);dialog.showAndWait();
    }
    static Optional<Choice> confirm(Window owner,String title,KizImportReader.Preview preview,ImportReview review) {
        if(review==null)review=new ImportReview(0,0,0,0,0,0,0,0,0,preview.duplicates());
        var dialog=new Dialog<Choice>();dialog.setTitle(tr("title"));dialog.setHeaderText(title);
        if(owner!=null)dialog.initOwner(owner);
        var counts=new Label(reviewText(review));counts.setWrapText(true);counts.setId("kiz-import-review");
        boolean legacyReview=review.unassigned()+review.sameCategory()+review.otherCategory()+review.reserved()+review.consumed()+review.archived()+review.otherStock()+review.conflicts()+review.fileDuplicates()>0;
        counts.setVisible(legacyReview);counts.setManaged(legacyReview);
        var formats=new Label(MessageFormat.format(tr("formats"),preview.marks().stream().filter(mark->!mark.fullDataMatrix()).count(),preview.marks().stream().filter(mark->mark.fullDataMatrix()).count()));formats.setWrapText(true);
        var move=new CheckBox(MessageFormat.format(tr("move"),review.otherCategory()));move.setId("kiz-import-move");move.setWrapText(true);
        move.setVisible(review.otherCategory()>0);move.setManaged(move.isVisible());
        var help=new Label(tr("help"));help.setWrapText(true);help.getStyleClass().add("text-muted");
        var file=new Label(fileText(preview));file.setWrapText(true);file.setId("kiz-import-file-report");
        var issues=new Label(preview.issues().stream().map(i->i.row()+": "+com.vncode.app.features.kiz.KizErrorMessages.message(i.code())).collect(java.util.stream.Collectors.joining("\n")));issues.setWrapText(true);issues.setId("kiz-import-damaged");
        var content=new ScrollPane(new VBox(12,file,counts,formats,issues,move,help));content.setFitToWidth(true);
        dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(590,480);
        var apply=new ButtonType(tr("apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(apply).setDisable(!preview.valid());
        dialog.setResultConverter(button->button==apply?new Choice(move.isSelected()):null);
        DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        return dialog.showAndWait();
    }
    static String fileText(KizImportReader.Preview preview){
        return MessageFormat.format(tr("file_report"),preview.total(),preview.marks().size(),preview.damaged(),preview.duplicates());
    }
    static String resultText(Imported result,KizImportReader.Preview preview){return fileText(preview)+"\n"+resultText(result);}
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
