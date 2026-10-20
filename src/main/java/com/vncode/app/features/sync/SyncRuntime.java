package com.vncode.app.features.sync;

import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.gs1.Gs1AutomaticSyncService;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.util.*;
import java.util.function.Consumer;

/** One runtime per application. All network work is off FX and independent of visible routes. */
public final class SyncRuntime implements AutoCloseable {
    private static volatile SyncRuntime current;
    private final SyncCoordinator coordinator;
    private final FbsSyncService fbs=new FbsSyncService();
    private final com.vncode.app.features.gs1.Gs1SyncService gs1=new com.vncode.app.features.gs1.Gs1SyncService();
    private ZnackAutoSyncService znack;
    public SyncRuntime(Consumer<SyncCoordinator.Health> listener){
        coordinator=new SyncCoordinator(this::run);coordinator.listen(listener);current=this;
    }
    public void updateShops(Collection<Shop> shops){coordinator.updateShops(shops);}
    public void selectShop(int id){coordinator.selectShop(id);}
    public void selectSupply(int shopId,String supplyId){if(fbs.selectSupply(shopId,supplyId))coordinator.request(shopId,SyncCoordinator.Stream.SUPPLY_CONTENT);}
    public void request(Shop shop,SyncCoordinator.Stream stream){coordinator.request(shop.getId(),stream);}
    public void refreshShop(Shop shop){for(var stream:SyncCoordinator.Stream.values())if(stream!=SyncCoordinator.Stream.FINANCE)request(shop,stream);}
    public static boolean requestIfRunning(Shop shop,SyncCoordinator.Stream stream){var runtime=current;if(runtime==null)return false;runtime.request(shop,stream);return true;}
    public static boolean hasActiveWrites(){var runtime=current;return runtime!=null&&runtime.coordinator.running(SyncCoordinator.Stream.ZNACK);}
    private SyncCoordinator.Page run(Shop shop,SyncCoordinator.Stream stream,String checkpoint)throws Exception {
        if(stream!=SyncCoordinator.Stream.GS1)LicenseRuntime.require(Permission.SELLER_API);
        switch(stream){
            case FBS_QUEUE,FBS_STATUS,SUPPLIES,SUPPLY_CONTENT,HISTORY:return fbs.step(shop,stream,checkpoint);
            case CATALOG:{
                int count=shop.getMarketplace()==Marketplace.OZON?new OzonSyncWorkflow().syncCatalog(shop):new WbSyncWorkflow().syncProducts(shop);
                coordinator.request(shop.getId(),SyncCoordinator.Stream.ZNACK);
                return SyncCoordinator.Page.complete(count);
            }
            case ZNACK:{
                var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
                if(settings.signerTestedAt()==null)return SyncCoordinator.Page.complete(0);
                var registrations=new com.vncode.app.integration.znack.registration.ZnackRegistrationMonitor().poll(shop,30);
                if(registrations.warning()!=null)return new SyncCoordinator.Page("",true,registrations.published(),registrations.warning());
                if(registrations.published()>0)new ZnackCatalogSyncService().refresh(shop,true,p->{});
                // Moderation polling must not rebuild an entire 17k-product review each tick.
                // Confirmed GTIN children still advance while other cards await moderation.
                if(registrations.pending()&&registrations.published()==0&&new SyncRunRepository().active(shop,"znack_marketplace").isEmpty())return new SyncCoordinator.Page("",true,0,new SyncFailure("READBACK_PENDING",java.time.Duration.ofSeconds(15),false));
                if(znack==null)znack=new ZnackAutoSyncService();var page=znack.step(shop,checkpoint);
                return registrations.pending()&&page.warning()==null?new SyncCoordinator.Page(page.checkpoint(),page.complete(),page.changed(),new SyncFailure("READBACK_PENDING",java.time.Duration.ofSeconds(15),false)):page;
            }
            case GS1:{
                var result=gs1.sync(shop);
                return new SyncCoordinator.Page("",true,result.membership()==null?0:1,result.complete()?null:new SyncFailure("GS1_SYNC_PARTIAL",java.time.Duration.ofMinutes(1),false));
            }
            case FBO:{
                var service = new com.vncode.app.features.fbosupply.FboSupplySyncService();
                int count;
                try(var cooldown=com.vncode.app.shared.RateLimitDeferral.enter()){count=service.syncOrders(shop).size();}
                String next = service.pendingOrdersCursor(shop);
                return new SyncCoordinator.Page(next, next.isBlank(), count, null);
            }
            case FINANCE:{com.vncode.app.features.finance.FinanceSyncScheduler.getInstance().runCoordinatedStep();return SyncCoordinator.Page.complete(0);}
            default:throw new IllegalArgumentException("UNSUPPORTED_SYNC_STREAM");
        }
    }
    @Override public void close(){if(current==this)current=null;coordinator.close();}
}
