# WBCode 1.3.26 purchase replacement — implementation architecture

Scope: implement observed purchase behavior independently in VNCode-owned source. No copying CFR source wholesale, no importing WBCode binary, no app edits in this architecture task. WB artifact pinned to JAR d10efde1c0ef6e6f8cf3e850e1dd7615966b0aa1e83082181f6c8dd26380f1b6; current VN baseline is parent verified ZIP8e73de42982b9e50bc13e9776a4dd4ef4e8074deb11f5b6d177b8faf5e360533/3,947 files.

## Decision

Keep `ZnackPurchaseCoordinator` public constructors/start/enqueue/enqueueForPrint/enqueueForCirculation/resume/retry/diagnostics as the single compatibility facade. Replace all its purchase orchestration with the WB1326 behavior model. All existing producers already converge there: manual mapping, WB/Ozon shipment cards, FBO shortage, WB print attachment, Ozon receive and print shortage. GS1 is registration/authentication, not another paid purchase producer.

A port limited to removing Catalog gating and granting startup shops is incomplete. WB1326 also has durable multi-GTIN batches, separate CREATE/RETRIEVE/INTRODUCE workers, immutable batch payload/identity, quota3350 holds, safe rejected-credential recovery, release methods PRODUCTION/COMMISSION and product groups lp/shoes.

## Behavioral coverage matrix

| Concern | WB1326 observed contract | Current VN gap / implementation |
|---|---|---|
| New intent | Settings read/create/save/UI force autoIntroduction=true | Settings.empty remainsfalse and normal repository preference mayfalse. Normalize new purchase intent and new effective settings; preserve raw original snapshots for legacy paid rows. |
| Before purchase | Valid qty/GTIN, no parked duplicate, product local, local completepermit for PRODUCTION, signed/OMS/CryptoPro; no global Catalog revision gate | Coordinator and FBS `KizPurchaseMappingProof.require` both gate `requireKnownPermit→CatalogReadRevision.requireComplete`. Replace purchase permit policy on BOTH routes, retain variant identity proof. |
| One engine | Manual and shortage purchases use same coordinator/queue | Preserve facade; no legacy strategy toggle or fallback route for new requests. Old persisted jobs go through explicit compatibility adapter in the same engine. |
| Group creation | Homogeneous remote account/credential identity, product group, purchase method; up to10 distinct GTINs per SUZ batch | Add durable batch parent/members and multi-line payload creation; keep per-GTIN local pipeline/order IDs. |
| Product group | LP template10; footwear TN VED6401–6405 → shoes/template1 | Existing API hardcodeslp/template10. Add group-aware overloads with LP-compatible defaults; do not change registration calls globally. |
| Purchase method | PRODUCTION/LP_INTRODUCE_GOODS; COMMISSION/LK_INDI_COMMISSIONING | VN only PRODUCTION. Add typed method in purchase preferences/context, defaultPRODUCTION for old data; persist original method per order/pipeline. |
| Queue | Per-shop CREATE4, RETRIEVE2, INTRODUCE2; per-GTIN ordering across stages | Current one worker/shop +500ms global delay. Replace executor with lane scheduler preserving sameGTIN mutual exclusion; no per-shop head-of-line blocking by waiting retrieval. |
| Prepare/send | Persist canonical payload/hash and submission state BEFORE paidPOST, identity snapshot; signer generation check at actual mutation boundary | Existing per-job state is reusable, add batch PREPARED/SUBMITTING/ACCEPTED/recovery records and atomic beginSubmission. No duplicatePOST after uncertain outcome. |
| Ambiguous create | Reconcile sameaccount/orderlist/creationwindow/entireGTIN+qty set | Existing singleGTIN reconcile cannot prove grouped order. Add exact multiset matching; only resume polling after one unique match. |
| Quota3350 | Hold new creation30s per remote account; retrieval still proceeds | Add durable account capacity hold and CREATE-stage scheduling; never discard approved members or re-request seller approval to repeat samepurchase. |
| Credential rejection | Retry only when definite no-order proof, bounded3 attempts with0/5/15min spacing and remaining approved demand | Existing genericHTTP4xx handling too broad for full replacement. Classify known rejection, persist not-created evidence and original/replacement linkage; never retry408/5xx/transport as known negative proof. |
| Download | Remainingquantity, exactGTINbuffer, status coalescing~1s across samebatch; closed/exhausted→issuedblocks/retry | Keep VN strengthened wholepackage/identity/CIS/cardinality validation +write-ahead marker before consumingGET. Batch parentstatus query shared per remoteorder. Never treat empty blocks as no-issuance proof. |
| Readiness | APPLIED with blank/EMPTYstatusEx or allINTRODUCED; check cardflags; EMITTEDwait | Preserve actual WB stage behavior and existing VN strict per-CIS/GTIN/owner proof as a transparent quality adaptation. |
| Document | PRODUCTION read current registry docs only after code download; COMMISSION separatepayload; save documentId and reconcile ambiguoussubmit | Preserve existing no-second-document-on-unknown guards; group-aware endpoints/doc types. Missing docs parks existingpaidcodes, not anotherpurchase. |
| Completion | Only remote legal confirmation yields IN_CIRCULATION/INTRODUCED | Never restore downloaded-only success for new jobs; preserve ownership/fullCIS proof rather than WB recursive-status-count weakness. |
| Startup | Authorize every verified WB/Ozon shop notpersistentlypaused/blocked/reconfiguring, thenresume jobs | Current onlyinitialshop authorized. Add explicit verified startup policy and read pause state before granting; not blanketauthorization. |
| Signing | Per-shop generation/blocked/reconfiguring, persistent pause, scoped explicitresume; background key presence precheck; canceled/disconnectedkey defers safely | Existing generation guard can be extended. Persist pause cause; invalidate stale contexts at actual paid/document write. Keep key-disconnected distinct from permanentfailure and avoid repeatPIN prompts. |
| Restart | Persist credentials/profile identity, paid order, payload, blocks, documents, timers and approved amounts | Existing originalsettings_json/request_key/local IDs survive; add typed sidecar batch/recovery context, no destructive rebuild/newrequestkeys. |
| Existing Ozon | Marketplaces do not change upstream Catalog/SUZ issuance | Preserve exact paid-code acceptance/slot readback, sellerimportpolicy, batch PDF and mapping/GTIN semantics outside purchase engine. |

