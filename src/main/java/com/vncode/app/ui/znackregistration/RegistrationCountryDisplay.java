package com.vncode.app.ui.znackregistration;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.util.StringConverter;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Country names are presentation only; Catalog values remain the validated ISO codes. */
public final class RegistrationCountryDisplay {
    private static final long COUNTRY=2630L;
    private static final Set<String> ISO=Set.of(Locale.getISOCountries());
    private static final Locale RUSSIAN=Locale.forLanguageTag("ru");
    private RegistrationCountryDisplay(){}

    public static String label(long attribute,String value){
        String text=Objects.requireNonNullElse(value,"");
        if(attribute!=COUNTRY)return text;
        String code=text.strip().toUpperCase(Locale.ROOT);
        if(!ISO.contains(code))return text;
        return code.equals("RU")?"Россия":Locale.of("",code).getDisplayCountry(RUSSIAN);
    }
    static String value(long attribute,String displayed,Collection<String> allowed){
        if(attribute!=COUNTRY||displayed==null)return displayed;
        if(allowed.contains(displayed))return displayed;
        var matches=allowed.stream().filter(code->label(attribute,code).equalsIgnoreCase(displayed.strip())||code.equalsIgnoreCase(displayed.strip())).toList();
        return matches.size()==1?matches.getFirst():displayed;
    }
    static void configure(ComboBox<String> box,long attribute){
        box.setConverter(new StringConverter<>(){
            @Override public String toString(String code){return label(attribute,code);}
            @Override public String fromString(String text){return value(attribute,text,box.getItems());}
        });
        box.setCellFactory(view->cell(attribute));box.setButtonCell(cell(attribute));
    }
    private static ListCell<String> cell(long attribute){
        return new ListCell<>(){
            @Override protected void updateItem(String item,boolean empty){super.updateItem(item,empty);setText(empty?null:label(attribute,item));}
        };
    }
}
