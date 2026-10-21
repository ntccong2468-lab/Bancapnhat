package com.vncode.app.features.gs1;

import com.google.gson.JsonObject;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import java.io.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Identity/bank guard plus a complete text/attribute preview of the exact XML to be signed. */
final class Gs1SigningDocument {
    private Gs1SigningDocument(){}
    static String validateAndDescribe(String xml,JsonObject reviewed)throws IOException {
        if(xml==null||xml.isBlank()||xml.length()>524288)throw new IOException("Invalid GS1 signing document");
        try {
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new DefaultHandler(){@Override public void error(SAXParseException e)throws SAXException{throw e;}@Override public void fatalError(SAXParseException e)throws SAXException{throw e;}});
            Document document=builder.parse(new InputSource(new StringReader(xml)));
            if(document.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#","Signature").getLength()!=0)throw new IOException("GS1 application already contains a signature");
            Set<String> scalars=new HashSet<>();StringBuilder description=new StringBuilder();
            JsonObject applicant=reviewed.getAsJsonObject("applicant");
            Map<String,String> expectedFields=new HashMap<>();
            addExpected(applicant,List.of("inn","companyName","ogrn"),expectedFields);
            if(reviewed.has("bankDetails")&&reviewed.get("bankDetails").isJsonObject())addExpected(reviewed.getAsJsonObject("bankDetails"),List.of("bik","settlementAccount","correspondentAccount"),expectedFields);
            describe(document.getDocumentElement(),scalars,description,expectedFields,0);
            for(String key:List.of("inn","companyName")){
                String expected=NationalCatalogGs1Client.text(applicant,key);
                if(expected.isBlank()||!scalars.contains(normalize(expected)))throw new IOException("GS1 document identity differs from the reviewed application");
            }
            requirePresent(applicant,List.of("ogrn"),scalars);
            if(reviewed.has("bankDetails")&&reviewed.get("bankDetails").isJsonObject())requirePresent(reviewed.getAsJsonObject("bankDetails"),List.of("bik","settlementAccount","correspondentAccount"),scalars);
            // No unknown XML schema is guessed. These guards complement mandatory review of
            // every text/attribute below, and the retained XML digest binds the actual signature.
            return description.toString();
        } catch(IOException failure){throw failure;}
        catch(Exception invalid){throw new IOException("GS1 signing document could not be validated; review it in the official portal");}
    }
    private static void requirePresent(JsonObject fields,List<String> keys,Set<String> scalars)throws IOException{
        for(String key:keys){String expected=NationalCatalogGs1Client.text(fields,key);if(!expected.isBlank()&&!scalars.contains(normalize(expected)))throw new IOException("GS1 document fields differ from the reviewed application");}
    }
    private static void addExpected(JsonObject source,List<String> keys,Map<String,String> expected){for(String key:keys){String value=NationalCatalogGs1Client.text(source,key);if(!value.isBlank())expected.put(key.toLowerCase(Locale.ROOT),normalize(value));}}
    private static void verifyNamedField(Node node,Map<String,String> expected)throws IOException{
        String key=Objects.requireNonNullElse(node.getLocalName(),node.getNodeName()).toLowerCase(Locale.ROOT);
        if(key.equals("инн"))key="inn";
        String reviewed=expected.get(key);
        if(reviewed==null)return;
        String value=node instanceof Element element?element.getTextContent():node.getNodeValue();
        if(!reviewed.equals(normalize(value)))throw new IOException("GS1 document contains conflicting enterprise or bank fields");
    }
    private static void describe(Element element,Set<String> scalars,StringBuilder description,Map<String,String> expected,int depth)throws IOException{
        if(depth>64)throw new IOException("GS1 signing document nesting exceeds the limit");
        verifyNamedField(element,expected);
        String indent="  ".repeat(depth),name=element.getTagName();
        var attributes=element.getAttributes();
        for(int i=0;i<attributes.getLength();i++){Node attr=attributes.item(i);if(XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI()))continue;verifyNamedField(attr,expected);scalars.add(normalize(attr.getNodeValue()));description.append(indent).append(name).append(" @").append(attr.getNodeName()).append(": ").append(attr.getNodeValue()).append('\n');}
        StringBuilder ownText=new StringBuilder();
        for(Node child=element.getFirstChild();child!=null;child=child.getNextSibling()){
            if(child instanceof Element nested)describe(nested,scalars,description,expected,depth+1);
            else if(child.getNodeType()==Node.TEXT_NODE||child.getNodeType()==Node.CDATA_SECTION_NODE)ownText.append(child.getNodeValue());
        }
        String text=normalize(ownText.toString());
        if(!text.isBlank()){scalars.add(text);description.append(indent).append(name).append(": ").append(text).append('\n');}
    }
    private static String normalize(String value){return value.strip().replaceAll("\\s+"," ");}
}