## Critical schema and hidden-gate findings

1. `ZnackSchemaSupport` creates `kiz_orders` with `UNIQUE(shop_id,external_order_id)`, oneGTIN perlocalrow. MultiGTIN SUZ batches therefore cannot write the same externalID to multiple old rows without migration. Choose one explicit design before writers start:
   - Preferred after caller audit: deliberate migration to per-GTIN uniqueness `(shop_id,external_order_id,gtin)` plus unique batchparent remoteID. SQLite cannot drop a table-levelconstraint with a simple indexdrop: rebuild must preserve everylocalID/FK/trigger/row and have rollback/backup/migration tests. Never drop a parent table while cascade-enabled FKs could erase codes/documents; select and test a transaction-safe migration mechanism.
   - Alternative only with explicit adapter changes: batchparent holds unique external ID; member rows keepNULL physicalexternalID and repository facade resolves through parent. This requires updating three Ozon paid-proof classes/four direct SQL sites (OzonPurchasedKizStock, OzonPurchasedKizAssignment, OzonAcceptanceKizReplacement); leaving those queries unchanged would falsely classify purchased codes as unpaid. The entrypoint audit found no standalone findByExternal purchase lookup otherwise.
2. Removing only Coordinator preflight is insufficient. `KizPurchaseMappingProof.require()` calls the sameglobalpermitgate after identityresolution; WBprint and Ozonreceive/print adapters use it as the beforebuy callback. Extract/rebind only thepurchasepermitpolicy, retain pure nativevariant resolution and epoch/mapping safety for allocation/registration/GTIN paths.
3. Do not make `Settings` normalization silently change historical originalsettings_json intent. New reads can expose auto=true, but unsnapshottedoldpaidrows need capture of original raw account/method/intent before normalization. ExistingCONSUMED codes mustnever becomeAVAILABLE, and opening app mustnot create extraorders/documents.
4. CFR has incomplete methods (notably enqueueBatchLocked); use javap for those branches. Treat source lengths and pseudocode as observations, not source to copy.

## Proposed non-overlapping owners

| Owner | Exclusive production files / responsibility | Shared contract |
|---|---|---|
| A persistence/models | ZnackModels, ZnackRepository, ZnackSchemaSupport; new PurchaseBatchStore/PurchaseContext/ProductGroup/PurchaseMethod/preference helpers | Publish records/method signatures first. Do not edit Coordinator/OrderService. Preserveold constructors as overloads or use sidecarcontext toavoidchangingKizOrderrecord. |
| B engine/order/queue | ZnackPurchaseCoordinator, ZnackKizOrderService, ZnackPipelineQueueExecutor; new response/reconciliation policies | Consume A's stablebatch API; routeallnewstart/enqueue via samebatchsubmission path. Existing facade methods remain. |
| C signing/resume/UI settings | ZnackSigningSession, ZnackAuthService onlyifnecessary, HomeController startup/explicitresume, ZnackAutomationController/settings FXML, new durablepause helper | Consume A's settings/pause context. No changes toFBSmainlayout orOzonacceptance. Addmethodchoice onlywhereWBpurchase preference needsit; no unrelatedredesign. |
| D retrieval/introduction/protocol | ZnackKizCodeService, ZnackIntroductionReadinessService, ZnackIntroductionService, ZnackApiClient; groupawarecall overloads | Keep old API overloads LP default forregistration callers. Keepstrictwholepackage/owner guards. CoordinateOrderService's batchstatus sharing API. |
| Parent/integration | FBS/shortage gateway changes onlywherehiddenpurchasegate exists; KizPurchaseMappingProof purchasepolicy split; test/build/rollup | Do not delete purevariantmapping guards; allmanual andFBS routes provecentralengine. |
| Read-only reviewer | Provenance/paritymatrix/migration/preservation review | No sourcewrites; verify allpriorECC/UI/Ozon/Znack workremains. |

