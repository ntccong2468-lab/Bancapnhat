package com.vncode.app.features.gs1;
import com.vncode.app.config.Database;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.znack.*;
import java.sql.*;
import java.nio.file.Path;
import java.net.URI;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
public final class Gs1BalanceRepository {
 @FunctionalInterface private interface Connections {Connection open()throws SQLException;}
 private final Connections connections;
 public Gs1BalanceRepository(){this.connections=Database::getConnection;init();}
 public Gs1BalanceRepository(Path path){this.connections=()->DriverManager.getConnection("jdbc:sqlite:"+path);init();}
 private void init(){try(var c=connections.open()){Gs1BalanceSchema.ensureTables(c);}catch(SQLException failure){throw unavailable();}}
 public void save(BalanceSnapshot value){try(var c=connections.open();var p=c.prepareStatement("INSERT INTO gs1_balance VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(inn,environment,product_group,account_id) DO UPDATE SET amount=excluded.amount,currency=excluded.currency,checked_at=excluded.checked_at,source=excluded.source")){var s=value.scope();Object[] values={s.inn(),s.environment(),s.productGroup(),s.accountId(),value.amount().toPlainString(),value.currency(),value.checkedAt().toString(),value.source().toString()};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}catch(SQLException failure){throw unavailable();}}
 public Optional<BalanceSnapshot> load(BalanceScope scope){return list(scope.inn(),scope.environment()).stream().filter(value->value.scope().equals(scope)).findFirst();}
 public List<BalanceSnapshot> list(String inn,String environment){Gs1Membership.requireInn(inn);if(!Set.of("production","sandbox").contains(environment))return List.of();try(var c=connections.open();var p=c.prepareStatement("SELECT * FROM gs1_balance WHERE inn=? AND environment=? ORDER BY product_group,account_id")){p.setString(1,inn);p.setString(2,environment);try(var r=p.executeQuery()){var result=new ArrayList<BalanceSnapshot>();while(r.next())try{result.add(new BalanceSnapshot(new BalanceScope(inn,environment,r.getString("product_group"),r.getString("account_id")),new BigDecimal(r.getString("amount")),r.getString("currency"),Instant.parse(r.getString("checked_at")),URI.create(r.getString("source"))));}catch(RuntimeException corrupt){}return List.copyOf(result);}}catch(SQLException failure){throw unavailable();}}
 private static IllegalStateException unavailable(){return new IllegalStateException("Scoped balance cache unavailable");}
}
