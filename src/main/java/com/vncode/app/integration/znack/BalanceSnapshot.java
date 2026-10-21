package com.vncode.app.integration.znack;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
public record BalanceSnapshot(BalanceScope scope,BigDecimal amount,String currency,Instant checkedAt,URI source) {
 public BalanceSnapshot {Objects.requireNonNull(scope);Objects.requireNonNull(amount);Objects.requireNonNull(checkedAt);Objects.requireNonNull(source);if(!"RUB".equals(currency)||amount.precision()>32||amount.scale()>2||!source.isAbsolute()||source.getUserInfo()!=null||!java.util.Set.of("http","https").contains(source.getScheme()))throw new IllegalArgumentException("Invalid balance snapshot");amount=amount.setScale(2);}
 @Override public String toString(){return "BalanceSnapshot[checkedAt="+checkedAt+"]";}
}
