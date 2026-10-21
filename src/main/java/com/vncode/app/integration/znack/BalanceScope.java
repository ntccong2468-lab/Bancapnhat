package com.vncode.app.integration.znack;
import com.vncode.app.integration.gs1.Gs1Membership;
import java.util.Set;
public record BalanceScope(String inn,String environment,String productGroup,String accountId) {
 public BalanceScope {Gs1Membership.requireInn(inn);if(!Set.of("production","sandbox").contains(environment)||productGroup==null||!productGroup.matches("[0-9]{1,12}")||accountId==null||!accountId.matches("[0-9]{1,30}"))throw new IllegalArgumentException("Invalid balance scope");}
 @Override public String toString(){return "BalanceScope[environment="+environment+", group="+productGroup+"]";}
}
