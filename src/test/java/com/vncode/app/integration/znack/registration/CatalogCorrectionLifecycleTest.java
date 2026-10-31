package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCorrectionLifecycleTest {
    private static final class Fixture implements CatalogCorrectionLifecycle.Adapter<String> {
        final List<String> calls=new ArrayList<>();
        boolean candidate=true, checkpoint=true, failBeforeSend=false, failAfterSend=false;
        String proofStatus="Rejected"; long observedId=7, proofId=7; int sends;
        public void observe(){calls.add("readback");}
        public String rejectedCandidate(){calls.add("candidate");return candidate?"row":null;}
        public ZnackNationalCatalogService.FeedProgress readProgress(String row){calls.add("progress");return progress("Rejected",observedId);}
        public ZnackNationalCatalogService.FeedProgress proveRejection(String row,long id){calls.add("proof");return proofStatus==null?null:progress(proofStatus,proofId);}
        public CatalogAutomaticRepair.Proposal propose(String row,ZnackNationalCatalogService.FeedProgress proof,long id){
            calls.add("propose");return new CatalogAutomaticRepair.Proposal(new JsonObject(),new JsonObject(),List.of(new CatalogAutomaticRepair.Change("field","a","b","proof")),"rejected",id);
        }
        public boolean checkpoint(String row,CatalogAutomaticRepair.Proposal proposal)throws IOException{
            calls.add("checkpoint");if(failBeforeSend)throw new IOException("checkpoint failed");return checkpoint;
        }
        public void submit(String row,CatalogAutomaticRepair.Proposal proposal)throws IOException{
            calls.add("submit");sends++;if(failAfterSend)throw new IOException("outcome unknown");
        }
        private static ZnackNationalCatalogService.FeedProgress progress(String status,long id){return new ZnackNationalCatalogService.FeedProgress(status,id,List.of("rejected"));}
    }
    @Test void runsOneCorrectionInDurableOrderAndReadsBack(){
        var f=new Fixture();assertDoesNotThrow(()->CatalogCorrectionLifecycle.advance(f));
        assertEquals(List.of("readback","candidate","progress","proof","propose","checkpoint","submit","readback"),f.calls);
        assertEquals(1,f.sends);
    }
    @Test void absentOrMismatchedProofNeverPlansOrSends(){
        var f=new Fixture();f.proofId=8;assertDoesNotThrow(()->CatalogCorrectionLifecycle.advance(f));assertEquals(0,f.sends);assertFalse(f.calls.contains("checkpoint"));
        var pending=new Fixture();pending.proofStatus="Processing";assertDoesNotThrow(()->CatalogCorrectionLifecycle.advance(pending));assertEquals(0,pending.sends);assertFalse(pending.calls.contains("propose"));
    }
    @Test void checkpointRejectionOrFailureStopsBeforeAnySend(){
        var f=new Fixture();f.checkpoint=false;assertDoesNotThrow(()->CatalogCorrectionLifecycle.advance(f));assertEquals(0,f.sends);
        var failed=new Fixture();failed.failBeforeSend=true;assertThrows(IOException.class,()->CatalogCorrectionLifecycle.advance(failed));assertEquals(0,failed.sends);
    }
    @Test void ambiguousSendIsNotRetriedByTheEngine(){
        var f=new Fixture();f.failAfterSend=true;assertThrows(IOException.class,()->CatalogCorrectionLifecycle.advance(f));assertEquals(1,f.sends);
        assertEquals("submit",f.calls.getLast());
        // The native journal decides the candidate on restart; this engine does not replay it.
        f.candidate=false;assertDoesNotThrow(()->CatalogCorrectionLifecycle.advance(f));assertEquals(1,f.sends);
    }
}