Each owner writes own meaningful regressions in distinct new test classes. Old tests whose documented policy was deliberately replaced may update onlythoseexpectations; no disabledtest or blanket assertion weakening.

## Suggested minimal interfaces (behavior, not mandatory names)

- `ApprovedPurchase(gtin, quantity, requestKey)` immutable per-GTIN intent.
- `PurchaseBatch(id, shopId, identity, group, method, state, payload, payloadHash, remoteOrderId, attemptedAt, members)` immutable state snapshot.
- `PurchaseMember(pipelineId, localOrderId, gtin, approvedQuantity)` retains everypubliclocalID.
- Store operations: enqueueApproved/prepare/findByPipeline/findByLocalOrder/beginSubmission/acceptRemoteOrder/holdQuota/recordKnownNotCreated/claimCredentialRecovery/settingsForOrder.
- Queue `submit(shopId, gtin, WorkStage, Runnable)` with stage.CREATE/RETRIEVE/INTRODUCE; oldthree-arg method delegatesCREATE.
- API group-aware overloads preserveexistingLP signatures. Neworderpayload uses group.code/templateId +persistedmethod, notcurrentmetadata atretrytime.
- Newsubmission rechecks shop/account/signer generation immediatelybeforeHTTPmutation; preparing/signing alone doesnotclaimsuccess.

## Required acceptance cases

1. Local GTIN+completepermit +incompleteglobalCatalogrevision can reach paidPOSTmock, while badGTIN/qty/shop/signer stillblocked.
2. Allpurchaseproducers enter the sameengine; FBSbeforeBuy no longer invokes globalrevision gate, but wrongnativevariant remainsblocked.
3. MultiGTIN1/10/11/1000 group split; mixedlp/shoes/method/accountnevercoalesce; sameGTINdifferentapprovedrequests remainordered.
4. Lostcreate response/restart/ambiguousmultiplecandidates neverduplicatespurchase; uniqueconfirmedmatch attaches everylocalmember correctly.
5. Quota3350 blocksCREATE only; retrieval freesexistingorders; recovery spacing/attemptcap survivesrestart.
6. Definitelyrejectedcredentials withprovenno-order cancreate replacement foronlyremainingapproveddemand; unknownoutcome neverdoes.
7. ConsumingGETWAL/partial/invalidGTIN/duplicateCIS/extraquantity/EXHAUSTED recovery keeppriorpayment/sourceidentity.
8. APPLIED/cardflags/documents/EMITTED/INTRODUCED/owner missing/wrongowner all truthful; noHTTP200falsecompletion.
9. PRODUCTION andCOMMISSION documents differ; lp/shoes group endpoints/template correct; registrationoldAPIunaffected.
10. VerifiedunpausedmultipleWB/Ozonshops autoResume; paused/reconfiguring/unverified/disconnectedkey donotsign; explicitresume scopes originaljob.
11. LegacySQLite withmultiplealready-sentGTINjobs/localIDs/codes/documents/CONSUMED rows survivesmigration, backuprestore, startupwithoutnewPOST/doc.
12. FBSWB+Ozon print/reprint/acceptance andallpreviousUI/Znack/GTIN edits regressions; sourcebaselinepreservation checksoutsideapprovedfiles.

No real purchase/sign/license actions are authorized by this architecture report. Current userauthorizedsourceimplementation; live testing must be reported separately. No release requested.

## Final entrypoint evidence

[ENTRYPOINTS.md](ENTRYPOINTS.md) and [ENTRYPOINTS.json](ENTRYPOINTS.json) enumerate13 productionpurchase/recoverypaths,18 publicfacadecontracts and20 directlycoupled existingtestclasses. ThreeOzonclasses/fourSQLsites require a real external order ID. Also retain shared `CertificateSigningQueue` fingerprint serialization acrossshops even when introducing per-shop WB signer generations; `guardReadAuthentication` remains challenge-only.

## Existing UI/stock projection contract

Preserve static `nextCheckAt`, `hasRunningMutation`, `isSigningConfirmationPending`, injected-constructor test paths and `Repository.requiresCirculationReadback` on the same new engine. KizMappingRepository and inventory SQL read existing `znack_purchase_pipelines` directly: batch stage/error/order changes must atomically project to member rows; a parent-only batch FSM leaves UI and stock blind. Existing per-member code/download/document completion stays separate even when remote emission is grouped. Constructor compatibility must not retain a second legacy mutation engine.
