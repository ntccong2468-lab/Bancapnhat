package com.vncode.app.features.print.history;

import com.vncode.app.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PrintHistoryRepository {
    private record PrintScope(java.nio.file.Path directory,int shopId,String kind,String item){}
    private static final java.util.Set<PrintScope> ACTIVE_PRINTS=new java.util.HashSet<>();
    /** Only active operations retain claims; same-profile duplicate exports fail before reserving. */
    public static PrintClaim claimKizExport(int shopId,String gtin){
        return claim(shopId,"KIZ",List.of(com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin)));
    }
    public static PrintClaim claimOrders(int shopId,List<Long> orders){
        var ids=orders.stream().filter(id->id!=null&&id>0).map(String::valueOf).distinct().toList();
        return claim(shopId,"WB",ids.isEmpty()?List.of("unidentified"):ids);
    }
    private static PrintClaim claim(int shopId,String kind,List<String> items){
        var directory=com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
        var keys=items.stream().map(id->new PrintScope(directory,shopId,kind,id)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        synchronized(ACTIVE_PRINTS){
            if(keys.stream().anyMatch(ACTIVE_PRINTS::contains))throw new IllegalStateException("Đang xuất nhãn cho các mã hoặc đơn hàng này. Chờ thao tác hiện tại kết thúc.");
            ACTIVE_PRINTS.addAll(keys);
        }
        return new PrintClaim(keys);
    }
    public static final class PrintClaim implements AutoCloseable {
        private final java.util.Set<PrintScope> keys;private boolean closed;
        private PrintClaim(java.util.Set<PrintScope> keys){this.keys=keys;}
        @Override public void close(){synchronized(ACTIVE_PRINTS){if(!closed){ACTIVE_PRINTS.removeAll(keys);closed=true;}}}
    }
    public void requireNoRecoverableOrders(int shopId,List<Long> orders) {
        for(int start=0;start<orders.size();start+=400){var batch=orders.subList(start,Math.min(start+400,orders.size()));
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM print_jobs j JOIN print_job_items i ON i.print_job_id=j.id WHERE j.shop_id=? AND j.status='pdf_ready' AND j.supply_id NOT LIKE 'FBO/%' AND j.supply_id NOT LIKE 'KIZ/%' AND i.order_id IN ("+String.join(",",java.util.Collections.nCopies(batch.size(),"?"))+") LIMIT 1")){
                p.setInt(1,shopId);for(int i=0;i<batch.size();i++)p.setLong(i+2,batch.get(i));
                try(var r=p.executeQuery()){if(r.next())throw recoveryRequired();}
            }catch(SQLException failure){throw new IllegalStateException("Không thể kiểm tra lịch sử nhãn đã lưu.",failure);}
        }
    }
    public void requireNoRecoverableKiz(int shopId,String gtin) {
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM print_jobs WHERE shop_id=? AND status='pdf_ready' AND supply_id LIKE 'KIZ/%' AND json_extract(template_layout_json,'$.gtin')=? LIMIT 1")){
            p.setInt(1,shopId);p.setString(2,gtin);try(var r=p.executeQuery()){if(r.next())throw recoveryRequired();}
        }catch(SQLException failure){throw new IllegalStateException("Không thể kiểm tra lịch sử nhãn KIZ đã lưu.",failure);}
    }
    private static IllegalStateException recoveryRequired(){return new IllegalStateException("Có nhãn đã sử dụng mã nhưng chưa xuất xong. Mở Lịch sử in và in lại bản đã lưu trước khi lấy mã mới.");}
    public long insertPreparedJob(com.vncode.app.models.Shop shop,String supplyId,String supplyName,
                                  String printedAt,Integer templateId,String templateName,String templateJson,
                                  List<PrintHistoryItem> items) {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        return insertJob(shop.getId(),shop.getName(),shop.getMarketplace().name(),supplyId,supplyName,
                printedAt,items.size(),templateId,templateName,templateJson,"prepared",null,items);
    }
    public void finishPrintJob(int shopId,long id,String expected,String target) {
        if(!java.util.Set.of("prepared","pdf_ready").contains(expected)
                ||!java.util.Set.of("success","failed").contains(target))throw new IllegalArgumentException("Invalid print checkpoint");
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE print_jobs SET status=? WHERE id=? AND shop_id=? AND status IN (?,?)")){
            p.setString(1,target);p.setLong(2,id);p.setInt(3,shopId);p.setString(4,expected);p.setString(5,target);
            if(p.executeUpdate()!=1)throw new IllegalStateException("Print checkpoint changed");
        }catch(SQLException failure){throw new IllegalStateException("Unable to update print checkpoint",failure);}
    }
    public long insertPreparedFboJob(com.vncode.app.models.Shop shop,String templateName,String templateJson,List<PrintHistoryItem> items) {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        return insertJob(shop.getId(),shop.getName(),shop.getMarketplace().name(),"FBO/"+java.util.UUID.randomUUID(),"FBO",Instant.now().toString(),items.size(),null,templateName,templateJson,"prepared",null,items);
    }
    public void finishFboJob(int shopId,long id,String expected,String target) {
        if(!java.util.Set.of("prepared","pdf_ready").contains(expected)||!java.util.Set.of("success","failed","pdf_ready").contains(target))throw new IllegalArgumentException("Invalid print checkpoint");
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE print_jobs SET status=? WHERE id=? AND shop_id=? AND status=? AND supply_id LIKE 'FBO/%'")){p.setString(1,target);p.setLong(2,id);p.setInt(3,shopId);p.setString(4,expected);if(p.executeUpdate()!=1)throw new IllegalStateException("FBO print checkpoint changed");}catch(SQLException failure){throw new IllegalStateException("Unable to update FBO print checkpoint");}
    }
    /** Called only during process startup, while the app-data lock is held. */
    public void recoverInterruptedFboJobs() {
        // A prepared snapshot has not committed inventory consumption. pdf_ready is
        // deliberately retained: its exact codes can be exported again without buying.
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE print_jobs SET status='failed',error_message=CASE WHEN supply_id LIKE 'FBO/%' THEN 'FBO_EXPORT_INTERRUPTED' ELSE 'Quá trình xuất nhãn bị gián đoạn trước khi sử dụng mã.' END WHERE status='prepared'")){p.executeUpdate();}catch(SQLException failure){throw new IllegalStateException("Unable to recover print checkpoints",failure);}
    }

    public long insertSuccessfulJob(int shopId,
                                    String shopName,
                                    String supplyId,
                                    String supplyName,
                                    String printedAt,
                                    int itemCount,
                                    Integer templateId,
                                    String templateName,
                                    String templateLayoutJson,
                                    List<PrintHistoryItem> items) {
        return insertSuccessfulJob(shopId, shopName, "WILDBERRIES", supplyId, supplyName, printedAt,
                itemCount, templateId, templateName, templateLayoutJson, items);
    }

    public long insertSuccessfulJob(int shopId,
                                    String shopName,
                                    String marketplace,
                                    String supplyId,
                                    String supplyName,
                                    String printedAt,
                                    int itemCount,
                                    Integer templateId,
                                    String templateName,
                                    String templateLayoutJson,
                                    List<PrintHistoryItem> items) {
        return insertJob(shopId, shopName, marketplace, supplyId, supplyName, printedAt, itemCount,
                templateId, templateName, templateLayoutJson, "success", null, items);
    }

    public long insertFailedJob(int shopId,
                                String shopName,
                                String supplyId,
                                String supplyName,
                                String printedAt,
                                int itemCount,
                                Integer templateId,
                                String templateName,
                                String templateLayoutJson,
                                String errorMessage) {
        return insertFailedJob(shopId, shopName, "WILDBERRIES", supplyId, supplyName, printedAt,
                itemCount, templateId, templateName, templateLayoutJson, errorMessage);
    }

    public long insertFailedJob(int shopId,
                                String shopName,
                                String marketplace,
                                String supplyId,
                                String supplyName,
                                String printedAt,
                                int itemCount,
                                Integer templateId,
                                String templateName,
                                String templateLayoutJson,
                                String errorMessage) {
        return insertJob(shopId, shopName, marketplace, supplyId, supplyName, printedAt, itemCount,
                templateId, templateName, templateLayoutJson, "failed", errorMessage, List.of());
    }

    private long insertJob(int shopId,
                           String shopName,
                           String marketplace,
                           String supplyId,
                           String supplyName,
                           String printedAt,
                           int itemCount,
                           Integer templateId,
                           String templateName,
                           String templateLayoutJson,
                           String status,
                           String errorMessage,
                           List<PrintHistoryItem> items) {
        String jobSql = """
                INSERT INTO print_jobs(
                    shop_id, shop_name, supply_id, supply_name, printed_at, item_count,
                    template_id, template_name, template_layout_json, status, error_message, marketplace
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String itemSql = """
                INSERT INTO print_job_items(
                    print_job_id, sort_index, order_id, brand, name, subject_name, size, ru_size, color,
                    article, barcode, sticker, sticker_code, kiz, image_cache_key, external_order_id, external_item_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement jobPs = conn.prepareStatement(jobSql, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement itemPs = conn.prepareStatement(itemSql)) {
                jobPs.setInt(1, shopId);
                jobPs.setString(2, shopName);
                jobPs.setString(3, supplyId);
                jobPs.setString(4, supplyName);
                jobPs.setString(5, printedAt == null || printedAt.isBlank() ? Instant.now().toString() : printedAt);
                jobPs.setInt(6, itemCount);
                if (templateId == null) {
                    jobPs.setObject(7, null);
                } else {
                    jobPs.setInt(7, templateId);
                }
                jobPs.setString(8, templateName);
                jobPs.setString(9, templateLayoutJson);
                jobPs.setString(10, status);
                jobPs.setString(11, errorMessage);
                jobPs.setString(12, "OZON".equalsIgnoreCase(marketplace) ? "OZON" : "WILDBERRIES");
                jobPs.executeUpdate();

                long jobId;
                try (ResultSet keys = jobPs.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Cannot create print job history");
                    }
                    jobId = keys.getLong(1);
                }

                for (PrintHistoryItem item : items) {
                    itemPs.setLong(1, jobId);
                    itemPs.setInt(2, item.sortIndex());
                    itemPs.setLong(3, item.orderId());
                    itemPs.setString(4, item.brand());
                    itemPs.setString(5, item.name());
                    itemPs.setString(6, item.subjectName());
                    itemPs.setString(7, item.size());
                    itemPs.setString(8, item.ruSize());
                    itemPs.setString(9, item.color());
                    itemPs.setString(10, item.article());
                    itemPs.setString(11, item.barcode());
                    itemPs.setString(12, item.sticker());
                    itemPs.setString(13, item.stickerCode());
                    itemPs.setString(14, item.kiz());
                    itemPs.setString(15, item.imageCacheKey());
                    itemPs.setString(16, item.externalOrderId());
                    itemPs.setString(17, item.externalItemId());
                    itemPs.addBatch();
                }
                if (!items.isEmpty()) {
                    itemPs.executeBatch();
                }

                conn.commit();
                return jobId;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PrintHistoryJobSummary> findJobsByShop(int shopId) {
        List<PrintHistoryJobSummary> result = new ArrayList<>();
        String sql = """
                SELECT id, shop_id, shop_name, supply_id, supply_name, printed_at, item_count,
                       template_id, template_name, template_layout_json, status, error_message, marketplace
                FROM print_jobs
                WHERE shop_id = ?
                ORDER BY printed_at DESC, id DESC
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Integer templateId = rs.getObject("template_id") == null ? null : rs.getInt("template_id");
                result.add(new PrintHistoryJobSummary(
                        rs.getLong("id"),
                        rs.getInt("shop_id"),
                        rs.getString("shop_name"),
                        rs.getString("supply_id"),
                        rs.getString("supply_name"),
                        rs.getString("printed_at"),
                        rs.getInt("item_count"),
                        templateId,
                        rs.getString("template_name"),
                        rs.getString("template_layout_json"),
                        rs.getString("status"),
                        rs.getString("error_message"),
                        rs.getString("marketplace")
                ));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PrintHistoryItem> findItems(long printJobId) {
        List<PrintHistoryItem> result = new ArrayList<>();
        String sql = """
                SELECT print_job_id, sort_index, order_id, brand, name, subject_name, size, color,
                       ru_size, article, barcode, sticker, sticker_code, kiz, image_cache_key,
                       external_order_id, external_item_id
                FROM print_job_items
                WHERE print_job_id = ?
                ORDER BY sort_index
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, printJobId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new PrintHistoryItem(
                        rs.getLong("print_job_id"),
                        rs.getInt("sort_index"),
                        rs.getLong("order_id"),
                        rs.getString("brand"),
                        rs.getString("name"),
                        rs.getString("subject_name"),
                        rs.getString("size"),
                        rs.getString("ru_size"),
                        rs.getString("color"),
                        rs.getString("article"),
                        rs.getString("barcode"),
                        rs.getString("sticker"),
                        rs.getString("sticker_code"),
                        rs.getString("kiz"),
                        rs.getString("image_cache_key"),
                        rs.getString("external_order_id"),
                        rs.getString("external_item_id")
                ));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasSuccessfulJobForSupply(int shopId, String supplyId) {
        String sql = """
                SELECT 1
                FROM print_jobs
                WHERE shop_id = ? AND supply_id = ? AND status = 'success'
                LIMIT 1
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
