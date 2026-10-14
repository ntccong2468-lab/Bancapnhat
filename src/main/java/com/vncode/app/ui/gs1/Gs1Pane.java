package com.vncode.app.ui.gs1;

import com.google.gson.*;
import com.vncode.app.features.gs1.*;
import com.vncode.app.integration.gs1.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** GS1 uses the VN code shop context; construction and shop selection never contact an upstream. */
public final class Gs1Pane extends BorderPane {
    private final Gs1Repository repository=new Gs1Repository();
    private final Gs1BalanceRepository balances=new Gs1BalanceRepository();
    private List<BalanceSnapshot> balanceSnapshots=List.of();
    private BalanceSnapshot selectedBalance;
    private ChestnyZnakBalanceClient.ServiceSession balanceSession;
    private String balanceFingerprint="";
    private record CashResult(ChestnyZnakBalanceClient.ServiceSession session,List<BalanceSnapshot> snapshots,CryptoProCertificateInfo certificate){}
    private record Login(NationalCatalogGs1Client.Session session,CryptoProCertificateInfo certificate){}
    private record AccountResult(NationalCatalogGs1Client.AccountSnapshot snapshot,CryptoProCertificateInfo certificate){}
    private record AllChecks(AccountResult catalog,CashResult cash,boolean catalogFailed,boolean balanceFailed){}
    private final Gs1DashboardService dashboard=new Gs1DashboardService(repository);
    private final Gs1ReadinessService readinessService=new Gs1ReadinessService(dashboard);
    private Context currentContext;
    private CryptoProCertificateInfo signedCertificate;
    private JsonObject applicationForm;
    private boolean newApplication;
    private Boolean czAuthenticated;
    private String environment="unsupported";
    private final Gs1LettersService letters=new Gs1LettersService(repository);
    private final Gs1PrefixRequestService prefixRequests=new Gs1PrefixRequestService(repository);
    private final Gs1AutoReminderService autoReminders=new Gs1AutoReminderService(repository);
    private final I18nService i18n=I18nService.getInstance();
    private final Consumer<AppLanguage> languageListener=language->Platform.runLater(()->{if(!this.disposed)render();});
    private final AtomicInteger tasks=new AtomicInteger();
    private final ListView<Gs1Repository.Request> requests=new ListView<>();
    private final VBox conversation=new VBox(12);
    private final ScrollPane conversationScroll=new ScrollPane(conversation);
    private final ScrollPane headerScroll=new ScrollPane();
    private final Label summary=new Label();
    private final Label detail=new Label();
    private final Label status=new Label();
    private Shop shop;
    private String inn="";
    private Gs1Membership membership;
    private volatile long generation;
    private long requestGeneration,unreadGeneration,threadGeneration;
    private record ThreadData(List<Gs1Repository.Message> messages,List<Gs1Repository.AttachmentSummary> attachments,boolean invoice,boolean proof,Gs1ReminderPolicy.Result reminder,Gs1AutoReminderService.Status automatic,Gs1MailAccount account,Gs1MailClient.Letter reminderPreview){}
    private final ShopReadCoordinator reads=ShopReadCoordinator.shared();
    private record Cached(Context context,String environment,Gs1Membership membership,List<BalanceSnapshot> balances){}
    private boolean busy,disposed;
    private final javafx.beans.property.BooleanProperty busyState=new javafx.beans.property.SimpleBooleanProperty(false);
    javafx.beans.property.ReadOnlyBooleanProperty busyProperty(){return busyState;}
    private IntConsumer onUnread=count->{};
    private Consumer<Boolean> onAttention=attention->{};
    private Runnable configureSignature=()->{};
    private record Context(Shop shop,ZnackModels.Settings settings,String inn,String fingerprint,CryptoProCertificateInfo certificate){}
    private record Application(NationalCatalogGs1Client.Session session,NationalCatalogGs1Client.AccountSnapshot snapshot,Map<String,List<NationalCatalogGs1Client.Choice>> dictionaries,CryptoProCertificateInfo certificate,Gs1ActionPolicy.Decision decision){}
    public Gs1Pane(){
        setPadding(new Insets(20));getStyleClass().add("gs1-workspace");
        requests.setCellFactory(list->new ListCell<>(){@Override protected void updateItem(Gs1Repository.Request item,boolean empty){super.updateItem(item,empty);setText(empty||item==null?null:item.subject()+"\n"+tr("gs1.state."+item.state().name())+" · "+displayTime(item.createdAt()));}});
        requests.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showThread(b));
        i18n.addListener(languageListener);render();
    }
    public void setOnUnreadChanged(IntConsumer callback){onUnread=Objects.requireNonNull(callback);updateUnread();}
    public void setOnAttentionChanged(Consumer<Boolean> callback){onAttention=Objects.requireNonNull(callback);publishAttention();}
    public void setOnConfigureSignature(Runnable callback){configureSignature=Objects.requireNonNull(callback);}
    public int activeTaskCount(){return tasks.get();}
    public void setShop(Shop selected){
        generation++;shop=selected;busy=false;busyState.set(false);inn="";membership=null;environment="unsupported";balanceSnapshots=List.of();selectedBalance=null;balanceSession=null;balanceFingerprint="";currentContext=null;signedCertificate=null;applicationForm=null;newApplication=false;czAuthenticated=null;
        requests.getItems().clear();conversation.getChildren().clear();onUnread.accept(0);status.setText("");render();
        if(selected==null||disposed)return;
        long expected=generation;
        reads.submitLocal(new ShopReadCoordinator.Key(selected.getId(),"gs1-context",Long.toString(expected)),()->{
            try {var owner=context(selected);var env=Gs1DashboardService.environment(owner.settings().trueApiBaseUrl());return new Cached(owner,env,dashboard.cached(owner.inn(),env).orElse(null),balances.list(owner.inn(),env));}
            catch(RuntimeException unconfigured){return null;}
        }).whenComplete((cached,error)->Platform.runLater(()->{
            if(disposed||generation!=expected||cached==null||error!=null)return;
            currentContext=cached.context();inn=cached.context().inn();environment=cached.environment();membership=cached.membership();balanceSnapshots=cached.balances();if(balanceSnapshots.size()==1)selectedBalance=balanceSnapshots.getFirst();render();reloadRequests();
        }));
    }
    private Context context(){
        var owner=context(shop);var prior=currentContext;
        if(prior!=null&&(!prior.inn().equals(owner.inn())||!prior.fingerprint().equals(owner.fingerprint())
                ||!Gs1DashboardService.environment(prior.settings().trueApiBaseUrl()).equals(Gs1DashboardService.environment(owner.settings().trueApiBaseUrl())))){
            setShop(shop);throw new IllegalStateException("gs1.account_changed");
        }
        Instant now=Instant.now();
        if(owner.certificate().validFrom().isAfter(now)||!owner.certificate().validTo().isAfter(now))throw new IllegalStateException("gs1.no_signer");
        return owner;
    }
    private static Context context(Shop selected){
        if(selected==null)throw new IllegalStateException("gs1.no_shop");
        var settings=new ZnackRepository(new ZnackModels.ShopContext(selected.getId(),selected.getName())).getSettings();
        if(settings.signerTestedAt()==null||settings.signerCertificate()==null||settings.signerCertificate().isBlank())throw new IllegalStateException("gs1.no_signer");
        try{
            JsonObject certificate=JsonParser.parseString(settings.certificateMetadataJson()).getAsJsonObject();
            String owner=NationalCatalogGs1Client.text(certificate,"inn"),fingerprint=NationalCatalogGs1Client.text(certificate,"thumbprint").replaceAll("\\s","").toUpperCase(Locale.ROOT);
            if(!fingerprint.matches("[A-F0-9]{40}")||!certificate.has("hasPrivateKey")||!certificate.get("hasPrivateKey").getAsBoolean())throw new IllegalArgumentException();
            String selector=settings.signerCertificate().replaceAll("\\s","").toUpperCase(Locale.ROOT);
            if(!selector.equals(fingerprint)&&!settings.signerCertificate().equals(NationalCatalogGs1Client.text(certificate,"selector")))throw new IllegalArgumentException();
            // Historical verified identity remains usable for local history/readiness. Fresh
            // work checks current validity in context() and again against the actual CMS.
            if(!certificate.has("validFrom")||!certificate.has("validTo"))throw new IllegalArgumentException();
            Instant.parse(certificate.get("validFrom").getAsString());Instant.parse(certificate.get("validTo").getAsString());
            Gs1Membership.requireInn(owner);
            if(settings.participantInn()!=null&&!settings.participantInn().isBlank()&&!owner.equals(settings.participantInn()))throw new IllegalArgumentException();
            return new Context(selected,settings,owner,fingerprint,new CryptoProCertificateInfo(settings.signerCertificate(),fingerprint,NationalCatalogGs1Client.text(certificate,"subject"),NationalCatalogGs1Client.text(certificate,"issuer"),owner,Instant.parse(certificate.get("validFrom").getAsString()),Instant.parse(certificate.get("validTo").getAsString()),true,NationalCatalogGs1Client.text(certificate,"provider"),""));
        }catch(RuntimeException invalid){throw new IllegalStateException("gs1.no_signer");}
    }
    /** Local-only sidebar check before the GS1 screen is created. Run on the local read worker. */
    public static boolean cachedAttention(Shop selected){
        Context owner;
        try{owner=context(selected);}catch(IllegalStateException missing){
            if("gs1.no_signer".equals(missing.getMessage()))return true;
            throw missing;
        }
        var env=Gs1DashboardService.environment(owner.settings().trueApiBaseUrl());
        var dashboard=new Gs1DashboardService();var membership=dashboard.cached(owner.inn(),env).orElse(null);
        var values=new Gs1BalanceRepository().list(owner.inn(),env);var balance=values.size()==1?values.getFirst():null;
        var scope=new Gs1ReadinessService.Scope(owner.inn(),env,owner.fingerprint(),balance==null?null:balance.scope());
        return new Gs1ReadinessService(dashboard).evaluate(new Gs1ReadinessService.Input(scope,owner.certificate(),owner.settings().signerTestedAt(),null,null,balance,membership),Instant.now()).hasActionRequired();
    }
    private ZnackSignatureProvider identitySigner(Context owner,java.util.concurrent.atomic.AtomicReference<CryptoProCertificateInfo> identity){
        ZnackSigningSession.authorizeShop(owner.shop().getId());
        var provider=new CryptoProSignatureProvider(owner.settings().cryptcpPath(),owner.fingerprint(),Duration.ofSeconds(owner.settings().resolvedCryptoProTimeoutSeconds()));
        var guarded=ZnackSigningSession.guard(owner.shop().getId(),owner.fingerprint(),provider);
        return (payload,signingContext)->{
            var result=guarded.sign(payload,signingContext);
            var certificate=CryptoProSignedCertificateReader.read(result,owner.fingerprint());
            if(!owner.inn().equals(certificate.inn())||certificate.validFrom()==null||certificate.validTo()==null
                    ||certificate.validFrom().isAfter(Instant.now())||!certificate.validTo().isAfter(Instant.now()))
                throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT,"Signing certificate enterprise is unverified.");
            identity.set(certificate);return result;
        };
    }
    private Login login(Context owner)throws IOException{
        if(!"production".equals(Gs1DashboardService.environment(owner.settings().trueApiBaseUrl())))throw new IOException("gs1.environment_unsupported");
        var identity=new java.util.concurrent.atomic.AtomicReference<CryptoProCertificateInfo>();
        var session=new NationalCatalogGs1Client().login(owner.inn(),owner.fingerprint(),identitySigner(owner,identity));
        return new Login(session,identity.get());
    }
    private Gs1ActionPolicy.Decision decision(){
        return Gs1ActionPolicy.evaluate("production".equals(environment)?membership:null,applicationForm,List.copyOf(requests.getItems()),dashboard.availability(membership,LocalDate.now(ZoneId.of("Europe/Moscow"))),LocalDate.now(ZoneId.of("Europe/Moscow")),Instant.now(),newApplication);
    }
    private Gs1ReadinessService.Snapshot readiness(){
        var owner=currentContext;
        var scope=new Gs1ReadinessService.Scope(inn,environment,owner==null?"":owner.fingerprint(),selectedBalance==null?null:selectedBalance.scope());
        Boolean authenticated=Boolean.FALSE.equals(czAuthenticated)?Boolean.FALSE:balanceSession!=null&&balanceSession.expiresAt().isAfter(Instant.now())?czAuthenticated:null;
        return readinessService.evaluate(new Gs1ReadinessService.Input(scope,signedCertificate!=null?signedCertificate:owner==null?null:owner.certificate(),owner==null?null:owner.settings().signerTestedAt(),authenticated,null,selectedBalance,membership),Instant.now());
    }
    private void publishAttention(){onAttention.accept(shop!=null&&readiness().hasActionRequired());}
    private String joinKey(Gs1ActionPolicy.Decision decision){return "gs1.action."+decision.joinAction().name();}
    private boolean joinDisabled(Gs1ActionPolicy.Decision decision){return switch(decision.joinAction()){case APPLICATION_SUBMITTED,ALREADY_MEMBER,RESTORATION_REQUESTED,RECONCILE_DELIVERY->true;default->false;};}
    private boolean actionDisabled(Gs1ReadinessService.Action action){
        if(busy)return true;
        var policy=decision();
        return switch(action){
            case JOIN_GS1->joinDisabled(policy)||inn.isBlank();
            case RENEW_MEMBERSHIP->!policy.renewal();
            case RESTORE_MEMBERSHIP->!policy.restoration();
            case REQUEST_PREFIX->!Gs1ActionPolicy.canOpenPrefixRequest(membership,Instant.now(),LocalDate.now(ZoneId.of("Europe/Moscow")));
            case LOGIN,REFRESH_BALANCE,REFRESH_MEMBERSHIP->inn.isBlank();
            default->false;
        };
    }
    private VBox readinessCard(){
        var snapshot=readiness();var checkAll=button("gs1.check_all",this::checkAll);checkAll.setId("gs1CheckAllButton");checkAll.setDisable(busy||inn.isBlank());
        var card=new VBox(12,cardHeading("gs1.readiness.title",checkAll));card.setId("gs1ReadinessCard");card.getStyleClass().add("feature-card");card.setPadding(new Insets(16));
        var checks=new GridPane();checks.setId("gs1ReadinessGrid");checks.setHgap(18);checks.setVgap(14);var cells=new ArrayList<VBox>();
        for(var check:snapshot.checks()){
            var name=new Label(tr("gs1.readiness.kind."+check.kind().name()));name.setWrapText(true);name.getStyleClass().add("text-secondary");
            String marker=switch(check.state()){case READY->"✓ ";case ACTION_REQUIRED->"! ";case UNKNOWN->"? ";};
            var state=new Label(marker+tr("gs1.readiness.state."+check.state().name()));state.setId("gs1ReadinessState"+check.kind().name());state.setWrapText(true);
            state.getStyleClass().add(switch(check.state()){case READY->"activation-active";case ACTION_REQUIRED->"text-danger";case UNKNOWN->"text-muted";});
            var hint=new Label(tr(check.messageKey()));hint.setWrapText(true);hint.getStyleClass().add("text-muted");
            var cell=new VBox(5,name,state,hint);cell.setId("gs1Readiness"+check.kind().name());cell.setMinWidth(0);cell.setMaxWidth(Double.MAX_VALUE);
            if(check.kind()==Gs1ReadinessService.Kind.GTIN&&snapshot.availability().available().isPresent()){
                var amount=new Label(snapshot.availability().available().getAsLong()+" GTIN"+(snapshot.availability().complete()?"":" · "+tr("gs1.partial")));amount.setWrapText(true);cell.getChildren().add(amount);
            }
            if(check.kind()==Gs1ReadinessService.Kind.BALANCE){
                if(snapshot.balanceAmount()!=null)cell.getChildren().add(new Label(snapshot.balanceAmount().toPlainString()+" RUB"));
                cell.getChildren().add(balanceAccountChoice());
            }
            if(check.action()!=Gs1ReadinessService.Action.NONE){
                String key=check.action()==Gs1ReadinessService.Action.JOIN_GS1?joinKey(decision()):"gs1.readiness.action."+check.action().name();
                var action=button(key,()->readinessAction(check.action()));action.setWrapText(true);action.setDisable(actionDisabled(check.action()));cell.getChildren().add(action);
            }
            cells.add(cell);checks.getChildren().add(cell);
        }
        arrangeChecks(checks,cells,2);checks.widthProperty().addListener((o,a,b)->arrangeChecks(checks,cells,b.doubleValue()<520?1:2));card.getChildren().add(checks);
        publishAttention();return card;
    }
    private static void arrangeChecks(GridPane grid,List<VBox> cells,int columns){
        if(grid.getColumnConstraints().size()==columns)return;
        grid.getColumnConstraints().clear();for(int index=0;index<columns;index++){var constraint=new ColumnConstraints();constraint.setPercentWidth(100.0/columns);constraint.setHgrow(Priority.ALWAYS);constraint.setMinWidth(0);grid.getColumnConstraints().add(constraint);}
        for(int index=0;index<cells.size();index++){GridPane.setColumnIndex(cells.get(index),index%columns);GridPane.setRowIndex(cells.get(index),index/columns);GridPane.setFillWidth(cells.get(index),true);GridPane.setValignment(cells.get(index),VPos.TOP);}
    }
    private ComboBox<BalanceSnapshot> balanceAccountChoice(){
        var account=new ComboBox<BalanceSnapshot>();account.setId("gs1BalanceAccount");account.setPromptText(tr("gs1.choose_account"));account.getItems().setAll(balanceSnapshots);account.setMinWidth(0);account.setMaxWidth(Double.MAX_VALUE);account.setDisable(busy||balanceSnapshots.isEmpty());
        account.setConverter(new javafx.util.StringConverter<>(){public String toString(BalanceSnapshot value){return value==null?tr("gs1.choose_account"):tr("gs1.balance_group")+" "+value.scope().productGroup()+" · "+tr("gs1.balance_account")+" "+value.scope().accountId();}public BalanceSnapshot fromString(String value){return null;}});
        account.setValue(selectedBalance);account.valueProperty().addListener((o,a,b)->{selectedBalance=b;render();});return account;
    }
    private HBox cardHeading(String key,Node action){var title=new Label(tr(key));title.setWrapText(true);title.getStyleClass().add("h2");title.setMinWidth(0);title.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(title,Priority.ALWAYS);var row=new HBox(12,title,action);row.setAlignment(Pos.CENTER_LEFT);return row;}
    private void readinessAction(Gs1ReadinessService.Action action){switch(action){
        case CONFIGURE_SIGNATURE,TEST_SIGNATURE->configureSignature.run();
        case LOGIN,REFRESH_BALANCE->refreshCashBalance();
        case OPEN_CZ_PORTAL,TOP_UP_BALANCE->openCzPortal();
        case REFRESH_MEMBERSHIP->refreshMembership();
        case JOIN_GS1->join();
        case RENEW_MEMBERSHIP->draft(Gs1LettersService.Kind.RENEWAL);
        case RESTORE_MEMBERSHIP->draft(Gs1LettersService.Kind.RESTORE_MEMBERSHIP);
        case REQUEST_PREFIX->draft(Gs1LettersService.Kind.MORE_CODES);
        case NONE->{}
    }}
    private void render(){
        Label title=new Label(tr("gs1.title"));title.getStyleClass().add("h1");
        Label guide=new Label(tr("gs1.guide"));guide.setWrapText(true);guide.getStyleClass().add("text-muted");
        var heading=new VBox(5,title,guide);heading.setPadding(new Insets(0,0,10,0));setTop(heading);
        summary.setWrapText(true);summary.getStyleClass().setAll("label","h2");detail.setWrapText(true);detail.getStyleClass().setAll("label","text-muted");status.setWrapText(true);
        var left=new VBox(10,membershipCard(),readinessCard(),prefixCard());left.setId("gs1AccountPanel");left.setPadding(new Insets(0,12,8,0));left.setMinWidth(0);
        headerScroll.setId("gs1AccountScroll");headerScroll.setFitToWidth(true);headerScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);headerScroll.setMinWidth(0);headerScroll.setMinHeight(0);headerScroll.setMaxHeight(Double.MAX_VALUE);headerScroll.setContent(left);
        var inboxActions=new FlowPane(8,8,button("gs1.mail_settings",this::mailSettings),button("gs1.inbox_refresh",this::refreshInbox));inboxActions.setDisable(busy||inn.isBlank());
        requests.setId("gs1Requests");requests.setPlaceholder(new Label(tr("gs1.requests_empty")));requests.setMinHeight(80);requests.setPrefHeight(145);requests.setMaxHeight(190);
        conversationScroll.setFitToWidth(true);conversationScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        conversationScroll.setId("gs1Conversation");conversationScroll.setMinHeight(100);conversation.setPadding(new Insets(8));
        if(conversation.getChildren().isEmpty()){var empty=new Label(tr("gs1.inbox_empty"));empty.setWrapText(true);empty.getStyleClass().add("text-muted");conversation.getChildren().add(empty);}
        var inboxTitle=new Label(tr("gs1.inbox_title"));inboxTitle.getStyleClass().add("h2");inboxTitle.setWrapText(true);
        var inbox=new VBox(10,inboxTitle,inboxActions,new Label(tr("gs1.requests")),requests,conversationScroll);inbox.setId("gs1InboxPanel");inbox.setPadding(new Insets(12));inbox.setMinWidth(0);inbox.setMinHeight(0);inbox.getStyleClass().add("feature-card");VBox.setVgrow(conversationScroll,Priority.ALWAYS);
        SplitPane split=new SplitPane(headerScroll,inbox);split.setId("gs1WorkspaceSplit");split.setDividerPositions(.64);split.widthProperty().addListener((o,a,b)->adaptWorkspace(split,b.doubleValue()));setCenter(split);adaptWorkspace(split,getWidth()-getPadding().getLeft()-getPadding().getRight());
        setBottom(status);BorderPane.setMargin(status,new Insets(8,0,0,0));
        requests.refresh();if(requests.getSelectionModel().getSelectedItem()!=null)showThread(requests.getSelectionModel().getSelectedItem());
    }
    private static void adaptWorkspace(SplitPane split,double width){
        var orientation=width>0&&width<840?Orientation.VERTICAL:Orientation.HORIZONTAL;
        if(split.getOrientation()!=orientation){split.setOrientation(orientation);split.setDividerPositions(orientation==Orientation.HORIZONTAL?.64:.56);}
    }
    private VBox membershipCard(){
        var checkAgain=button("gs1.check_again",this::refreshMembership);checkAgain.setId("gs1CheckMembership");checkAgain.setDisable(busy||inn.isBlank());
        var card=new VBox(9,cardHeading("gs1.account_title",checkAgain),summary,detail);card.setId("gs1MembershipCard");card.getStyleClass().add("feature-card");card.setPadding(new Insets(16));
        var snapshot=readiness();var assertion=snapshot.check(Gs1ReadinessService.Kind.MEMBERSHIP).state()==Gs1ReadinessService.State.UNKNOWN?Gs1Membership.Status.UNKNOWN:membership.status(LocalDate.now(ZoneId.of("Europe/Moscow")));
        var certificate=signedCertificate!=null?signedCertificate:currentContext==null?null:currentContext.certificate();
        String name=membership!=null&&!membership.legalName().isBlank()?membership.legalName():certificate==null?"":certificate.ownerName();
        summary.setText(inn.isBlank()?tr(shop==null?"gs1.no_shop":"gs1.no_signer"):name+" · INN: "+inn+"\n"+tr("gs1.status."+assertion.name()));
        String facts=membership==null?tr("gs1.not_checked"):tr("gs1.expiry")+": "+Objects.toString(membership.expiry(),tr("gs1.unknown_value"))+" · "+tr("gs1.checked_at")+": "+displayTime(membership.checkedAt())+"\n"+(membership.stale(Instant.now())?tr("gs1.stale"):tr("gs1.cached"))+" · "+tr("gs1.source_catalog");
        if(certificate!=null&&inn.equals(certificate.inn()))facts+="\n"+tr("gs1.signed_account")+": "+certificate.ownerName();detail.setText(facts);
        var policy=decision();var join=button(joinKey(policy),this::join);join.setId("gs1Join");join.setDisable(busy||inn.isBlank()||joinDisabled(policy));var renewal=button("gs1.renew",()->draft(Gs1LettersService.Kind.RENEWAL));renewal.setId("gs1Renew");renewal.setDisable(busy||inn.isBlank()||!policy.renewal());var actions=new FlowPane(8,8,join,renewal);
        if(policy.joiningInvoice()){var invoice=button("gs1.joining_invoice",()->draft(Gs1LettersService.Kind.JOINING_INVOICE));invoice.setDisable(busy||inn.isBlank());actions.getChildren().add(invoice);}
        Hyperlink portal=new Hyperlink(tr("gs1.official_portal"));portal.setOnAction(e->openPortal());actions.getChildren().add(portal);card.getChildren().add(actions);return card;
    }
    private VBox prefixCard(){
        var more=button("gs1.more_codes",()->draft(Gs1LettersService.Kind.MORE_CODES));more.setId("gs1MoreCodes");more.setDisable(busy||inn.isBlank()||!Gs1ActionPolicy.canOpenPrefixRequest(membership,Instant.now(),LocalDate.now(ZoneId.of("Europe/Moscow"))));
        var table=new TableView<Gs1Membership.Prefix>();table.setId("gs1PrefixTable");table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);table.setPlaceholder(new Label(tr("gs1.prefixes_unknown")));table.setMinHeight(110);
        var gcp=new TableColumn<Gs1Membership.Prefix,String>(tr("gs1.prefix.gcp"));gcp.setCellValueFactory(cell->new javafx.beans.property.SimpleStringProperty(cell.getValue().gcp()));gcp.setMinWidth(85);
        var gln=new TableColumn<Gs1Membership.Prefix,String>(tr("gs1.prefix.gln"));gln.setCellValueFactory(cell->new javafx.beans.property.SimpleStringProperty(cell.getValue().glns().isEmpty()?tr("gs1.unknown_value"):String.join("\n",cell.getValue().glns())));gln.setMinWidth(110);
        var paid=new TableColumn<Gs1Membership.Prefix,String>(tr("gs1.prefix.valid_until"));paid.setCellValueFactory(cell->new javafx.beans.property.SimpleStringProperty(Objects.toString(prefixValidUntil(cell.getValue(),membership),tr("gs1.unknown_value"))));paid.setMinWidth(105);
        var remaining=new TableColumn<Gs1Membership.Prefix,String>(tr("gs1.prefix.remaining"));remaining.setCellValueFactory(cell->new javafx.beans.property.SimpleStringProperty(Objects.toString(cell.getValue().gtinsLeft(),tr("gs1.unknown_value"))));remaining.setMinWidth(100);table.getColumns().addAll(gcp,gln,paid,remaining);
        if(membership!=null&&inn.equals(membership.inn())&&"production".equals(environment))table.getItems().setAll(Gs1ActionPolicy.prefixesForDisplay(membership,Instant.now(),LocalDate.now(ZoneId.of("Europe/Moscow"))));
        table.setPrefHeight(Math.min(260,60+Math.max(1,table.getItems().size())*54));table.setMaxHeight(300);
        var source=new Label(tr("gs1.prefix.source_notice"));source.setWrapText(true);source.getStyleClass().add("text-muted");var issue=button("gs1.catalog_issue",()->draft(Gs1LettersService.Kind.CATALOG_ISSUE));issue.setDisable(busy||inn.isBlank());var card=new VBox(10,cardHeading("gs1.prefixes_title",more),table,source,issue);card.setId("gs1PrefixesCard");card.getStyleClass().add("feature-card");card.setPadding(new Insets(16));return card;
    }
    static LocalDate prefixValidUntil(Gs1Membership.Prefix prefix,Gs1Membership member){return prefix.expiry()!=null?prefix.expiry():member==null?null:member.expiry();}
    private void checkAll(){
        Context owner;try{owner=context();if(!"production".equals(Gs1DashboardService.environment(owner.settings().trueApiBaseUrl())))throw new IllegalStateException("gs1.environment_unsupported");}catch(RuntimeException invalid){showError(invalid);return;}
        var existing=balanceSession;String priorFingerprint=balanceFingerprint;var priorSigned=signedCertificate;
        run(()->{
            com.vncode.app.features.license.LicenseRuntime.require(com.vncode.app.features.license.LicenseModels.Permission.GS1);
            AccountResult catalog=null;CashResult cash=null;boolean catalogFailed=false,balanceFailed=false;
            try{var auth=dashboard.readFresh(()->login(owner));var snapshot=auth.session().accountSnapshot();saveAccountSnapshot(owner,snapshot);catalog=new AccountResult(snapshot,auth.certificate());}catch(IOException unavailable){catalogFailed=true;}
            try{var client=new ChestnyZnakBalanceClient();var session=existing;var identity=new java.util.concurrent.atomic.AtomicReference<CryptoProCertificateInfo>(priorSigned);
                if(session==null||!owner.inn().equals(session.verifiedInn())||!owner.fingerprint().equals(priorFingerprint)||!session.expiresAt().isAfter(Instant.now()))session=client.authenticate(owner.inn(),owner.fingerprint(),identitySigner(owner,identity));
                var values=client.readAccounts(session);for(var value:values)balances.save(value);cash=new CashResult(session,values,identity.get());
            }catch(IOException unavailable){balanceFailed=true;}
            return new AllChecks(catalog,cash,catalogFailed,balanceFailed);
        },result->{
            currentContext=owner;
            if(result.catalog()!=null){var catalog=result.catalog();membership=catalog.snapshot().membership();applicationForm=catalog.snapshot().form();newApplication=catalog.snapshot().newApplication();signedCertificate=catalog.certificate();reloadRequests();}
            if(result.cash()!=null){var cash=result.cash();czAuthenticated=true;balanceSession=cash.session();balanceFingerprint=owner.fingerprint();balanceSnapshots=cash.snapshots();if(cash.certificate()!=null)signedCertificate=cash.certificate();var prior=selectedBalance;selectedBalance=prior==null?(balanceSnapshots.size()==1?balanceSnapshots.getFirst():null):balanceSnapshots.stream().filter(value->value.scope().equals(prior.scope())).findFirst().orElse(null);}
            else{czAuthenticated=null;balanceSession=null;balanceFingerprint="";}
            status.setText(tr(result.catalog()==null&&result.cash()==null?"gs1.check_failed":result.catalogFailed()||result.balanceFailed()?"gs1.check_partial":"gs1.refreshed"));render();
        });
    }
    private void refreshCashBalance(){
        Context owner;try{owner=context();if(!"production".equals(Gs1DashboardService.environment(owner.settings().trueApiBaseUrl())))throw new IllegalStateException("gs1.environment_unsupported");}catch(RuntimeException invalid){showError(invalid);return;}
        var existing=balanceSession;String priorFingerprint=balanceFingerprint;var priorSigned=signedCertificate;
        run(()->{
            var client=new ChestnyZnakBalanceClient();var session=existing;var identity=new java.util.concurrent.atomic.AtomicReference<CryptoProCertificateInfo>(priorSigned);
            if(session==null||!owner.inn().equals(session.verifiedInn())||!owner.fingerprint().equals(priorFingerprint)||!session.expiresAt().isAfter(Instant.now())) {
                session=client.authenticate(owner.inn(),owner.fingerprint(),identitySigner(owner,identity));
            }
            var values=client.readAccounts(session);for(var value:values)balances.save(value);return new CashResult(session,values,identity.get());
        },result->{currentContext=owner;signedCertificate=result.certificate();czAuthenticated=true;balanceSession=result.session();balanceFingerprint=owner.fingerprint();balanceSnapshots=result.snapshots();var prior=selectedBalance;selectedBalance=prior==null?(balanceSnapshots.size()==1?balanceSnapshots.getFirst():null):balanceSnapshots.stream().filter(value->value.scope().equals(prior.scope())).findFirst().orElse(null);status.setText(tr("gs1.refreshed"));render();},failure->{if(failure instanceof IOException){czAuthenticated=null;balanceSession=null;balanceFingerprint="";}});
    }
    private void saveAccountSnapshot(Context owner,NationalCatalogGs1Client.AccountSnapshot snapshot){
        String env=Gs1DashboardService.environment(owner.settings().trueApiBaseUrl());repository.saveMembership(snapshot.membership(),env);repository.completeMembershipRequests(snapshot.membership(),env);
    }
    private void refreshMembership(){
        Context owner;try{owner=context();}catch(RuntimeException error){showError(error);return;}
        run(()->{var auth=dashboard.readFresh(()->login(owner));var snapshot=auth.session().accountSnapshot();saveAccountSnapshot(owner,snapshot);return new AccountResult(snapshot,auth.certificate());},result->{currentContext=owner;signedCertificate=result.certificate();membership=result.snapshot().membership();applicationForm=result.snapshot().form();newApplication=result.snapshot().newApplication();status.setText(tr("gs1.refreshed"));reloadRequests();render();});
    }
    private void join(){
        Context owner=context();
        run(()->{
            var auth=dashboard.readFresh(()->login(owner));var session=auth.session();var snapshot=session.accountSnapshot();
            saveAccountSnapshot(owner,snapshot);
            var policy=Gs1ActionPolicy.evaluate(snapshot.membership(),snapshot.form(),repository.requests(owner.inn()),dashboard.availability(snapshot.membership(),LocalDate.now(ZoneId.of("Europe/Moscow"))),LocalDate.now(ZoneId.of("Europe/Moscow")),Instant.now(),snapshot.newApplication());
            Map<String,List<NationalCatalogGs1Client.Choice>> dictionaries=new LinkedHashMap<>();
            if(policy.joinAction()==Gs1ActionPolicy.JoinAction.JOIN)for(String name:List.of("opf","positions","profiles-organization","enterprise-sector","main-products","documents","provider","participation","selling-ways","distribution-channels"))dictionaries.put(name,session.dictionary(name));
            return new Application(session,snapshot,Map.copyOf(dictionaries),auth.certificate(),policy);
        },application->{
            currentContext=owner;signedCertificate=application.certificate();membership=application.snapshot().membership();applicationForm=application.snapshot().form();newApplication=application.snapshot().newApplication();render();
            if(application.decision().joinAction()==Gs1ActionPolicy.JoinAction.RESTORE_MEMBERSHIP){draft(Gs1LettersService.Kind.RESTORE_MEMBERSHIP);return;}
            if(application.decision().joinAction()!=Gs1ActionPolicy.JoinAction.JOIN){status.setText(tr(joinKey(application.decision())));return;}
            var editable=application.snapshot().form();var applicant=editable.getAsJsonObject("applicant");
            if(applicant!=null&&NationalCatalogGs1Client.text(applicant,"companyName").isBlank()&&!signedCertificate.organizationName().isBlank())applicant.addProperty("companyName",signedCertificate.organizationName());
            new Gs1JoinDialog(this,owner.inn(),editable,application.dictionaries(),
                query->application.session().addresses(query),
                (form,submit)->{
                    if(submit){
                        if(!confirm("gs1.confirm_save",tr("gs1.prepare_document_notice")))return;
                        run(()->new Gs1ApplicationService(repository).prepareSigning(application.session(),form),prepared->{reloadRequests();reviewSigningDocument(owner,application.session(),prepared);});
                    }else{
                        if(!confirm("gs1.confirm_save",tr("gs1.save_notice")))return;
                        run(()->new Gs1ApplicationService(repository).saveReviewedDraft(application.session(),form),saved->{applicationForm=saved;newApplication=false;status.setText(tr("gs1.saved"));reloadRequests();render();});
                    }
                }).show();
        });
    }
    private void reviewSigningDocument(Context owner,NationalCatalogGs1Client.Session session,Gs1ApplicationService.PreparedApplication prepared){
        Dialog<Void> dialog=dialog(tr("gs1.review_document"));dialog.getDialogPane().setPrefSize(850,650);
        TextArea content=new TextArea(prepared.description());content.setEditable(false);content.setWrapText(true);
        TextArea xml=new TextArea(prepared.xml());xml.setEditable(false);
        Tab summaryTab=new Tab(tr("gs1.review"),content),xmlTab=new Tab("XML",xml);summaryTab.setClosable(false);xmlTab.setClosable(false);
        TabPane pages=new TabPane(summaryTab,xmlTab);
        Label notice=new Label(tr("gs1.exact_document_notice")+"\nINN: "+prepared.inn()+"\n"+tr("gs1.certificate")+": "+owner.fingerprint());notice.setWrapText(true);
        Label feedback=new Label();feedback.textProperty().bind(status.textProperty());feedback.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(12,notice,pages,feedback));
        ButtonType sign=new ButtonType(tr("gs1.sign_submit"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(sign,ButtonType.CANCEL);
        Button send=(Button)dialog.getDialogPane().lookupButton(sign);send.disableProperty().bind(busyState);
        send.addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            event.consume();if(!inn.equals(owner.inn())||disposed)return;
            var signer=ZnackSigningSession.guardXml(owner.shop().getId(),owner.fingerprint(),XmlSignatureProvider.forCertificate(owner.fingerprint(),Duration.ofSeconds(Math.max(300,owner.settings().resolvedCryptoProTimeoutSeconds()))));
            run(()->new Gs1ApplicationService(repository).submit(session,prepared,signer::signXml),state->{dialog.close();if(state==Gs1Repository.State.SUBMITTED&&applicationForm!=null){applicationForm.addProperty("isSent",true);newApplication=false;}status.setText(tr("gs1.state."+state.name()));reloadRequests();render();});
        });
        dialog.showAndWait();feedback.textProperty().unbind();
    }
    private void draft(Gs1LettersService.Kind kind){
        if(kind==Gs1LettersService.Kind.MORE_CODES){draftPrefix();return;}
        Context owner=context();
        var policy=decision();
        boolean allowed=switch(kind){case RENEWAL->policy.renewal();case MORE_CODES->policy.moreCodes();case JOINING_INVOICE->policy.joiningInvoice();case RESTORE_MEMBERSHIP->policy.restoration();case CATALOG_ISSUE->true;};
        if(!allowed){status.setText(tr("gs1.action.CHECK_STATUS"));return;}
        Dialog<Void> dialog=dialog(tr("gs1.request."+kind.name()));
        String defaultName=membership==null?"":membership.legalName();if(defaultName.isBlank()&&signedCertificate!=null)defaultName=signedCertificate.organizationName();
        TextField name=new TextField(defaultName);TextArea note=new TextArea();note.setWrapText(true);note.setPrefRowCount(4);
        GridPane grid=grid();row(grid,0,tr("gs1.legal_name"),name);row(grid,1,tr("gs1.comment"),note);
        ButtonType next=new ButtonType(tr("gs1.preview"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(next,ButtonType.CANCEL);dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().lookupButton(next).disableProperty().bind(busyState);
        ((Button)dialog.getDialogPane().lookupButton(next)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            event.consume();
            try{
                String legalName=name.getText().strip(),comment=note.getText();
                if(legalName.isBlank()||legalName.length()>500||comment.length()>20000)throw new IllegalArgumentException();
                run(()->letters.draft(owner.inn(),legalName,kind,null,comment,List.of()),request->{dialog.close();reloadRequests();previewLetter(request);});
            }catch(RuntimeException invalid){showError(new IllegalStateException("gs1.invalid_fields"));}
        });
        dialog.showAndWait();
    }
    private record PrefixSource(Login auth,NationalCatalogGs1Client.AccountSnapshot snapshot){}
    private void draftPrefix(){
        Context owner=context();long expected=generation;
        run(()->{var auth=dashboard.readFresh(()->login(owner));var fresh=prefixReader(owner,expected,auth).read();return new PrefixSource(auth,fresh);},loaded->{
            currentContext=owner;signedCertificate=loaded.auth().certificate();membership=loaded.snapshot().membership();applicationForm=loaded.snapshot().form();newApplication=loaded.snapshot().newApplication();render();
            var defaults=prefixRequests.defaults(loaded.snapshot(),loaded.auth().certificate());
            var preflight=prefixRequests.preflight(owner.inn(),loaded.snapshot(),Instant.now());
            var draft=Gs1PrefixRequestDialog.show(getScene()==null?null:getScene().getWindow(),defaults,prefixRequests,preflight);
            if(draft.isEmpty())return;
            var reader=prefixReader(owner,expected,loaded.auth());
            run(()->prefixRequests.prepare(draft.get(),reader.read(),true),request->{reloadRequests();previewLetter(request,reader);});
        });
    }
    private Gs1PrefixRequestService.FreshSnapshotReader prefixReader(Context owner,long expected,Login auth){
        return ()->{
            requirePrefixOwner(owner,expected);var snapshot=auth.session().accountSnapshot();requirePrefixOwner(owner,expected);
            saveAccountSnapshot(owner,snapshot);return snapshot;
        };
    }
    private void requirePrefixOwner(Context expected,long expectedGeneration)throws IOException{
        if(disposed||generation!=expectedGeneration)throw new IOException("gs1.account_changed");
        Context actual;try{actual=context(expected.shop());}catch(IllegalStateException missing){throw new IOException("gs1.account_changed");}
        Instant now=Instant.now();
        if(!expected.inn().equals(actual.inn())||!expected.fingerprint().equals(actual.fingerprint())
                ||!"production".equals(Gs1DashboardService.environment(actual.settings().trueApiBaseUrl()))
                ||actual.certificate().validFrom().isAfter(now)||!actual.certificate().validTo().isAfter(now))throw new IOException("gs1.account_changed");
    }
    private void previewLetter(Gs1Repository.Request request){previewLetter(request,null);}
    private void previewLetter(Gs1Repository.Request request,Gs1PrefixRequestService.FreshSnapshotReader boundReader){
        Dialog<Void> preview=dialog(tr("gs1.preview"));TextArea content=new TextArea(request.body());content.setEditable(false);content.setWrapText(true);content.setPrefRowCount(15);
        var account=repository.mailAccount(request.inn());Label header=new Label(tr("gs1.from")+": "+account.map(Gs1MailAccount::from).orElse(tr("gs1.mail_not_configured"))+"\n"+tr("gs1.to")+": "+request.recipient()+"\n"+request.subject());header.setWrapText(true);
        VBox box=new VBox(10,header,content,new Label(tr("gs1.sent_not_accepted")));
        var frozenLetter=letters.letter(request);
        for(var attachment:frozenLetter.attachments())box.getChildren().add(new Label(attachment.name()+" · "+attachment.bytes().length+" bytes"));
        boolean prefix=request.kind().equals(Gs1LettersService.Kind.MORE_CODES.name());
        var pdfReviewed=new CheckBox(tr("gs1.prefix.confirm"));pdfReviewed.setWrapText(true);pdfReviewed.setId("gs1PrefixStoredPdfReview");pdfReviewed.setDisable(true);
        var pdfView=new javafx.scene.image.ImageView();pdfView.setId("gs1PrefixStoredPdf");pdfView.setFitWidth(540);pdfView.setPreserveRatio(true);
        if(prefix){var pdfScroll=new ScrollPane(pdfView);pdfScroll.setPrefViewportHeight(310);pdfScroll.setFitToWidth(true);box.getChildren().addAll(pdfScroll,pdfReviewed);}
        ButtonType send=new ButtonType(tr("gs1.send"),ButtonBar.ButtonData.OK_DONE),export=new ButtonType(tr("gs1.export"),ButtonBar.ButtonData.OTHER);
        preview.getDialogPane().getButtonTypes().addAll(send,export,ButtonType.CANCEL);preview.getDialogPane().setContent(box);
        boolean maySend=account.isPresent()&&request.state()==Gs1Repository.State.DRAFT&&(!prefix||repository.prefixRequestReview(request.inn(),request.id()).isPresent());
        if(maySend)preview.getDialogPane().lookupButton(send).disableProperty().bind(prefix?busyState.or(pdfReviewed.selectedProperty().not()):busyState);
        else preview.getDialogPane().lookupButton(send).setDisable(true);
        preview.setOnHidden(e->preview.getDialogPane().lookupButton(send).disableProperty().unbind());
        if(prefix&&frozenLetter.attachments().size()==1&&"application/pdf".equals(frozenLetter.attachments().getFirst().type())){
            preview.setOnShown(e->run(()->new Gs1PrefixDocumentService().previewTemplate(frozenLetter.attachments().getFirst().bytes()),rendered->{
                if(preview.getDialogPane().getScene()!=null&&preview.getDialogPane().getScene().getWindow().isShowing()){
                    pdfView.setImage(new javafx.scene.image.Image(new ByteArrayInputStream(rendered.png())));pdfReviewed.setDisable(false);
                }
            }));
        }
        ((Button)preview.getDialogPane().lookupButton(send)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            if(!confirm("gs1.confirm_send",tr("gs1.to")+": "+request.recipient())){event.consume();return;}
            if(request.kind().equals(Gs1LettersService.Kind.MORE_CODES.name())){
                Context owner;
                try{owner=context();if(!owner.inn().equals(request.inn()))throw new IllegalStateException("gs1.account_changed");}
                catch(RuntimeException invalid){event.consume();showError(invalid);return;}
                long expected=generation;
                run(()->{var reader=boundReader!=null?boundReader:prefixReader(owner,expected,login(owner));prefixRequests.send(account.orElseThrow(),request.inn(),request.id(),reader,true);return true;},done->{status.setText(tr("gs1.sent_not_accepted"));reloadRequests();});
            }else run(()->{letters.send(account.orElseThrow(),request.inn(),request.id());return true;},done->{status.setText(tr("gs1.sent_not_accepted"));reloadRequests();});
        });
        ((Button)preview.getDialogPane().lookupButton(export)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{event.consume();exportLetter(request,account.map(Gs1MailAccount::from).orElse("owner@vncode.local"));});
        preview.showAndWait();
    }
    private void exportLetter(Gs1Repository.Request request,String from){
        FileChooser chooser=new FileChooser();chooser.setInitialFileName("GS1-"+request.id()+".eml");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Email","*.eml"));File file=chooser.showSaveDialog(getScene()==null?null:getScene().getWindow());
        if(file!=null)run(()->{Files.write(file.toPath(),new Gs1MailClient().export(from,letters.letter(request)));return true;},done->status.setText(tr("gs1.exported")));
    }
    private String profileEmail() {
        if(applicationForm==null||!applicationForm.has("applicant")||!applicationForm.get("applicant").isJsonObject())return "";
        var applicant=applicationForm.getAsJsonObject("applicant");if(!inn.equals(NationalCatalogGs1Client.text(applicant,"inn")))return "";
        String email=NationalCatalogGs1Client.text(applicant,"email").strip();return email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")?email:"";
    }
    private void mailSettings(){
        Context owner=context();var existing=repository.mailAccount(owner.inn());Dialog<Void> dialog=dialog(tr("gs1.mail_settings"));GridPane grid=grid();
        TextField smtp=new TextField(existing.map(Gs1MailAccount::smtpHost).orElse("")),smtpPort=new TextField(existing.map(a->Integer.toString(a.smtpPort())).orElse("465")),imap=new TextField(existing.map(Gs1MailAccount::imapHost).orElse("")),imapPort=new TextField(existing.map(a->Integer.toString(a.imapPort())).orElse("993")),username=new TextField(existing.map(Gs1MailAccount::username).orElse(profileEmail())),from=new TextField(existing.map(Gs1MailAccount::from).orElse(profileEmail()));PasswordField password=new PasswordField();
        row(grid,0,"SMTP TLS",smtp);row(grid,1,tr("gs1.port"),smtpPort);row(grid,2,"IMAP TLS",imap);row(grid,3,tr("gs1.port"),imapPort);row(grid,4,tr("gs1.username"),username);row(grid,5,tr("gs1.from"),from);row(grid,6,tr("gs1.password"),password);
        Label hint=new Label(tr("gs1.mail_security"));hint.setWrapText(true);Label feedback=new Label();feedback.setWrapText(true);feedback.textProperty().bind(status.textProperty());dialog.getDialogPane().setContent(new VBox(12,hint,grid,feedback));ButtonType save=new ButtonType(tr("gs1.save"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);if(!WindowsSecretProtector.supported())dialog.getDialogPane().lookupButton(save).setDisable(true);else dialog.getDialogPane().lookupButton(save).disableProperty().bind(busyState);
        ((Button)dialog.getDialogPane().lookupButton(save)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            event.consume();
            try{
                String smtpHost=smtp.getText().strip(),imapHost=imap.getText().strip(),user=username.getText().strip(),sender=from.getText().strip(),secret=password.getText();int sp=Integer.parseInt(smtpPort.getText()),ip=Integer.parseInt(imapPort.getText());
                new Gs1MailAccount(smtpHost,sp,imapHost,ip,user,sender,"dpapi:validation");
                if(secret.isEmpty()&&existing.isEmpty())throw new IllegalArgumentException();
                run(()->{String protectedValue=secret.isEmpty()?existing.orElseThrow().protectedPassword():new WindowsSecretProtector().protect(secret);repository.saveMailAccount(owner.inn(),new Gs1MailAccount(smtpHost,sp,imapHost,ip,user,sender,protectedValue));return true;},done->{password.clear();dialog.close();status.setText(tr("gs1.saved"));});
            }catch(RuntimeException invalid){event.consume();showError(new IllegalStateException("gs1.invalid_fields"));}
        });dialog.showAndWait();password.clear();feedback.textProperty().unbind();
    }
    private void refreshInbox(){
        Context owner=context();var account=repository.mailAccount(owner.inn());if(account.isEmpty()){showError(new IllegalStateException("gs1.mail_not_configured"));return;}
        run(()->letters.refresh(account.get(),owner.inn()),count->{reloadRequests();status.setText(tr("gs1.refreshed"));});
    }
    private void reloadRequests(){
        String selected=requests.getSelectionModel().getSelectedItem()==null?null:requests.getSelectionModel().getSelectedItem().id();
        var owner=shop;String enterprise=inn;long expected=generation,request=++requestGeneration;
        if(owner==null||enterprise.isBlank()){requests.getItems().clear();onUnread.accept(0);return;}
        reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"gs1-requests",enterprise+":"+request),()->repository.requests(enterprise)).whenComplete((items,error)->Platform.runLater(()->{
            if(disposed||expected!=generation||request!=requestGeneration||!enterprise.equals(inn)||error!=null)return;
            requests.getItems().setAll(items);requests.getItems().stream().filter(r->r.id().equals(selected)).findFirst().ifPresent(r->requests.getSelectionModel().select(r));updateUnread();render();
            if(items.isEmpty())conversation.getChildren().setAll(new Label(tr("gs1.empty_letters")));
        }));
    }
    private void showThread(Gs1Repository.Request request){
        long ticket=++threadGeneration,expected=generation;String enterprise=inn;
        conversation.getChildren().clear();if(request==null||!request.inn().equals(inn))return;
        conversation.getChildren().add(new Label(tr("gs1.loading")));
        reads.submitLocal(new ShopReadCoordinator.Key(shop==null?0:shop.getId(),"gs1-thread",enterprise+":"+request.id()+":"+ticket),()->{
            var account=repository.mailAccount(enterprise).orElse(null);
            return new ThreadData(repository.messages(enterprise,request.id()),repository.attachmentSummaries(enterprise,request.id()),repository.hasConfirmedInvoice(enterprise,request.id()),letters.canPreparePaymentProof(enterprise,request.id()),letters.reminderStatus(enterprise,request.id(),Instant.now(),true),autoReminders.status(enterprise,request.id(),account),account,request.kind().equals(Gs1LettersService.Kind.MORE_CODES.name())?autoReminders.previewReminder(enterprise,request.id()):null);
        }).whenComplete((data,error)->Platform.runLater(()->{
            if(disposed||expected!=generation||ticket!=threadGeneration||!enterprise.equals(inn))return;
            if(error!=null){conversation.getChildren().setAll(new Label(tr("gs1.invalid_fields")));return;}
            renderThread(request,data);
            SellerMutationExecutor.submit(()->{repository.markReadMessages(enterprise,request.id(),data.messages());return true;}).whenComplete((saved,failure)->Platform.runLater(()->{if(!disposed&&expected==generation&&enterprise.equals(inn))updateUnread();}));
        }));
    }
    private void renderThread(Gs1Repository.Request request,ThreadData data){
        conversation.getChildren().clear();
        Label heading=new Label(request.subject());heading.getStyleClass().add("h2");conversation.getChildren().add(heading);
        Label state=new Label(tr("gs1.state."+request.state().name()));state.setWrapText(true);conversation.getChildren().add(state);
        if(request.kind().equals("JOIN")){
            conversation.getChildren().add(button("gs1.reconcile",()->{Context owner=context();run(()->new Gs1ApplicationService(repository).reconcile(login(owner).session(),request.id()),result->{reloadRequests();refreshMembership();});}));
        }else{
            conversation.getChildren().add(button("gs1.preview",()->previewLetter(request)));
            Button proof=button("gs1.payment_proof",()->paymentProof(request));proof.setDisable(!data.proof());conversation.getChildren().add(proof);
            if(request.kind().equals(Gs1LettersService.Kind.MORE_CODES.name())&&request.state()==Gs1Repository.State.SENT){
                var automatic=new CheckBox(tr("gs1.auto_reminder"));automatic.setWrapText(true);automatic.setSelected(data.automatic().enabled());automatic.setId("gs1AutoReminder");automatic.disableProperty().bind(busyState);
                automatic.setOnAction(event->{
                    if(automatic.isSelected()){
                        if(data.account()==null){automatic.setSelected(false);showError(new IllegalStateException("gs1.mail_not_configured"));return;}
                        if(!reviewAutomaticReminder(data.account(),data.reminderPreview())){automatic.setSelected(false);return;}
                        run(()->{autoReminders.enable(request.inn(),request.id(),data.account(),data.reminderPreview(),true);return true;},saved->reloadRequests(),failure->automatic.setSelected(false));
                    }else run(()->{autoReminders.disable(request.inn(),request.id());return true;},saved->reloadRequests());
                });
                conversation.getChildren().add(automatic);
                var autoState=new Label(tr("gs1.auto_reminder.state."+data.automatic().state().name()));autoState.setWrapText(true);conversation.getChildren().add(autoState);
                var reminder=button("gs1.reminder",()->prepareReminder(request));reminder.setDisable(data.reminder()!=Gs1ReminderPolicy.Result.READY);conversation.getChildren().add(reminder);
                if(data.reminder()==Gs1ReminderPolicy.Result.WAITING){var waiting=new Label(tr("gs1.reminder_wait"));waiting.setWrapText(true);conversation.getChildren().add(waiting);}
            }
            if(request.state()==Gs1Repository.State.SENDING||request.state()==Gs1Repository.State.RECONCILE_REQUIRED)conversation.getChildren().add(button("gs1.reconcile_mail",()->reconcileMail(request)));
        }
        List<Gs1Repository.Message> messages=data.messages();
        if(messages.isEmpty()){Label draft=new Label(request.kind().equals("JOIN")?tr("gs1.submit_notice"):request.body());draft.setWrapText(true);conversation.getChildren().add(draft);}
        for(var message:messages){
            Label text=new Label();text.setWrapText(true);String[] lines=message.body().split("\n",-1);boolean collapsed=lines.length>10||message.body().length()>1600;
            String compact=String.join("\n",Arrays.copyOf(lines,Math.min(10,lines.length)));if(compact.length()>1600)compact=compact.substring(0,1600)+"…";text.setText(collapsed?compact:message.body());
            VBox bubble=new VBox(7,new Label((message.outgoing()?tr("gs1.you"):message.sender())+" · "+displayTime(message.createdAt())),text);bubble.setPadding(new Insets(12));bubble.getStyleClass().add("dashboard-feature-card");bubble.setMaxWidth(650);
            if(!message.outgoing()){Label provenance=new Label(tr("gs1.mail_unverified"));provenance.setWrapText(true);provenance.getStyleClass().add("text-muted");bubble.getChildren().add(1,provenance);}
            if(collapsed){Button expand=new Button(tr("gs1.expand"));expand.setOnAction(e->{boolean full=expand.getText().equals(tr("gs1.expand"));text.setText(full?message.body():String.join("\n",Arrays.copyOf(lines,Math.min(10,lines.length))));expand.setText(tr(full?"gs1.collapse":"gs1.expand"));});bubble.getChildren().add(expand);}
            for(var attachment:data.attachments())if(attachment.messageId().equals(message.id())){Button download=new Button(attachment.name());download.setOnAction(e->downloadAttachment(request,attachment));bubble.getChildren().add(download);}
            if(!message.outgoing()){boolean verified=data.invoice();Button invoice=button(verified?"gs1.invoice_confirmed":"gs1.confirm_invoice",()->confirmPortalInvoice(request,message));invoice.setDisable(verified||request.kind().startsWith("PAYMENT_PROOF:"));bubble.getChildren().add(invoice);}
            HBox aligned=new HBox(bubble);aligned.setAlignment(message.outgoing()?Pos.TOP_RIGHT:Pos.TOP_LEFT);conversation.getChildren().add(aligned);
        }
        long expected=generation;String threadId=request.id();
        Platform.runLater(()->{var selected=requests.getSelectionModel().getSelectedItem();if(!disposed&&generation==expected&&selected!=null&&selected.id().equals(threadId)){conversation.applyCss();conversation.layout();conversationScroll.layout();conversationScroll.setVvalue(1);}});
    }
    private void confirmPortalInvoice(Gs1Repository.Request request,Gs1Repository.Message message){
        Dialog<Void> dialog=dialog(tr("gs1.confirm_invoice"));GridPane fields=grid();
        TextField enterprise=new TextField(),number=new TextField(),amount=new TextField();
        row(fields,0,"INN",enterprise);row(fields,1,tr("gs1.invoice_number"),number);row(fields,2,tr("gs1.invoice_amount")+" (RUB)",amount);
        Label notice=new Label(tr("gs1.invoice_notice"));notice.setWrapText(true);
        Hyperlink portal=new Hyperlink(tr("gs1.official_portal"));portal.setOnAction(e->openPortal());
        CheckBox verified=new CheckBox(tr("gs1.invoice_portal_checked"));verified.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(12,notice,portal,fields,verified));
        ButtonType save=new ButtonType(tr("gs1.confirm_invoice"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
        ((Button)dialog.getDialogPane().lookupButton(save)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            try{if(!verified.isSelected())throw new IllegalArgumentException();repository.confirmInvoice(request.inn(),request.id(),message.id(),new Gs1Repository.PortalInvoice(enterprise.getText().strip(),number.getText(),amount.getText().strip(),"RUB"));showThread(request);}
            catch(RuntimeException invalid){event.consume();notice.setText(tr("gs1.invalid_fields"));}
        });dialog.showAndWait();
    }
    private void reconcileMail(Gs1Repository.Request request){
        var account=repository.mailAccount(request.inn());if(account.isEmpty()){showError(new IllegalStateException("gs1.mail_not_configured"));return;}
        if(!confirm("gs1.reconcile_mail",tr("gs1.sent_copy_notice")))return;
        FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Email","*.eml"));File file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;
        run(()->{if(Files.size(file.toPath())>15*1024*1024)throw new IOException("Mail evidence too large");return letters.reconcileSent(account.get(),request.inn(),request.id(),Files.readAllBytes(file.toPath()));},state->{status.setText(tr("gs1.sent_copy_confirmed"));reloadRequests();});
    }
    private boolean reviewAutomaticReminder(Gs1MailAccount account,Gs1MailClient.Letter reviewed){
        Dialog<Boolean> dialog=dialog(tr("gs1.auto_reminder"));
        var notice=new Label(tr("gs1.auto_reminder_notice"));notice.setWrapText(true);
        var from=new Label(tr("gs1.from")+": "+account.from()+"\n"+tr("gs1.to")+": "+reviewed.recipient()+"\n"+reviewed.subject());from.setWrapText(true);
        var content=new TextArea(reviewed.body());content.setEditable(false);content.setWrapText(true);content.setPrefRowCount(14);
        dialog.getDialogPane().setContent(new VBox(10,notice,from,content));
        var allow=new ButtonType(tr("gs1.auto_reminder_enable"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(allow,ButtonType.CANCEL);dialog.setResultConverter(button->button==allow);
        return dialog.showAndWait().orElse(false);
    }
    private void prepareReminder(Gs1Repository.Request request){
        var account=repository.mailAccount(request.inn());if(account.isEmpty()){showError(new IllegalStateException("gs1.mail_not_configured"));return;}
        if(!confirm("gs1.reminder",tr("gs1.reminder_notice")))return;
        run(()->{letters.refresh(account.get(),request.inn());return letters.prepareReminder(request.inn(),request.id(),Instant.now(),true);},reminder->{reloadRequests();previewLetter(reminder);});
    }
    private void paymentProof(Gs1Repository.Request request){
        FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("gs1.documents"),"*.pdf","*.png","*.jpg","*.jpeg"));File file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;
        run(()->{if(Files.size(file.toPath())>Gs1MailClient.MAX_ATTACHMENT)throw new IOException("Attachment too large");String extension=file.getName().toLowerCase(Locale.ROOT);String type=extension.endsWith(".pdf")?"application/pdf":extension.endsWith(".png")?"image/png":"image/jpeg";return letters.preparePaymentProof(request.inn(),request.id(),new Gs1MailClient.Attachment(file.getName(),type,Files.readAllBytes(file.toPath())));},proof->{reloadRequests();previewLetter(proof);});
    }
    private void downloadAttachment(Gs1Repository.Request request,Gs1Repository.AttachmentSummary attachment){
        FileChooser chooser=new FileChooser();chooser.setInitialFileName(attachment.name());File file=chooser.showSaveDialog(getScene()==null?null:getScene().getWindow());if(file!=null)run(()->{Files.write(file.toPath(),repository.attachment(request.inn(),request.id(),attachment).bytes());return true;},done->status.setText(tr("gs1.saved")));
    }
    private <T> void run(Callable<T> work,Consumer<T> done){run(work,done,failure->{});}
    private <T> void run(Callable<T> work,Consumer<T> done,Consumer<Throwable> failed){
        if(busy||disposed)return;
        long expected=generation;busy=true;busyState.set(true);status.setText(tr("gs1.loading"));render();tasks.incrementAndGet();
        Task<T> task=new Task<>(){@Override protected T call()throws Exception{try{return work.call();}finally{tasks.decrementAndGet();}}};
        task.setOnSucceeded(e->{if(disposed||generation!=expected)return;busy=false;busyState.set(false);render();done.accept(task.getValue());});
        java.util.function.Consumer<Throwable> taskFailure = rejection ->{if(rejection instanceof java.util.concurrent.RejectedExecutionException)tasks.decrementAndGet();if(disposed||generation!=expected)return;busy=false;busyState.set(false);failed.accept(rejection);render();reloadRequests();showError(rejection);};
        task.setOnFailed(e -> taskFailure.accept(task.getException()));AppTaskExecutor.execute(task, taskFailure);
    }
    private Button button(String key,Runnable action){Button button=new Button(tr(key));button.setOnAction(e->{if(busy||disposed)return;try{action.run();}catch(RuntimeException failure){showError(failure);}});return button;}
    private void updateUnread(){
        var owner=shop;String enterprise=inn;long expected=generation,request=++unreadGeneration;
        if(owner==null||enterprise.isBlank()){onUnread.accept(0);return;}
        reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"gs1-unread",enterprise+":"+request),()->repository.unread(enterprise)).whenComplete((count,error)->Platform.runLater(()->{if(!disposed&&expected==generation&&request==unreadGeneration&&enterprise.equals(inn)&&error==null)onUnread.accept(count);}));
    }
    private void showError(Throwable error){String message=error.getMessage();status.setText(tr(message!=null&&message.startsWith("gs1.")?message:"gs1.error"));}
    private boolean confirm(String title,String body){Alert alert=new Alert(Alert.AlertType.CONFIRMATION,body,ButtonType.YES,ButtonType.NO);AlertService.applyTheme(alert);if(getScene()!=null)alert.initOwner(getScene().getWindow());alert.setTitle(tr(title));alert.setHeaderText(tr(title));return alert.showAndWait().orElse(ButtonType.NO)==ButtonType.YES;}
    private <T> Dialog<T> dialog(String title){Dialog<T> dialog=new Dialog<>();AlertService.applyTheme(dialog);if(getScene()!=null)dialog.initOwner(getScene().getWindow());Gs1Dialogs.centerOnOwner(dialog);dialog.setTitle(title);dialog.getDialogPane().setPrefWidth(650);return dialog;}
    private static GridPane grid(){GridPane grid=new GridPane();grid.setHgap(14);grid.setVgap(10);return grid;}
    private static void row(GridPane grid,int row,String label,Node input){grid.addRow(row,new Label(label),input);GridPane.setHgrow(input,Priority.ALWAYS);}
    private String tr(String key){return i18n.tr(key);}
    private static String displayTime(Instant value){return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(value);}
    private void openPortal(){try{java.awt.Desktop.getDesktop().browse(java.net.URI.create(NationalCatalogGs1Client.PROFILE_URL));}catch(Exception unavailable){showError(new IllegalStateException("gs1.error"));}}
    private void openCzPortal(){try{java.awt.Desktop.getDesktop().browse(ChestnyZnakBalanceClient.PRODUCTION);}catch(Exception unavailable){showError(new IllegalStateException("gs1.error"));}}
    public void dispose(){disposed=true;generation++;i18n.removeListener(languageListener);}
}
