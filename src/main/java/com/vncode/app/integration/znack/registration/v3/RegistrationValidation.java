package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.integration.znack.registration.RegistrationAttributeRules;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import java.util.*;

/** Does not invent legal attributes, categories, countries, or conformity documents. */
public final class RegistrationValidation {
    private RegistrationValidation() { }
    public static List<String> issues(String gtin,Draft draft,List<Attribute> schema) {
        var errors=new ArrayList<String>();
        if(!RegisteredGtinValidator.isValid(gtin))errors.add("GTIN không hợp lệ hoặc sai số kiểm tra.");
        if(draft==null){errors.add("Chưa có thông tin thẻ.");return List.copyOf(errors);}
        if(draft.tnved()==null||!draft.tnved().matches("[0-9]{10}"))errors.add("Cần mã TN VED đủ 10 chữ số đã xác nhận.");
        if(draft.categoryId()<=0)errors.add("Chưa chọn danh mục National Catalog.");
        if(RegistrationJob.blank(draft.goodName()))errors.add("Thiếu tên sản phẩm.");
        if(schema==null||schema.isEmpty()){errors.add("Chưa đọc được thuộc tính bắt buộc từ Catalog.");return List.copyOf(errors);}
        for(var a:RegistrationAttributeRules.required(schema,draft.attributes())) {
            String v=draft.attributes().get(a.id());
            if(!RegistrationAttributeRules.validFieldValue(a,v))errors.add("Thiếu hoặc sai dữ liệu: "+a.name()+" ("+a.id()+").");
            else if(a.presetOnly()&&!a.presets().isEmpty()&&!a.presets().contains(v))errors.add("Giá trị ngoài danh sách Catalog: "+a.name()+".");
            if(!a.valueTypes().isEmpty()&&!a.valueTypes().contains(Objects.toString(draft.attributeTypes().get(a.id()),"")))errors.add("Cần loại giá trị hợp lệ: "+a.name()+".");
        }
        return List.copyOf(errors);
    }
}
