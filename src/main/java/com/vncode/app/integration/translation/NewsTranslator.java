package com.vncode.app.integration.translation;
import java.io.IOException;
import java.util.List;
public interface NewsTranslator extends AutoCloseable {default void close(){}List<String> translate(List<String> texts,String sourceLanguage,String targetLanguage)throws IOException;String version();}
